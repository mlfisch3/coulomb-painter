// WGSL port of the CUDA tile_metropolis kernel in
// projects/coulomb-brush/gpu_backend.py. One workgroup owns one tile; a 32-lane
// cooperative team sums the (2rc+1)^2 pair-potential neighbourhood for the
// proposed move, reduces via workgroup shared memory, and thread 0 runs the
// Boltzmann test.
//
// Detailed balance is preserved by the same argument the CUDA version uses:
// tiles of one colour are separated by a full tile, so with L > cutoff no
// concurrent tile's read window ever touches another tile's writable interior.
// The tile grid gets a fresh random offset per launch to keep ergodicity across
// tile seams.
//
// A pure workgroup-shared broadcast + reduction is used unconditionally instead
// of subgroups. wgpu 0.19 does not yet expose subgroup ops as a stable
// portable feature, and the workgroup path is ~30% slower on desktop but
// portable to every backend (Vulkan, Metal, DX12, GL) with no capability
// probing. When wgpu picks up stable subgroup ops the shader can be
// conditionally rewritten; the outer Rust API does not change.

struct Params {
    h: u32,
    w: u32,
    l_edge: u32,
    rc: u32,
    hop: u32,
    ntiles: u32,
    noff: u32,
    moves: u32,
    periodic: u32,
    seed: u32,
    q: f32,
    beta: f32,
};

@group(0) @binding(0) var<uniform> P: Params;
@group(0) @binding(1) var<storage, read_write> occ: array<u32>;
@group(0) @binding(2) var<storage, read> blocked_sites: array<u32>;
@group(0) @binding(3) var<storage, read> u_edge: array<f32>;
@group(0) @binding(4) var<storage, read> tile_y: array<i32>;
@group(0) @binding(5) var<storage, read> tile_x: array<i32>;
@group(0) @binding(6) var<storage, read> off_dy: array<i32>;
@group(0) @binding(7) var<storage, read> off_dx: array<i32>;
@group(0) @binding(8) var<storage, read> off_v: array<f32>;
@group(0) @binding(9) var<storage, read_write> accepted_out: array<u32>;
@group(0) @binding(10) var<storage, read_write> attempted_out: array<u32>;
@group(0) @binding(11) var<storage, read_write> energy_delta_out: array<f32>;

const WG_SIZE: u32 = 32u;

var<workgroup> ws_ok: u32;
var<workgroup> ws_y0: i32;
var<workgroup> ws_x0: i32;
var<workgroup> ws_y1: i32;
var<workgroup> ws_x1: i32;
var<workgroup> ws_partial: array<f32, 32>;
var<workgroup> ws_rng0: u32;
var<workgroup> ws_acc: u32;
var<workgroup> ws_att: u32;
var<workgroup> ws_eacc: f32;

fn xorshift(s_ptr: ptr<function, u32>) -> u32 {
    var v = *s_ptr;
    v = v ^ (v << 13u);
    v = v ^ (v >> 17u);
    v = v ^ (v << 5u);
    *s_ptr = v;
    return v;
}

fn rndf(s_ptr: ptr<function, u32>) -> f32 {
    return f32(xorshift(s_ptr) >> 8u) * (1.0 / 16777216.0);
}

fn wrap(v: i32, n: i32) -> i32 {
    return ((v % n) + n) % n;
}

