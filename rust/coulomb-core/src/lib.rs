// coulomb-core: CPU port of the desktop Coulomb annealer physics.
//
// The GPU path is M2's territory; this crate exists so the shipping Android
// build has a portable reference that produces the same statistical outcomes
// as the Python desktop annealer at the same params and seed, and so a bug in
// the GPU kernel later can be pinned by diffing against a CPU run of the same
// scenario. Correctness is the only performance target here.
//
// Kept single-threaded on purpose (M1 spec).

#![deny(unsafe_code)]

use num_complex::Complex64;
use rand::{Rng, SeedableRng};
use rand_pcg::Pcg64Mcg;
use rustfft::{FftPlanner, num_complex::Complex};
use std::sync::Arc;

pub const KB_EV: f64 = 8.617_333_262e-5;

// ---------------------------------------------------------------- params

/// Interaction and annealing parameters. Names track the desktop `Params`
/// dataclass; defaults track its dataclass defaults so a bare-bones scenario
/// JSON is not a trap.
#[derive(Debug, Clone)]
pub struct Params {
    pub h: usize,
    pub w: usize,
    pub seed: u64,

    // interaction
    pub strength: f64,
    pub screening: f64,
    pub cutoff: f64,
    pub periodic: bool,
    pub charge: f64,

    // short-range attraction (mobile-mobile only)
    pub attract_depth: f64,
    pub attract_range: f64,

    // annealing
    pub temperature: f64,
    pub batch: usize,
    pub batch_min: usize,
    pub batch_decrement: usize,
    pub fail_limit: usize,
    pub step_size: i32,
}

impl Default for Params {
    fn default() -> Self {
        Self {
            h: 128,
            w: 128,
            seed: 0,
            strength: 1.0,
            screening: 0.0,
            cutoff: 12.0,
            periodic: false,
            charge: 1.0,
            attract_depth: 0.0,
            attract_range: 1.5,
            temperature: 5_000.0,
            batch: 64,
            batch_min: 1,
            batch_decrement: 1,
            fail_limit: 12,
            step_size: 2,
        }
    }
}

// ---------------------------------------------------------------- brush

/// Brush settings for one stroke, in lattice cells. Mirrors `brush.Brush`.
#[derive(Debug, Clone)]
pub struct Brush {
    pub sign: f64,
    pub magnitude: f64,
    pub density: f64,
    pub thickness: f64,
    pub flow: f64,
    pub hardness: f64,
    pub penetrability: f64,
    pub coupling: f64,
}

impl Default for Brush {
    fn default() -> Self {
        Self {
            sign: 1.0,
            magnitude: 4.0,
            density: 1.0,
            thickness: 10.0,
            flow: 1.0,
            hardness: 0.5,
            penetrability: 1.0,
            coupling: 12.0,
        }
    }
}

// ---------------------------------------------------------------- stats

#[derive(Debug, Clone, Copy, Default)]
pub struct Stats {
    pub iteration: u64,
    /// Iterations that produced at least one legal proposal (denominator for
    /// acceptance rate). Iterations where every proposal was rejected as
    /// dead-on-arrival do not count toward acceptance.
    pub proposed: u64,
    /// Batches that survived proposal filtering and were accepted by the
    /// Boltzmann test.
    pub accepted: u64,
    pub dead_proposals: u64,
    pub fail_streak: u32,
    pub batch: usize,
    pub particles: usize,
    pub energy: f64,
    pub temperature: f64,
}

impl Stats {
    pub fn acceptance(&self) -> f64 {
        if self.proposed == 0 {
            0.0
        } else {
            self.accepted as f64 / self.proposed as f64
        }
    }
}

// ---------------------------------------------------------------- potential

fn potential(r: f64, params: &Params, attractive: bool) -> f64 {
    if r <= 0.0 || r > params.cutoff {
        return 0.0;
    }
    let mut v = params.strength / r;
    if params.screening > 0.0 {
        v *= (-r / params.screening).exp();
    }
    if attractive && params.attract_depth != 0.0 && params.attract_range > 0.0 {
        v -= params.attract_depth * (-r / params.attract_range).exp();
    }
    v
}

fn build_kernel(params: &Params, attractive: bool) -> (usize, Vec<f64>) {
    let rc = params.cutoff.ceil().max(1.0) as usize;
    let size = 2 * rc + 1;
    let mut k = vec![0.0f64; size * size];
    for dy in 0..size {
        for dx in 0..size {
            let ry = dy as f64 - rc as f64;
            let rx = dx as f64 - rc as f64;
            let r = (ry * ry + rx * rx).sqrt();
            k[dy * size + dx] = potential(r, params, attractive);
        }
    }
    (rc, k)
}

// -------- raised cosine radial profile shared by brush + coupling shoulder

fn radial_alpha_at(d: f64, radius: f64, hardness: f64) -> f64 {
    let h = hardness.clamp(0.0, 1.0);
    let inner = radius * h;
    if d <= inner {
        1.0
    } else if d >= radius {
        0.0
    } else if radius > inner {
        let t = (d - inner) / (radius - inner);
        0.5 * (1.0 + (std::f64::consts::PI * t).cos())
    } else {
        0.0
    }
}

