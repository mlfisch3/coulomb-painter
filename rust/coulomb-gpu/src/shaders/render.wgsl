// Fullscreen triangle that samples the occupancy storage buffer and lights
// occupied cells. Intentionally minimal - M3 will replace this with the
// Android SurfaceControl path, but the pipeline shape (bind occ + params,
// draw 3 verts) is what M3 attaches to, so we keep it stable now.

struct Params {
    w: u32,
    h: u32,
};

@group(0) @binding(0) var<uniform> P: Params;
@group(0) @binding(1) var<storage, read> occ: array<u32>;

struct VsOut {
    @builtin(position) pos: vec4<f32>,
    @location(0) uv: vec2<f32>,
};

@vertex
fn vs_main(@builtin(vertex_index) vid: u32) -> VsOut {
    // Oversized triangle covering the whole clip rectangle.
    var xy = array<vec2<f32>, 3>(
        vec2<f32>(-1.0, -1.0),
        vec2<f32>( 3.0, -1.0),
        vec2<f32>(-1.0,  3.0),
    );
    var out: VsOut;
    out.pos = vec4<f32>(xy[vid], 0.0, 1.0);
    out.uv = 0.5 * (xy[vid] + vec2<f32>(1.0, 1.0));
    return out;
}

@fragment
fn fs_main(in: VsOut) -> @location(0) vec4<f32> {
    let x = clamp(u32(in.uv.x * f32(P.w)), 0u, P.w - 1u);
    let y = clamp(u32((1.0 - in.uv.y) * f32(P.h)), 0u, P.h - 1u);
    let v = occ[y * P.w + x];
    if v == 0u {
        return vec4<f32>(0.02, 0.02, 0.03, 1.0);
    }
    return vec4<f32>(0.95, 0.85, 0.35, 1.0);
}