@compute @workgroup_size(32)
fn tile_metropolis(
    @builtin(workgroup_id) gid: vec3<u32>,
    @builtin(local_invocation_index) lane: u32,
) {
    let warp_id = gid.x;
    if warp_id >= P.ntiles {
        return;
    }
    let oy = tile_y[warp_id];
    let ox = tile_x[warp_id];

    // Per-lane RNG stream matches the CUDA seed hash, so the same launch on
    // the same tile pool produces statistically the same acceptance rate as
    // the reference. Warmup drops the trivial early bits of xorshift.
    var s: u32 = P.seed ^ (warp_id * 2654435761u) ^ (lane * 40503u);
    for (var i: u32 = 0u; i < 4u; i = i + 1u) {
        let _junk = xorshift(&s);
    }

    if lane == 0u {
        ws_acc = 0u;
        ws_att = 0u;
        ws_eacc = 0.0;
        // The rng that picks the move + runs the Boltzmann test lives on
        // thread 0 across the whole move loop, so its state must persist in
        // workgroup memory (private state does not survive between
        // iterations for the reduction lanes anyway).
        ws_rng0 = s;
    }
    workgroupBarrier();

    let hi = i32(P.h);
    let wi = i32(P.w);
    let l_i = i32(P.l_edge);

    for (var m: u32 = 0u; m < P.moves; m = m + 1u) {
        // Thread 0 picks a move. Writes go into ws_* shared slots which the
        // whole workgroup reads after the barrier - this is the portable
        // stand-in for CUDA's __shfl_sync(lane 0) broadcast.
        if lane == 0u {
            var s0: u32 = ws_rng0;
            let r1 = xorshift(&s0);
            var y0 = oy + i32(r1 % P.l_edge);
            var x0 = ox + i32((r1 / P.l_edge) % P.l_edge);
            let dy = i32(xorshift(&s0) % (2u * P.hop + 1u)) - i32(P.hop);
            let dx = i32(xorshift(&s0) % (2u * P.hop + 1u)) - i32(P.hop);
            var y1 = y0 + dy;
            var x1 = x0 + dx;

            var ok: u32 = 0u;
            let inside_tile = (dy != 0 || dx != 0)
                && y1 >= oy && y1 < oy + l_i
                && x1 >= ox && x1 < ox + l_i;
            if inside_tile {
                if P.periodic != 0u {
                    y0 = wrap(y0, hi); x0 = wrap(x0, wi);
                    y1 = wrap(y1, hi); x1 = wrap(x1, wi);
                    ok = 1u;
                } else {
                    if y0 >= 0 && y0 < hi && x0 >= 0 && x0 < wi
                        && y1 >= 0 && y1 < hi && x1 >= 0 && x1 < wi {
                        ok = 1u;
                    }
                }
                if ok == 1u {
                    let idx0 = u32(y0) * P.w + u32(x0);
                    let idx1 = u32(y1) * P.w + u32(x1);
                    let occ0_alive = occ[idx0] != 0u;
                    let dst_free = occ[idx1] == 0u;
                    let dst_open = blocked_sites[idx1] == 0u;
                    if !(occ0_alive && dst_free && dst_open) {
                        ok = 0u;
                    }
                }
            }
            ws_ok = ok;
            ws_y0 = y0; ws_x0 = x0; ws_y1 = y1; ws_x1 = x1;
            ws_rng0 = s0;
        }
        workgroupBarrier();

        let ok_all = ws_ok;
        if ok_all == 0u {
            // Uniform branch: every lane sees the same ws_ok, so this skip is
            // safe under WGSL's barrier-in-uniform-control-flow rule.
            continue;
        }
        let y0 = ws_y0;
        let x0 = ws_x0;
        let y1 = ws_y1;
        let x1 = ws_x1;

        // Cooperative neighbourhood sum. Every lane sweeps a stride-32 slice
        // of the precomputed offset table, mirroring the CUDA warp loop.
        var part: f32 = 0.0;
        var k: u32 = lane;
        loop {
            if k >= P.noff { break; }
            let dyk = off_dy[k];
            let dxk = off_dx[k];
            let v = off_v[k];

            // Contribution gained at the new site.
            var yy = y1 + dyk;
            var xx = x1 + dxk;
            var inside: bool = true;
            if P.periodic != 0u {
                yy = wrap(yy, hi); xx = wrap(xx, wi);
            } else {
                inside = yy >= 0 && yy < hi && xx >= 0 && xx < wi;
            }
            if inside && (yy != y0 || xx != x0) && occ[u32(yy) * P.w + u32(xx)] != 0u {
                part = part + v;
            }

            // Contribution lost at the old site.
            yy = y0 + dyk;
            xx = x0 + dxk;
            inside = true;
            if P.periodic != 0u {
                yy = wrap(yy, hi); xx = wrap(xx, wi);
            } else {
                inside = yy >= 0 && yy < hi && xx >= 0 && xx < wi;
            }
            if inside && (yy != y1 || xx != x1) && occ[u32(yy) * P.w + u32(xx)] != 0u {
                part = part - v;
            }
            k = k + 32u;
        }
        ws_partial[lane] = part;
        workgroupBarrier();

        if lane == 0u {
            var total: f32 = 0.0;
            for (var j: u32 = 0u; j < 32u; j = j + 1u) {
                total = total + ws_partial[j];
            }
            let du = u_edge[u32(y1) * P.w + u32(x1)] - u_edge[u32(y0) * P.w + u32(x0)];
            let dE = P.q * P.q * total + P.q * du;

            var s0: u32 = ws_rng0;
            let r = rndf(&s0);
            let acceptp = exp(-dE * P.beta);
            var take: u32 = 0u;
            if dE <= 0.0 || r < acceptp {
                take = 1u;
            }
            ws_rng0 = s0;
            ws_att = ws_att + 1u;
            if take == 1u {
                occ[u32(y0) * P.w + u32(x0)] = 0u;
                occ[u32(y1) * P.w + u32(x1)] = 1u;
                ws_eacc = ws_eacc + dE;
                ws_acc = ws_acc + 1u;
            }
        }
        workgroupBarrier();
    }

    if lane == 0u {
        accepted_out[warp_id] = ws_acc;
        attempted_out[warp_id] = ws_att;
        energy_delta_out[warp_id] = ws_eacc;
    }
}