/// Potential a painted cell casts on mobile charge, tapered at `coupling`.
/// Repulsive-only, same form as line charges. Mirrors `brush.coupling_kernel`.
pub fn coupling_kernel(
    strength: f64,
    screening: f64,
    coupling: f64,
    hardness: f64,
) -> (usize, Vec<f64>, usize) {
    let rp = coupling.ceil().max(1.0) as usize;
    let size = 2 * rp + 1;
    let mut k = vec![0.0f64; size * size];
    for iy in 0..size {
        for ix in 0..size {
            let ry = iy as f64 - rp as f64;
            let rx = ix as f64 - rp as f64;
            let r = (ry * ry + rx * rx).sqrt();
            if r > 0.0 && r <= coupling {
                let mut v = strength / r;
                if screening > 0.0 {
                    v *= (-r / screening).exp();
                }
                v *= radial_alpha_at(r, coupling, hardness);
                k[iy * size + ix] = v;
            }
        }
    }
    (rp, k, size)
}

// ---------------------------------------------------------------- FFT 2D

/// Real 2D circular convolution via `rustfft` on complex arrays. Cost dominated
/// by the two forward transforms; kernel spectrum is cached by the caller.
struct Fft2 {
    fwd_row: Arc<dyn rustfft::Fft<f64>>,
    fwd_col: Arc<dyn rustfft::Fft<f64>>,
    inv_row: Arc<dyn rustfft::Fft<f64>>,
    inv_col: Arc<dyn rustfft::Fft<f64>>,
    h: usize,
    w: usize,
}

impl Fft2 {
    fn new(h: usize, w: usize) -> Self {
        let mut planner = FftPlanner::<f64>::new();
        Self {
            fwd_row: planner.plan_fft_forward(w),
            fwd_col: planner.plan_fft_forward(h),
            inv_row: planner.plan_fft_inverse(w),
            inv_col: planner.plan_fft_inverse(h),
            h,
            w,
        }
    }

    fn forward(&self, real: &[f64]) -> Vec<Complex<f64>> {
        let (h, w) = (self.h, self.w);
        let mut buf: Vec<Complex<f64>> = real
            .iter()
            .map(|&x| Complex::new(x, 0.0))
            .collect();
        // row transforms
        for row in buf.chunks_mut(w) {
            self.fwd_row.process(row);
        }
        // column transforms via a strided pass
        let mut col = vec![Complex::new(0.0, 0.0); h];
        for c in 0..w {
            for r in 0..h {
                col[r] = buf[r * w + c];
            }
            self.fwd_col.process(&mut col);
            for r in 0..h {
                buf[r * w + c] = col[r];
            }
        }
        buf
    }

    fn inverse_real(&self, freq: &mut [Complex<f64>]) -> Vec<f64> {
        let (h, w) = (self.h, self.w);
        // column inverse first
        let mut col = vec![Complex::new(0.0, 0.0); h];
        for c in 0..w {
            for r in 0..h {
                col[r] = freq[r * w + c];
            }
            self.inv_col.process(&mut col);
            for r in 0..h {
                freq[r * w + c] = col[r];
            }
        }
        for row in freq.chunks_mut(w) {
            self.inv_row.process(row);
        }
        let n = (h * w) as f64;
        freq.iter().map(|c| c.re / n).collect()
    }
}

/// Convolve a real field by a real square kernel of half-size `rc`, on the
/// current boundary condition. Result has the same shape as `field`. Mirrors
/// `anneal_gui.CoulombSim._convolve` on the CPU path.
fn convolve(field: &[f64], h: usize, w: usize, kernel: &[f64], rc: usize, periodic: bool) -> Vec<f64> {
    let (fh, fw, off) = if periodic {
        (h, w, 0usize)
    } else {
        (h + 2 * rc, w + 2 * rc, rc)
    };
    let mut src = vec![0.0f64; fh * fw];
    for y in 0..h {
        for x in 0..w {
            src[(y + off) * fw + (x + off)] = field[y * w + x];
        }
    }
    // Kernel embedded at the [-rc..=rc] wrap-around origin so a linear
    // convolution comes out with the mover at the centre of its window.
    let ksize = 2 * rc + 1;
    let mut ker = vec![0.0f64; fh * fw];
    for iy in 0..ksize {
        for ix in 0..ksize {
            let ry = (iy as isize - rc as isize).rem_euclid(fh as isize) as usize;
            let rx = (ix as isize - rc as isize).rem_euclid(fw as isize) as usize;
            ker[ry * fw + rx] = kernel[iy * ksize + ix];
        }
    }
    let planner = Fft2::new(fh, fw);
    let f_src = planner.forward(&src);
    let f_ker = planner.forward(&ker);
    let mut product: Vec<Complex<f64>> = f_src
        .iter()
        .zip(f_ker.iter())
        .map(|(a, b)| a * b)
        .collect();
    let full = planner.inverse_real(&mut product);
    let mut out = vec![0.0f64; h * w];
    for y in 0..h {
        for x in 0..w {
            out[y * w + x] = full[(y + off) * fw + (x + off)];
        }
    }
    out
}

// ---------------------------------------------------------------- stroke

/// Stamps along the polyline: (y, x, dose along the path).
fn stroke_samples(points: &[[f64; 2]], first: bool) -> Vec<(f64, f64, f64)> {
    let spacing = 0.5;
    if points.is_empty() {
        return Vec::new();
    }
    let mut out = Vec::new();
    if first {
        out.push((points[0][0], points[0][1], spacing));
    }
    for i in 1..points.len() {
        let (y0, x0) = (points[i - 1][0], points[i - 1][1]);
        let (y1, x1) = (points[i][0], points[i][1]);
        let ds = (y1 - y0).hypot(x1 - x0);
        let m = ((ds / spacing).ceil() as usize).max(1);
        let dose = ds.max(spacing) / m as f64;
        for k in 1..=m {
            let t = k as f64 / m as f64;
            out.push((y0 + (y1 - y0) * t, x0 + (x1 - x0) * t, dose));
        }
    }
    out
}

struct StrokePatch {
    y0: i64,
    x0: i64,
    ph: usize,
    pw: usize,
    dq: Vec<f64>,
    cov: Vec<f64>,
}

fn stroke_patch(points: &[[f64; 2]], br: &Brush, first: bool) -> Option<StrokePatch> {
    let samples = stroke_samples(points, first);
    if samples.is_empty() {
        return None;
    }
    let r_stroke = (br.thickness / 2.0).max(0.5);
    let rr = r_stroke.ceil() as i64 + 1;
    let ys_min = samples.iter().map(|s| s.0).fold(f64::INFINITY, f64::min);
    let ys_max = samples.iter().map(|s| s.0).fold(f64::NEG_INFINITY, f64::max);
    let xs_min = samples.iter().map(|s| s.1).fold(f64::INFINITY, f64::min);
    let xs_max = samples.iter().map(|s| s.1).fold(f64::NEG_INFINITY, f64::max);
    let y0 = ys_min.floor() as i64 - rr;
    let x0 = xs_min.floor() as i64 - rr;
    let y1 = ys_max.ceil() as i64 + rr + 1;
    let x1 = xs_max.ceil() as i64 + rr + 1;
    let ph = (y1 - y0) as usize;
    let pw = (x1 - x0) as usize;
    let mut dq = vec![0.0f64; ph * pw];
    let mut cov = vec![0.0f64; ph * pw];
    let hardness = br.hardness.clamp(0.0, 1.0);
    let norm = (r_stroke * (1.0 + hardness)).max(1e-9);
    let amp = br.sign * br.magnitude * br.density * br.flow / norm;
    for (sy, sx, dose) in samples {
        let iy0 = ((sy - r_stroke).floor() as i64 - y0).max(0) as usize;
        let iy1 = (((sy + r_stroke).ceil() as i64 + 1 - y0) as usize).min(ph);
        let ix0 = ((sx - r_stroke).floor() as i64 - x0).max(0) as usize;
        let ix1 = (((sx + r_stroke).ceil() as i64 + 1 - x0) as usize).min(pw);
        if iy0 >= iy1 || ix0 >= ix1 {
            continue;
        }
        for iy in iy0..iy1 {
            let gy = (y0 + iy as i64) as f64 - sy;
            for ix in ix0..ix1 {
                let gx = (x0 + ix as i64) as f64 - sx;
                let d = gy.hypot(gx);
                let a = radial_alpha_at(d, r_stroke, hardness);
                dq[iy * pw + ix] += amp * dose * a;
                let c = &mut cov[iy * pw + ix];
                if a > *c {
                    *c = a;
                }
            }
        }
    }
    Some(StrokePatch { y0, x0, ph, pw, dq, cov })
}

// Full 2D convolution of two real arrays, size (ah+bh-1) x (aw+bw-1).
// Used to cast a stroke's dq through the coupling kernel into a local
// potential patch; mirrors `brush.potential_patch` in mode="full".
fn convolve_full(a: &[f64], ah: usize, aw: usize, b: &[f64], bh: usize, bw: usize) -> (Vec<f64>, usize, usize) {
    let oh = ah + bh - 1;
    let ow = aw + bw - 1;
    let fh = oh.next_power_of_two();
    let fw = ow.next_power_of_two();
    let mut pa = vec![0.0f64; fh * fw];
    for y in 0..ah {
        for x in 0..aw {
            pa[y * fw + x] = a[y * aw + x];
        }
    }
    let mut pb = vec![0.0f64; fh * fw];
    for y in 0..bh {
        for x in 0..bw {
            pb[y * fw + x] = b[y * bw + x];
        }
    }
    let planner = Fft2::new(fh, fw);
    let fa = planner.forward(&pa);
    let fb = planner.forward(&pb);
    let mut prod: Vec<Complex<f64>> = fa.iter().zip(fb.iter()).map(|(x, y)| x * y).collect();
    let full = planner.inverse_real(&mut prod);
    let mut out = vec![0.0f64; oh * ow];
    for y in 0..oh {
        for x in 0..ow {
            out[y * ow + x] = full[y * fw + x];
        }
    }
    (out, oh, ow)
}

// Place a patch onto the lattice under the current boundary. Returns the
// destination lattice indices and the source-slice offsets, or None if the
// patch misses entirely (or aliases onto itself on a small torus).
struct Placement {
    rows: Vec<usize>,
    cols: Vec<usize>,
    sy: usize, // source row offset
    sy_end: usize,
    sx: usize,
    sx_end: usize,
}

fn patch_index(
    y0: i64,
    x0: i64,
    ph: usize,
    pw: usize,
    h: usize,
    w: usize,
    periodic: bool,
) -> Option<Placement> {
    if periodic {
        if ph > h || pw > w {
            return None;
        }
        let rows: Vec<usize> = (0..ph)
            .map(|i| ((y0 + i as i64).rem_euclid(h as i64)) as usize)
            .collect();
        let cols: Vec<usize> = (0..pw)
            .map(|i| ((x0 + i as i64).rem_euclid(w as i64)) as usize)
            .collect();
        return Some(Placement { rows, cols, sy: 0, sy_end: ph, sx: 0, sx_end: pw });
    }
    let ry0 = y0.max(0) as usize;
    let ry1 = ((y0 + ph as i64).min(h as i64)).max(0) as usize;
    let rx0 = x0.max(0) as usize;
    let rx1 = ((x0 + pw as i64).min(w as i64)).max(0) as usize;
    if ry0 >= ry1 || rx0 >= rx1 {
        return None;
    }
    let sy = (ry0 as i64 - y0) as usize;
    let sx = (rx0 as i64 - x0) as usize;
    Some(Placement {
        rows: (ry0..ry1).collect(),
        cols: (rx0..rx1).collect(),
        sy,
        sy_end: sy + (ry1 - ry0),
        sx,
        sx_end: sx + (rx1 - rx0),
    })
}

// ---------------------------------------------------------------- Sim

// One undo record per stroke: contiguous field patches with their target
// lattice indices, so `undo_stroke` can subtract them back exactly.
struct StrokeRecord {
    u_patches: Vec<(Vec<usize>, Vec<usize>, Vec<f64>)>,
    q_patches: Vec<(Vec<usize>, Vec<usize>, Vec<f64>)>,
    b_patches: Vec<(Vec<usize>, Vec<usize>, Vec<bool>)>,
}

pub struct Sim {
    params: Params,
    rng: Pcg64Mcg,

    // fixed layer: line coverage in [0,1], contributes line_density*cov to the
    // fixed field. Kept so `clear_paint` can rebuild u_edge from the drawing.
    cov: Vec<f64>,
    line_density: f64,
    line_blocked: Vec<bool>,

    // paint layer, edited only through paint_stroke / undo_stroke / clear_paint.
    paint: Vec<f64>,
    paint_blocked: Vec<bool>,
    strokes: Vec<StrokeRecord>,

    blocked: Vec<bool>,
    u_edge: Vec<f64>,

    kernel: Vec<f64>,      // mobile-mobile (with attractive well)
    kernel_edge: Vec<f64>, // repulsive-only (lines + paint)
    rc: usize,

    occ: Vec<bool>,
    pos: Vec<(i32, i32)>, // parallel to occupied particles; index rebuilt on need
    energy: f64,

    // Counters. See Stats for meaning.
    iteration: u64,
    proposed: u64,
    accepted: u64,
    dead_proposals: u64,
    fail_streak: u32,
    live_batch: usize,
}

// The undo stack cap matches the desktop annealer: 32 strokes is enough for
// interactive editing without unbounded memory growth on a long session.
const STROKE_UNDO_CAP: usize = 32;

impl Sim {
    /// Build a sim from lattice geometry, line-charge coverage, initial
    /// occupancy, and params. `cov` and `occ0` are row-major, length h*w.
    pub fn new(
        params: Params,
        cov: Vec<f64>,
        occ0: Vec<bool>,
        line_density: f64,
        line_blocks: bool,
    ) -> Self {
        assert_eq!(cov.len(), params.h * params.w, "coverage size mismatch");
        assert_eq!(occ0.len(), params.h * params.w, "occupancy size mismatch");
        let (rc, kernel) = build_kernel(&params, true);
        let (rc2, kernel_edge) = build_kernel(&params, false);
        assert_eq!(rc, rc2);

        let line_blocked: Vec<bool> = if line_blocks {
            cov.iter().map(|&c| c > 0.5).collect()
        } else {
            vec![false; cov.len()]
        };
        let paint = vec![0.0f64; cov.len()];
        let paint_blocked = vec![false; cov.len()];
        let blocked: Vec<bool> = line_blocked
            .iter()
            .zip(paint_blocked.iter())
            .map(|(a, b)| *a || *b)
            .collect();

        let mut edge_charge = vec![0.0f64; cov.len()];
        for i in 0..cov.len() {
            edge_charge[i] = cov[i] * line_density;
        }
        let u_edge = convolve(&edge_charge, params.h, params.w, &kernel_edge, rc, params.periodic);

        let mut occ = occ0;
        // A caller might have handed us occupancy that overlaps a blocked
        // site; the physics forbids it, so scrub before building the position
        // list. Otherwise the energy would include a mover on a blocked cell.
        for i in 0..occ.len() {
            if occ[i] && blocked[i] {
                occ[i] = false;
            }
        }
        let mut pos = Vec::with_capacity(occ.iter().filter(|&&b| b).count());
        for y in 0..params.h {
            for x in 0..params.w {
                if occ[y * params.w + x] {
                    pos.push((y as i32, x as i32));
                }
            }
        }
        let live_batch = params.batch.max(params.batch_min);
        let mut s = Self {
            params: params.clone(),
            rng: Pcg64Mcg::seed_from_u64(params.seed),
            cov,
            line_density,
            line_blocked,
            paint,
            paint_blocked,
            strokes: Vec::new(),
            blocked,
            u_edge,
            kernel,
            kernel_edge,
            rc,
            occ,
            pos,
            energy: 0.0,
            iteration: 0,
            proposed: 0,
            accepted: 0,
            dead_proposals: 0,
            fail_streak: 0,
            live_batch,
        };
        s.energy = s.total_energy();
        s
    }

    /// Convenience: build a sim on a blank canvas of `h*w` with `n` mobile
    /// particles placed on evenly-strided flat lattice indices. Deterministic
    /// on purpose - the validator wants the Rust and Python engines to start
    /// from bit-identical occupancy, otherwise the initial-condition drift
    /// swamps a same-Hamiltonian equilibrium comparison at high temperature.
    pub fn new_blank(params: Params, n_particles: usize) -> Self {
        let h = params.h;
        let w = params.w;
        let cov = vec![0.0f64; h * w];
        let total = h * w;
        let take = n_particles.min(total);
        let mut occ = vec![false; total];
        if take > 0 {
            // Fixed-point stride so a fractional site count still yields the
            // requested number without collisions.
            let stride_num = total as u64;
            let stride_den = take as u64;
            for k in 0..take {
                let idx = ((k as u64 * stride_num) / stride_den) as usize;
                occ[idx] = true;
            }
        }
        Self::new(params, cov, occ, 1.0, false)
    }

    // -- energy -------------------------------------------------------

    pub fn total_energy(&self) -> f64 {
        let occ_f: Vec<f64> = self.occ.iter().map(|&b| if b { 1.0 } else { 0.0 }).collect();
        let conv = convolve(
            &occ_f,
            self.params.h,
            self.params.w,
            &self.kernel,
            self.rc,
            self.params.periodic,
        );
        let q = self.params.charge;
        let mut e_mm = 0.0;
        let mut e_edge = 0.0;
        for i in 0..occ_f.len() {
            e_mm += occ_f[i] * conv[i];
            e_edge += occ_f[i] * self.u_edge[i];
        }
        0.5 * q * q * e_mm + q * e_edge
    }

    // -- gathered neighbourhood energy for one site -------------------

    // Sum over the (2rc+1)^2 window centred at (y,x) of occ * kernel, using
    // the sim's canonical bool occupancy. Any subset of sites in `mask_out`
    // is treated as empty for the sum, so a caller can gather "without the
    // movers" for the batch-Metropolis dE without allocating a full-lattice
    // scratch copy every step.
    fn gather_bool(&self, y: i32, x: i32, mask_out: &[(i32, i32)]) -> f64 {
        let rc = self.rc as i32;
        let ksize = 2 * self.rc + 1;
        let h = self.params.h as i32;
        let w = self.params.w as i32;
        let mut acc = 0.0;
        if self.params.periodic {
            for dy in -rc..=rc {
                let ry = (y + dy).rem_euclid(h);
                for dx in -rc..=rc {
                    let rx = (x + dx).rem_euclid(w);
                    let occ = self.occ[(ry as usize) * self.params.w + rx as usize];
                    if !occ {
                        continue;
                    }
                    if mask_out.iter().any(|&(my, mx)| my == ry && mx == rx) {
                        continue;
                    }
                    let k = self.kernel[((dy + rc) as usize) * ksize + (dx + rc) as usize];
                    acc += k;
                }
            }
        } else {
            for dy in -rc..=rc {
                let ry = y + dy;
                if ry < 0 || ry >= h {
                    continue;
                }
                for dx in -rc..=rc {
                    let rx = x + dx;
                    if rx < 0 || rx >= w {
                        continue;
                    }
                    if !self.occ[(ry * w + rx) as usize] {
                        continue;
                    }
                    if mask_out.iter().any(|&(my, mx)| my == ry && mx == rx) {
                        continue;
                    }
                    let k = self.kernel[((dy + rc) as usize) * ksize + (dx + rc) as usize];
                    acc += k;
                }
            }
        }
        acc
    }

    // Interactions among the movers themselves, evaluated at points `pts`.
    fn pair_energy(&self, pts: &[(i32, i32)]) -> f64 {
        if pts.len() < 2 {
            return 0.0;
        }
        let h = self.params.h as i32;
        let w = self.params.w as i32;
        let mut acc = 0.0;
        for i in 0..pts.len() {
            for j in (i + 1)..pts.len() {
                let mut dy = pts[i].0 - pts[j].0;
                let mut dx = pts[i].1 - pts[j].1;
                if self.params.periodic {
                    dy = (dy + h / 2).rem_euclid(h) - h / 2;
                    dx = (dx + w / 2).rem_euclid(w) - w / 2;
                }
                let r = ((dy * dy + dx * dx) as f64).sqrt();
                acc += potential(r, &self.params, true);
            }
        }
        acc
    }

    // -- one Metropolis iteration ------------------------------------

    /// Advance the simulation by one batch-Metropolis proposal. Returns true
    /// if the proposal was accepted, false if it was rejected or if the batch
    /// produced no legal moves. The batch may shrink as a side effect (the
    /// desktop `fail_streak >= fail_limit` policy).
    pub fn step(&mut self) -> bool {
        let p = self.params.clone();
        if self.pos.is_empty() {
            self.iteration += 1;
            return false;
        }
        let n = self.pos.len();
        let m = self.live_batch.min(n).max(1);

        // Pick m distinct indices by partial Fisher-Yates.
        let mut idx: Vec<usize> = Vec::with_capacity(m);
        {
            let mut pool: Vec<usize> = (0..n).collect();
            for i in 0..m {
                let j = i + (self.rng.gen::<u64>() as usize) % (n - i);
                pool.swap(i, j);
                idx.push(pool[i]);
            }
        }
        let old: Vec<(i32, i32)> = idx.iter().map(|&i| self.pos[i]).collect();

        // Displacements uniform on [-step..=step]^2.
        let step = p.step_size;
        let span = (2 * step + 1) as u64;
        let mut new: Vec<(i32, i32)> = Vec::with_capacity(m);
        for _ in 0..m {
            let dy = (self.rng.gen::<u64>() % span) as i32 - step;
            let dx = (self.rng.gen::<u64>() % span) as i32 - step;
            new.push((dy, dx));
        }
        let mut new: Vec<(i32, i32)> = old.iter().zip(new.iter()).map(|((y, x), (dy, dx))| (y + dy, x + dx)).collect();

        // Filter zero moves.
        let mut keep: Vec<bool> = old
            .iter()
            .zip(new.iter())
            .map(|(o, n)| o.0 != n.0 || o.1 != n.1)
            .collect();

        // Bounds check.
        let h = p.h as i32;
        let w = p.w as i32;
        for i in 0..m {
            if !keep[i] {
                continue;
            }
            if p.periodic {
                new[i].0 = new[i].0.rem_euclid(h);
                new[i].1 = new[i].1.rem_euclid(w);
            } else if new[i].0 < 0 || new[i].0 >= h || new[i].1 < 0 || new[i].1 >= w {
                keep[i] = false;
            }
        }

        // Blocked check.
        for i in 0..m {
            if keep[i] && self.blocked[(new[i].0 as usize) * p.w + new[i].1 as usize] {
                keep[i] = false;
            }
        }

        // Deduplicate landing sites (first wins).
        {
            let mut seen: std::collections::HashSet<i64> = std::collections::HashSet::new();
            for i in 0..m {
                if keep[i] {
                    let key = (new[i].0 as i64) * (p.w as i64) + new[i].1 as i64;
                    if !seen.insert(key) {
                        keep[i] = false;
                    }
                }
            }
        }

        // Iterative "no mover lands on a staying particle" pass.
        // Dropping one mover un-vacates its old site, which may invalidate
        // another mover, so iterate to a fixed point.
        loop {
            let mut changed = false;
            // "staying" occupancy: every currently-occupied site whose particle
            // is not among the movers still alive.
            let mut moving_old: std::collections::HashSet<i64> = std::collections::HashSet::new();
            for i in 0..m {
                if keep[i] {
                    moving_old.insert((old[i].0 as i64) * (p.w as i64) + old[i].1 as i64);
                }
            }
            for i in 0..m {
                if !keep[i] {
                    continue;
                }
                let key = (new[i].0 as i64) * (p.w as i64) + new[i].1 as i64;
                let occupied = self.occ[(new[i].0 as usize) * p.w + new[i].1 as usize];
                if occupied && !moving_old.contains(&key) {
                    keep[i] = false;
                    changed = true;
                }
            }
            if !changed {
                break;
            }
        }

        // Compact the survivors.
        let mut sidx: Vec<usize> = Vec::new();
        let mut sold: Vec<(i32, i32)> = Vec::new();
        let mut snew: Vec<(i32, i32)> = Vec::new();
        for i in 0..m {
            if keep[i] {
                sidx.push(idx[i]);
                sold.push(old[i]);
                snew.push(new[i]);
            }
        }

        self.iteration += 1;
        if sidx.is_empty() {
            self.dead_proposals += 1;
            self.fail_streak += 1;
            if self.fail_streak as usize >= p.fail_limit {
                self.fail_streak = 0;
                self.live_batch = p.batch_min.max(self.live_batch.saturating_sub(p.batch_decrement));
            }
            return false;
        }
        self.fail_streak = 0;

        // Neighbourhood + mover-mover interactions. The gather uses the live
        // occupancy directly with the movers' old sites masked out, so no
        // whole-lattice scratch buffer is allocated per step.
        let mut e_old = 0.0;
        let mut e_new = 0.0;
        for i in 0..sold.len() {
            e_old += self.gather_bool(sold[i].0, sold[i].1, &sold);
            e_new += self.gather_bool(snew[i].0, snew[i].1, &sold);
        }
        e_old += self.pair_energy(&sold);
        e_new += self.pair_energy(&snew);

        let mut du_edge = 0.0;
        for i in 0..sold.len() {
            du_edge += self.u_edge[(snew[i].0 as usize) * p.w + snew[i].1 as usize]
                - self.u_edge[(sold[i].0 as usize) * p.w + sold[i].1 as usize];
        }
        let q = p.charge;
        let de = q * q * (e_new - e_old) + q * du_edge;

        self.proposed += 1;
        let kt = KB_EV * p.temperature.max(1e-9);
        let accept = de <= 0.0 || {
            let r: f64 = self.rng.gen();
            r < (-de / kt).exp()
        };
        if accept {
            for i in 0..sold.len() {
                self.occ[(sold[i].0 as usize) * p.w + sold[i].1 as usize] = false;
            }
            for i in 0..snew.len() {
                self.occ[(snew[i].0 as usize) * p.w + snew[i].1 as usize] = true;
                self.pos[sidx[i]] = snew[i];
            }
            self.energy += de;
            self.accepted += 1;
        }
        accept
    }

    /// Convenience: run `n` Metropolis iterations.
    pub fn step_many(&mut self, n: u64) {
        for _ in 0..n {
            self.step();
        }
    }

    /// Full Batch-Metropolis run at the current params for `iterations` steps.
    /// Same semantics as `step_many`; distinct entry point kept because the
    /// scenario-level API stays stable when step() splits later.
    pub fn run(&mut self, iterations: u64) {
        self.step_many(iterations);
    }

    // -- paint stroke -------------------------------------------------

    /// Lay a stroke segment onto the paint layer, incrementally updating
    /// `u_edge` inside the stroke's own bounded box grown by the coupling
    /// radius. Never triggers a whole-lattice FFT (except on the small-torus
    /// aliasing edge case, which the desktop code names and refuses too).
    ///
    /// `points[i] = [y, x]` in lattice cells. `first = true` at the start of a
    /// stroke opens a new undo record.
    pub fn paint_stroke(&mut self, points: &[[f64; 2]], br: &Brush, first: bool) -> PaintReport {
        let p = self.params.clone();
        let q = p.charge;
        let patch = match stroke_patch(points, br, first) {
            Some(pp) => pp,
            None => return PaintReport::default(),
        };

        if first || self.strokes.is_empty() {
            self.strokes.push(StrokeRecord {
                u_patches: Vec::new(),
                q_patches: Vec::new(),
                b_patches: Vec::new(),
            });
            if self.strokes.len() > STROKE_UNDO_CAP {
                self.strokes.remove(0);
            }
        }

        // 1. paint layer patch
        let place = match patch_index(patch.y0, patch.x0, patch.ph, patch.pw, p.h, p.w, p.periodic) {
            Some(pl) => pl,
            None => return PaintReport::default(),
        };
        let dq_sub = slice_patch(&patch.dq, patch.ph, patch.pw, &place);
        let cov_sub = slice_patch(&patch.cov, patch.ph, patch.pw, &place);
        // record + apply
        add_into(&mut self.paint, p.w, &place, &dq_sub);
        {
            let rec = self.strokes.last_mut().unwrap();
            rec.q_patches.push((place.rows.clone(), place.cols.clone(), dq_sub.clone()));
        }

        // 2. incremental u_edge patch. Convolve dq (small box) with the
        // coupling kernel; the "full" 2D convolution has size (ph+2rp) x
        // (pw+2rp), so the effective origin is (y0-rp, x0-rp).
        let (rp, kpaint, ks) = coupling_kernel(p.strength, p.screening, br.coupling, br.hardness);
        let (du, du_h, du_w) = convolve_full(&patch.dq, patch.ph, patch.pw, &kpaint, ks, ks);
        let mut de_field = 0.0;
        let uplace = patch_index(
            patch.y0 - rp as i64,
            patch.x0 - rp as i64,
            du_h,
            du_w,
            p.h,
            p.w,
            p.periodic,
        );
        if let Some(upl) = uplace {
            let du_sub = slice_patch(&du, du_h, du_w, &upl);
            for (ri, &row) in upl.rows.iter().enumerate() {
                for (ci, &col) in upl.cols.iter().enumerate() {
                    let uv = du_sub[ri * upl.cols.len() + ci];
                    self.u_edge[row * p.w + col] += uv;
                    if self.occ[row * p.w + col] {
                        de_field += q * uv;
                    }
                }
            }
            let rec = self.strokes.last_mut().unwrap();
            rec.u_patches.push((upl.rows, upl.cols, du_sub));
            self.energy += de_field;
        } else {
            // Small-torus aliasing: rebuild the whole field from scratch.
            self.rebuild_u_edge();
            let before = self.energy;
            self.energy = self.total_energy();
            de_field = self.energy - before;
        }

        // 3. penetrability: paint may also close sites.
        let mut blocked_new = 0usize;
        if br.penetrability < 1.0 {
            let mut newly = vec![false; place.rows.len() * place.cols.len()];
            for (ri, &row) in place.rows.iter().enumerate() {
                for (ci, &col) in place.cols.iter().enumerate() {
                    let idx = row * p.w + col;
                    let cv = cov_sub[ri * place.cols.len() + ci];
                    if cv > br.penetrability && !self.blocked[idx] {
                        newly[ri * place.cols.len() + ci] = true;
                        self.paint_blocked[idx] = true;
                        self.blocked[idx] = true;
                        blocked_new += 1;
                    }
                }
            }
            if blocked_new > 0 {
                let rec = self.strokes.last_mut().unwrap();
                rec.b_patches.push((place.rows.clone(), place.cols.clone(), newly));
            }
        }

        PaintReport {
            painted_cells: cov_sub.iter().filter(|&&v| v > 0.0).count(),
            charge: dq_sub.iter().sum(),
            blocked_new,
            de_field,
        }
    }

    /// Subtract the last stroke's charge and painted-block flags exactly.
    /// Any annealing that ran between paint and undo stays: the gas keeps
    /// whatever arrangement it found. Matches `undo_stroke` in the reference.
    pub fn undo_stroke(&mut self) -> bool {
        let rec = match self.strokes.pop() {
            Some(r) => r,
            None => return false,
        };
        let w = self.params.w;
        for (rows, cols, sub) in &rec.u_patches {
            for (ri, &row) in rows.iter().enumerate() {
                for (ci, &col) in cols.iter().enumerate() {
                    self.u_edge[row * w + col] -= sub[ri * cols.len() + ci];
                }
            }
        }
        for (rows, cols, sub) in &rec.q_patches {
            for (ri, &row) in rows.iter().enumerate() {
                for (ci, &col) in cols.iter().enumerate() {
                    self.paint[row * w + col] -= sub[ri * cols.len() + ci];
                }
            }
        }
        // Snap accumulated float noise, so a display that scales by max is not
        // lit up from 1e-16 residues after strokes cancel. Same threshold as
        // the reference.
        for v in self.paint.iter_mut() {
            if v.abs() < 1e-9 {
                *v = 0.0;
            }
        }
        for (rows, cols, mask) in &rec.b_patches {
            for (ri, &row) in rows.iter().enumerate() {
                for (ci, &col) in cols.iter().enumerate() {
                    if mask[ri * cols.len() + ci] {
                        self.paint_blocked[row * w + col] = false;
                    }
                }
            }
        }
        // Rebuild composite blocked mask; cheaper than tracking increments.
        for i in 0..self.blocked.len() {
            self.blocked[i] = self.line_blocked[i] || self.paint_blocked[i];
        }
        self.energy = self.total_energy();
        true
    }

    fn rebuild_u_edge(&mut self) {
        let mut edge = vec![0.0f64; self.cov.len()];
        for i in 0..edge.len() {
            edge[i] = self.cov[i] * self.line_density + self.paint[i];
        }
        self.u_edge = convolve(
            &edge,
            self.params.h,
            self.params.w,
            &self.kernel_edge,
            self.rc,
            self.params.periodic,
        );
    }

    // -- reporting ----------------------------------------------------

    pub fn stats(&self) -> Stats {
        Stats {
            iteration: self.iteration,
            proposed: self.proposed,
            accepted: self.accepted,
            dead_proposals: self.dead_proposals,
            fail_streak: self.fail_streak,
            batch: self.live_batch,
            particles: self.pos.len(),
            energy: self.energy,
            temperature: self.params.temperature,
        }
    }

    pub fn params(&self) -> &Params {
        &self.params
    }

    pub fn set_temperature(&mut self, t: f64) {
        self.params.temperature = t;
    }

    pub fn occupancy(&self) -> &[bool] {
        &self.occ
    }

    pub fn u_edge(&self) -> &[f64] {
        &self.u_edge
    }
}

#[derive(Debug, Clone, Default)]
pub struct PaintReport {
    pub painted_cells: usize,
    pub charge: f64,
    pub blocked_new: usize,
    pub de_field: f64,
}

// ---------------------------------------------------------------- helpers

fn slice_patch(src: &[f64], sh: usize, sw: usize, place: &Placement) -> Vec<f64> {
    let ph = place.sy_end - place.sy;
    let pw = place.sx_end - place.sx;
    let mut out = vec![0.0f64; ph * pw];
    for (di, sy) in (place.sy..place.sy_end).enumerate() {
        for (dj, sx) in (place.sx..place.sx_end).enumerate() {
            out[di * pw + dj] = src[sy * sw + sx];
        }
    }
    // Fold periodic wrap when rows / cols repeat: for periodic mode the
    // placement rows/cols are already wrapped, so no extra work here.
    let _ = (ph, sh, sw);
    out
}

fn add_into(target: &mut [f64], w: usize, place: &Placement, patch: &[f64]) {
    let pw = place.cols.len();
    for (ri, &row) in place.rows.iter().enumerate() {
        for (ci, &col) in place.cols.iter().enumerate() {
            target[row * w + col] += patch[ri * pw + ci];
        }
    }
}

// Suppress an unused-import warning for Complex64 across all cfg branches.
#[allow(dead_code)]
fn _touch_complex(_: Complex64) {}

// ---------------------------------------------------------------- tests

#[cfg(test)]
mod tests {
    use super::*;
    use approx::assert_relative_eq;

    fn small_params() -> Params {
        Params {
            h: 32,
            w: 32,
            seed: 1,
            strength: 1.0,
            screening: 0.0,
            cutoff: 6.0,
            periodic: false,
            charge: 1.0,
            attract_depth: 0.0,
            attract_range: 1.5,
            temperature: 50_000.0,
            batch: 1,
            batch_min: 1,
            batch_decrement: 1,
            fail_limit: 12,
            step_size: 2,
        }
    }

    #[test]
    fn conserves_particles() {
        let p = small_params();
        let mut sim = Sim::new_blank(p, 80);
        let n0 = sim.occupancy().iter().filter(|&&b| b).count();
        sim.step_many(2000);
        let n1 = sim.occupancy().iter().filter(|&&b| b).count();
        assert_eq!(n0, n1);
    }

    #[test]
    fn energy_delta_tracks_total_recompute() {
        let p = small_params();
        let mut sim = Sim::new_blank(p, 60);
        sim.step_many(500);
        let running = sim.stats().energy;
        let recomputed = sim.total_energy();
        // Numerical drift bound. On a 32x32 lattice at cutoff 6 with 60
        // particles this is comfortably below 1% and usually well under 0.01%,
        // so the tolerance is loose enough to be robust and tight enough to
        // catch a real accounting bug.
        assert_relative_eq!(running, recomputed, max_relative = 0.005);
    }

    #[test]
    fn u_edge_from_line_reproduces_direct_potential() {
        // Two isolated point charges; the convolved u_edge at a probe point
        // should match direct sum of contributions within kernel cutoff.
        let mut p = small_params();
        p.h = 16;
        p.w = 16;
        p.cutoff = 8.0;
        let (rc, kedge) = build_kernel(&p, false);
        let mut cov = vec![0.0f64; p.h * p.w];
        cov[3 * p.w + 4] = 1.0;
        cov[9 * p.w + 11] = 1.0;
        let u = convolve(&cov, p.h, p.w, &kedge, rc, false);
        // direct at probe (6, 7)
        let (py, px) = (6i32, 7i32);
        let mut direct = 0.0;
        for (cy, cx) in [(3i32, 4i32), (9, 11)] {
            let r = (((py - cy).pow(2) + (px - cx).pow(2)) as f64).sqrt();
            direct += potential(r, &p, false);
        }
        assert_relative_eq!(u[py as usize * p.w + px as usize], direct, max_relative = 1e-8);
    }

    #[test]
    fn undo_stroke_restores_field() {
        let mut p = small_params();
        p.h = 24;
        p.w = 24;
        p.cutoff = 4.0;
        let mut sim = Sim::new_blank(p, 20);
        let u_before: Vec<f64> = sim.u_edge().to_vec();
        let e_before = sim.total_energy();
        let br = Brush { thickness: 3.0, coupling: 4.0, ..Brush::default() };
        sim.paint_stroke(&[[10.0, 8.0], [12.0, 14.0]], &br, true);
        assert!(sim.strokes.len() == 1);
        sim.undo_stroke();
        let u_after: Vec<f64> = sim.u_edge().to_vec();
        for i in 0..u_before.len() {
            assert!((u_before[i] - u_after[i]).abs() < 1e-9, "u_edge mismatch at {}", i);
        }
        assert_relative_eq!(sim.total_energy(), e_before, max_relative = 1e-9);
    }
}
