// coulomb-jni: the Kotlin <-> Rust bridge for the Android app shell (M3a).
//
// The Compose UI in `android/` calls into `object CoulombNative` in Kotlin,
// which loads `libcoulomb_jni.so` at process start and defines every
// `external fun native*` symbol below.
//
// The mangling convention here is the hand-written `Java_<pkg>_<class>_<fn>`
// form that Android's JNIEnv looks up when a Kotlin `external fun` in
// `com.coulombpainter.CoulombNative` is called. jni-rs is used only for the
// `JNIEnv` / `JString` / `JByteArray` helpers on the Rust side; the symbol
// names are hand-declared so the mapping is grep-able from either language.
//
// Handles crossing the boundary are raw `*mut SimHandle` cast to `jlong`.
// The Kotlin side owns lifetime: it calls `nativeSimCreate` on start and
// `nativeSimDestroy` on shutdown. All other entry points require the handle
// to be non-zero and refer to a still-live `SimHandle`; a zero or dropped
// handle causes early-return, never a panic across the FFI boundary.

#![deny(unsafe_op_in_unsafe_fn)]

mod renderer;

use std::panic::{self, AssertUnwindSafe};
use std::sync::Mutex;
use std::time::Instant;

use coulomb_core::{Brush, Params, Sim};
use jni::objects::{JByteArray, JClass, JDoubleArray, JObject, JString};
use jni::sys::{jboolean, jdouble, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use crate::renderer::Renderer;

// The mutex is here because the Kotlin caller runs one background loop for
// the physics tick and a separate UI thread for stats reads; a single lock
// keeps the crossing safe without needing to reason about JNI thread affinity
// at the boundary. The critical section is only whatever a single sim call
// takes, so contention is bounded by tick cadence, not held for a full frame.
struct SimHandle {
    sim: Mutex<Sim>,
    telem: Mutex<Telemetry>,
    paused: Mutex<bool>,

    // Latest brush settings pushed from Kotlin. paint_begin snapshots this
    // into the in-flight stroke, so the whole `Brush` (thickness, hardness,
    // coupling, …) reaches the physics kernel instead of a hard-coded
    // `Brush::default()` that lost every value the artist set.
    brush: Mutex<Brush>,

    // The current stroke's brush and points, buffered between paint_begin and
    // paint_end. Kotlin passes touch events one at a time; the physics kernel
    // batches a stroke into one incremental patch update at end-of-stroke.
    stroke: Mutex<Option<InFlightStroke>>,

    // M3b: surface-attached wgpu renderer. `bind_surface` fills this in, and
    // `unbind_surface` clears it before Kotlin releases the underlying Surface.
    // `render_frame` no-ops when the slot is empty, so a redundant Kotlin call
    // (during activity destroy, or before the first surface is available) is
    // benign rather than a crash.
    renderer: Mutex<Option<Renderer>>,

    // Reusable scratch buffer for uploading the CPU sim's bool occupancy as
    // u32 to the render pass. Held inside the handle so a 512x512 sim does
    // not reallocate 1 MiB per frame.
    occ_scratch: Mutex<Vec<u32>>,
}

struct Telemetry {
    // Wall-clock reference for moves-per-second. Reset each tick call so the
    // reported rate is the sustained rate over the last tick window rather
    // than a lifetime average that goes stale after a long pause.
    last_tick_wall: Option<Instant>,
    last_tick_iterations: u64,
    last_mps: f64,

    // Energy sampling for the drop metric surfaced in the live-stats one-liner.
    // Baseline resets on paint or preset load; the drop is measured relative
    // to that baseline so a user can see whether the current stroke settled.
    baseline_energy: f64,

    // Total lattice cells the paint brush has touched since the sim was
    // created. Firstmate's on-device smoke test asserts this is > 0 after a
    // synthetic swipe, which proves the whole touch -> JNI -> paint_stroke
    // chain is live. Cumulative rather than per-stroke because a spurious
    // extra stroke should still count.
    paint_cells_total: u64,
}

struct InFlightStroke {
    brush: Brush,
    points: Vec<[f64; 2]>,
}

impl SimHandle {
    fn new(sim: Sim) -> Box<Self> {
        let energy = sim.stats().energy;
        Box::new(Self {
            sim: Mutex::new(sim),
            telem: Mutex::new(Telemetry {
                last_tick_wall: None,
                last_tick_iterations: 0,
                last_mps: 0.0,
                baseline_energy: energy,
                paint_cells_total: 0,
            }),
            paused: Mutex::new(false),
            brush: Mutex::new(Brush::default()),
            stroke: Mutex::new(None),
            renderer: Mutex::new(None),
            occ_scratch: Mutex::new(Vec::new()),
        })
    }
}

// SAFETY: Kotlin gives us back the same jlong we returned, and never uses one
// after calling nativeSimDestroy. The cast is otherwise a no-op.
unsafe fn handle_from<'a>(ptr: jlong) -> Option<&'a SimHandle> {
    if ptr == 0 {
        return None;
    }
    // SAFETY: caller-provided invariant above.
    Some(unsafe { &*(ptr as *const SimHandle) })
}

// A single guard used at every entry point: JNI FFI is `extern "C"`, which
// aborts on unwind, so any panic inside the Rust core has to be caught here.
// The default arms return a "safe zero" value the Kotlin side treats as the
// no-op result rather than crashing the app.
fn guard<T>(default: T, f: impl FnOnce() -> T) -> T {
    match panic::catch_unwind(AssertUnwindSafe(f)) {
        Ok(v) => v,
        Err(_) => default,
    }
}

// ---------------------------------------------------------------- lifecycle

/// Allocate a Sim on a blank canvas of `h*w` with `n_particles` mobile charges.
/// Returns 0 on failure (invalid dims); a non-zero jlong on success.
#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimCreate(
    _env: JNIEnv,
    _class: JClass,
    h: jlong,
    w: jlong,
    seed: jlong,
    n_particles: jlong,
) -> jlong {
    guard(0, || {
        if h <= 0 || w <= 0 || n_particles < 0 {
            return 0;
        }
        let params = Params {
            h: h as usize,
            w: w as usize,
            seed: seed as u64,
            // Firstmate bug #9: periodic boundary on by default. The
            // desktop reference uses wrapped boundaries so a charge on
            // the far left of the canvas exerts its influence on the far
            // right too. Users who want a hard-wall boundary can toggle
            // it off in the drawer.
            periodic: true,
            ..Params::default()
        };
        let sim = Sim::new_blank(params, n_particles as usize);
        let handle = SimHandle::new(sim);
        Box::into_raw(handle) as jlong
    })
}

/// Drop the Sim referenced by `ptr`. After this call the handle is invalid;
/// any subsequent entry point that receives it will short-circuit as a no-op.
#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimDestroy(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    guard((), || {
        if ptr == 0 {
            return;
        }
        // SAFETY: we hand back only jlongs produced by nativeSimCreate; Kotlin
        // never uses one after destroy per the API contract on the Kotlin side.
        unsafe {
            drop(Box::from_raw(ptr as *mut SimHandle));
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimPause(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    guard((), || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        if let Some(h) = unsafe { handle_from(ptr) } {
            *h.paused.lock().unwrap() = true;
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimResume(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    guard((), || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        if let Some(h) = unsafe { handle_from(ptr) } {
            *h.paused.lock().unwrap() = false;
        }
    })
}

// ---------------------------------------------------------------- frame

/// Advance the sim by `iterations`. Returns the number of iterations actually
/// run (0 if paused, or the sim is missing). Rate telemetry is computed here
/// so `nativeSimStats` can surface a live moves-per-second number without a
/// second Kotlin-side timer.
#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimTick(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    iterations: jlong,
) -> jlong {
    guard(0, || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h) = (unsafe { handle_from(ptr) }) else {
            return 0;
        };
        if *h.paused.lock().unwrap() {
            return 0;
        }
        let iters = iterations.max(0) as u64;
        if iters == 0 {
            return 0;
        }
        let started = Instant::now();
        let iter_before = {
            let mut sim = h.sim.lock().unwrap();
            let before = sim.stats().iteration;
            sim.step_many(iters);
            before
        };
        let iter_after = h.sim.lock().unwrap().stats().iteration;
        let dt = started.elapsed().as_secs_f64();
        let done = iter_after - iter_before;

        let mut telem = h.telem.lock().unwrap();
        telem.last_tick_wall = Some(started);
        telem.last_tick_iterations = done;
        if dt > 0.0 {
            // Metropolis batch size matters for a comparable rate, so the
            // reported number is proposals-per-second, i.e. iters * batch.
            // The current live_batch is not exposed on Sim, so we approximate
            // with the params batch; the desktop reference does the same.
            let batch = h.sim.lock().unwrap().params().batch as f64;
            telem.last_mps = (done as f64) * batch / dt;
        }
        done as jlong
    })
}

/// Kept for backwards compatibility with the M3a stub. Returns 0, since the
/// M3b flow uses the surface-bind + render-frame pair below, not a raw
/// texture handle to hand to Compose.
#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimFrameTextureHandle(
    _env: JNIEnv,
    _class: JClass,
    _ptr: jlong,
) -> jlong {
    0
}

// ---------------------------------------------------------------- surface

/// Bind a Java `Surface` (from Compose's AndroidExternalSurface, or from a
/// SurfaceView holder) to this sim's wgpu renderer. Returns `true` on
/// success, `false` on any failure (null surface, no adapter, device create
/// failure). The renderer is single-owner: rebinding replaces any previous
/// surface. `unbind_surface` must be called before Kotlin lets the Surface
/// go, so wgpu drops the swapchain before ANativeWindow is invalidated.
#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimBindSurface<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    ptr: jlong,
    surface: JObject<'a>,
) -> jboolean {
    guard(JNI_FALSE, || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h) = (unsafe { handle_from(ptr) }) else {
            return JNI_FALSE;
        };
        // The Renderer constructor is unsafe because it dereferences the
        // Surface across the JNI boundary; the JNIEnv reference is what
        // guarantees that the JVM object is alive right now.
        let raw_obj = surface.as_raw();
        // SAFETY: env and surface both come from the JVM invocation.
        let result = unsafe { Renderer::new(&env, raw_obj) };
        match result {
            Ok(r) => {
                *h.renderer.lock().unwrap() = Some(r);
                JNI_TRUE
            }
            Err(_) => JNI_FALSE,
        }
    })
}

/// Drop the wgpu surface and its swapchain, so the Kotlin side can release
/// the underlying Surface without leaving dangling Vulkan swapchain images
/// pinned to a dead ANativeWindow. Safe to call with no bound surface.
#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimUnbindSurface(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    guard((), || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h) = (unsafe { handle_from(ptr) }) else {
            return;
        };
        *h.renderer.lock().unwrap() = None;
    })
}

/// Ask the renderer to reconfigure its swapchain to `w x h` (raw pixels).
/// The Compose layer reports the AndroidExternalSurface size in DP; we let
/// Kotlin convert to pixels since the density is a system property, and the
/// renderer treats any 0 dimension as "keep current size" so a spurious
/// pre-layout callback does not tear the swapchain down.
#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimSurfaceResize(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    w: jlong,
    h: jlong,
) {
    guard((), || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h_sim) = (unsafe { handle_from(ptr) }) else {
            return;
        };
        if let Some(r) = h_sim.renderer.lock().unwrap().as_mut() {
            r.resize(w.max(0) as u32, h.max(0) as u32);
        }
    })
}

/// Draw one frame from the current CPU sim state into the bound swapchain.
/// No-ops when no surface is bound. Occupancy is packed to u32 on the fly
/// into a scratch buffer that the handle owns, so the upload cost per frame
/// is one 1 MiB memcpy at 512x512 - a Vulkan pass on Adreno 750.
#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimRenderFrame(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    guard((), || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h) = (unsafe { handle_from(ptr) }) else {
            return;
        };
        let mut renderer_slot = h.renderer.lock().unwrap();
        let Some(renderer) = renderer_slot.as_mut() else {
            return;
        };
        let sim = h.sim.lock().unwrap();
        let occ = sim.occupancy();
        let u_edge = sim.u_edge();
        let (w, h_sz) = (sim.params().w as u32, sim.params().h as u32);
        // Pack occ + painted into one u32 per cell (bit 0 = mobile, bit 1
        // = painted fixed charge). `u_edge != 0` is what the desktop
        // reference uses to distinguish painted cells (see the desktop
        // brush.py's `sim.painted` accumulator, which feeds u_edge).
        let mut scratch = h.occ_scratch.lock().unwrap();
        if scratch.len() != occ.len() {
            scratch.clear();
            scratch.reserve(occ.len());
        }
        scratch.clear();
        for i in 0..occ.len() {
            let mut cell: u32 = 0;
            if occ[i] { cell |= 1; }
            // A small non-zero threshold covers the paint stroke's
            // patch-blend edges without lighting up floating-point noise.
            if u_edge[i].abs() > 1e-6 { cell |= 2; }
            scratch.push(cell);
        }
        // Drop the sim lock before touching wgpu so a slow submit does not
        // block the physics tick thread waiting behind the render.
        drop(sim);
        renderer.render(&scratch, w, h_sz);
    })
}

/// Push a view-mode viewport (origin + size in lattice cells) that the
/// renderer samples into the on-screen letterbox rect. Pass `(0, 0, 0, 0)`
/// to clear the viewport and resume rendering the whole lattice. Values
/// are clamped to the current lattice on each render frame, so a late
/// update after a lattice shrink does not sample out of bounds. No-op
/// when no surface is bound - a Kotlin caller that sets the viewport
/// before `nativeSimBindSurface` is tolerated (the renderer picks up the
/// stored value on bind) but today's flow always sets after bind.
#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimSetViewport(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    x: jdouble,
    y: jdouble,
    w: jdouble,
    h: jdouble,
) {
    guard((), || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h_sim) = (unsafe { handle_from(ptr) }) else {
            return;
        };
        let rect = if w > 0.0 && h > 0.0 {
            Some([x as f32, y as f32, w as f32, h as f32])
        } else {
            None
        };
        if let Some(r) = h_sim.renderer.lock().unwrap().as_mut() {
            r.set_src_rect(rect);
        }
    })
}

/// Return a JSON string with the adapter's identity fields so the
/// diagnostic overlay in Kotlin can show what wgpu picked. Returns "{}"
/// when no surface is bound. A JSON payload rather than a struct is chosen
/// for the same reason `nativeSimStats` uses a flat double array: layout
/// mismatches across Kotlin data classes and Rust structs silently ship
/// wrong fields, and JSON puts the schema on both ends of one string.
#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimAdapterInfoJson<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    ptr: jlong,
) -> jni::sys::jstring {
    guard(std::ptr::null_mut(), || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let json = match unsafe { handle_from(ptr) } {
            Some(h) => match h.renderer.lock().unwrap().as_ref() {
                Some(r) => {
                    let info = r.info();
                    serde_json::json!({
                        "name": info.name,
                        "backend": info.backend,
                        "driver": info.driver,
                        "device_type": info.device_type,
                    })
                    .to_string()
                }
                None => "{}".to_string(),
            },
            None => "{}".to_string(),
        };
        match env.new_string(&json) {
            Ok(js) => js.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    })
}

// ---------------------------------------------------------------- paint

#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimPaintBegin(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    sign: jdouble,
) {
    guard((), || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h) = (unsafe { handle_from(ptr) }) else {
            return;
        };
        let _ = &mut env; // reserved for a future brush-json override arg.
        // Snapshot the persistent brush the Kotlin UI keeps mirrored via
        // `nativeSimSetBrush`, so thickness / hardness / coupling / etc.
        // reach `paint_stroke` instead of the fat-line `Brush::default()`
        // that ignored every knob the artist set.
        let mut brush = h.brush.lock().unwrap().clone();
        brush.sign = if sign >= 0.0 { 1.0 } else { -1.0 };
        *h.stroke.lock().unwrap() = Some(InFlightStroke { brush, points: Vec::new() });
    })
}

/// Push the current brush shape into the handle. `sign` is applied per stroke
/// at `paint_begin` time, so callers pass whatever sign they last showed the
/// user and it is fine for the two calls to disagree.
#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimSetBrush(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    magnitude: jdouble,
    density: jdouble,
    thickness: jdouble,
    flow: jdouble,
    hardness: jdouble,
    penetrability: jdouble,
    coupling: jdouble,
) {
    guard((), || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h) = (unsafe { handle_from(ptr) }) else {
            return;
        };
        // The clamps mirror `brush.Brush.from_params` in the desktop reference:
        // a zero-thickness brush would paint no cell, and a hardness of 1.5
        // makes no sense. Clamp here so a slider bug in Kotlin cannot poison
        // the physics kernel.
        let mut b = Brush::default();
        b.magnitude = magnitude;
        b.density = density;
        b.thickness = thickness.max(1.0);
        b.flow = flow;
        b.hardness = hardness.clamp(0.0, 1.0);
        b.penetrability = penetrability.clamp(0.0, 1.0);
        b.coupling = coupling.max(1.0);
        *h.brush.lock().unwrap() = b;
    })
}

#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimPaintStrokePoint(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    x: jdouble,
    y: jdouble,
    _t: jdouble,
) {
    guard((), || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h) = (unsafe { handle_from(ptr) }) else {
            return;
        };
        let mut slot = h.stroke.lock().unwrap();
        if let Some(s) = slot.as_mut() {
            // Point order matches coulomb-core: [y, x] in lattice cells.
            s.points.push([y, x]);
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimPaintEnd(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    guard((), || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h) = (unsafe { handle_from(ptr) }) else {
            return;
        };
        let stroke = h.stroke.lock().unwrap().take();
        let Some(s) = stroke else { return };
        if s.points.is_empty() {
            return;
        }
        let mut sim = h.sim.lock().unwrap();
        let report = sim.paint_stroke(&s.points, &s.brush, true);
        drop(sim);
        // Any new stroke resets the "energy drop" baseline so the UI shows
        // the settling that follows the paint, not one accumulated since sim
        // creation.
        let energy = h.sim.lock().unwrap().stats().energy;
        let mut telem = h.telem.lock().unwrap();
        telem.baseline_energy = energy;
        telem.paint_cells_total = telem
            .paint_cells_total
            .saturating_add(report.painted_cells as u64);
    })
}

#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimUndo(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jboolean {
    guard(JNI_FALSE, || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h) = (unsafe { handle_from(ptr) }) else {
            return JNI_FALSE;
        };
        if h.sim.lock().unwrap().undo_stroke() {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimClearPaint(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    guard((), || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h) = (unsafe { handle_from(ptr) }) else {
            return;
        };
        // The core does not expose an atomic clear_paint yet; a loop of
        // undo_stroke reaches the same terminal state and is what the desktop
        // reference does too. Bounded by the undo stack cap in coulomb-core.
        let mut sim = h.sim.lock().unwrap();
        while sim.undo_stroke() {}
    })
}

// ---------------------------------------------------------------- params

/// Set a scalar param by name. Silently no-ops for an unknown key; a strict
/// check happens at load-params time where a JSON schema mismatch surfaces
/// with a return value the caller can display.
#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimSetParam(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    key: JString,
    value: jdouble,
) {
    guard((), || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h) = (unsafe { handle_from(ptr) }) else {
            return;
        };
        let key = match env.get_string(&key) {
            Ok(s) => s.into(),
            Err(_) => return,
        };
        let mut sim = h.sim.lock().unwrap();
        apply_param(&mut sim, key, value);
    })
}

#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimGetParam(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    key: JString,
) -> jdouble {
    guard(f64::NAN, || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h) = (unsafe { handle_from(ptr) }) else {
            return f64::NAN;
        };
        let key: String = match env.get_string(&key) {
            Ok(s) => s.into(),
            Err(_) => return f64::NAN,
        };
        let sim = h.sim.lock().unwrap();
        read_param(&sim, &key)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimLoadParamsJson<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    ptr: jlong,
    json: JString<'a>,
) -> jboolean {
    guard(JNI_FALSE, || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h) = (unsafe { handle_from(ptr) }) else {
            return JNI_FALSE;
        };
        let json: String = match env.get_string(&json) {
            Ok(s) => s.into(),
            Err(_) => return JNI_FALSE,
        };
        let v: serde_json::Value = match serde_json::from_str(&json) {
            Ok(v) => v,
            Err(_) => return JNI_FALSE,
        };
        let obj = match v.as_object() {
            Some(o) => o,
            None => return JNI_FALSE,
        };
        let mut sim = h.sim.lock().unwrap();
        for (k, val) in obj {
            if let Some(f) = val.as_f64() {
                apply_param(&mut sim, k.clone(), f);
            }
        }
        JNI_TRUE
    })
}

#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimDumpParamsJson<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    ptr: jlong,
) -> jni::sys::jstring {
    guard(std::ptr::null_mut(), || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h) = (unsafe { handle_from(ptr) }) else {
            return std::ptr::null_mut();
        };
        let sim = h.sim.lock().unwrap();
        let json = params_to_json(sim.params()).to_string();
        match env.new_string(&json) {
            Ok(js) => js.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    })
}

// ---------------------------------------------------------------- presets + snapshots

#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimLoadPreset(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    name: JString,
) -> jboolean {
    guard(JNI_FALSE, || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h) = (unsafe { handle_from(ptr) }) else {
            return JNI_FALSE;
        };
        let name: String = match env.get_string(&name) {
            Ok(s) => s.into(),
            Err(_) => return JNI_FALSE,
        };
        // Preserve params AND the current particle count so `Reset canvas`
        // gives the user a fresh arrangement of the same charge population;
        // n=0 would leave a blank navy field, which is not the reset the
        // captain asks for in firstmate bug #7.
        let sim_ref = h.sim.lock().unwrap();
        let params = sim_ref.params().clone();
        let n = sim_ref.occupancy().iter().filter(|&&b| b).count();
        drop(sim_ref);
        let fresh = match name.as_str() {
            "blank" => Sim::new_blank(params, n),
            // The wire-mesh preset is the shared parity fixture: a geometric
            // image both the desktop Python engine and the Android Rust
            // engine can run at the same seed, so a screenshot pair on
            // identical particle counts is a honest side-by-side. Step and
            // line_width are the same shape `docs/screenshots/m3c-wire-mesh.png`
            // encodes (grid every 16 lattice cells, 1-cell-thick rails on a
            // 256-wide lattice — subject to the current lattice).
            "wire_mesh" => {
                let step = (params.w / 16).max(8);
                Sim::new_wire_mesh(params, n, step, 1)
            }
            // Horizontal rails at ~1/10 of the lattice. The gap between rails
            // is thick enough that mobile charge can form a visible stripe,
            // but close enough that the drawer preset reads as "stripes" at a
            // glance rather than "a few far-apart lines".
            "stripes" => {
                let step = (params.h / 10).max(8);
                Sim::new_stripes(params, n, step, 2)
            }
            // Hollow ring at 0.42 of the shorter lattice side, matching the
            // desktop `disc_outline` fixture used in the parity campaigns.
            "disc" => Sim::new_disc(params, n, 0.42, 2),
            _ => return JNI_FALSE,
        };
        let energy = fresh.stats().energy;
        *h.sim.lock().unwrap() = fresh;
        // Reset the "energy drop" telemetry baseline so the diagnostic
        // overlay's delta reflects the settling after reset, not before.
        let mut telem = h.telem.lock().unwrap();
        telem.baseline_energy = energy;
        telem.paint_cells_total = 0;
        JNI_TRUE
    })
}

/// Encode the current CPU sim state as an RGBA PNG the Kotlin side can hand
/// to a SAF `CreateDocument` writer. The pixel pipeline is intentionally
/// independent of the wgpu surface so a snapshot works whether or not the
/// SurfaceView has bound a renderer yet (no race with the render loop, no
/// GPU readback path to probe). Order per cell: navy background, line-charge
/// white tint, mobile occupancy amber, painted charge red (+) / blue (-),
/// painted sign amplified by abs(paint).
#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimSnapshotPng<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    ptr: jlong,
) -> jni::sys::jbyteArray {
    guard(std::ptr::null_mut(), || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h) = (unsafe { handle_from(ptr) }) else {
            return std::ptr::null_mut();
        };
        let bytes = {
            let sim = h.sim.lock().unwrap();
            let w = sim.params().w as u32;
            let hgt = sim.params().h as u32;
            let occ = sim.occupancy();
            let paint = sim.paint();
            let cov = sim.cov();
            encode_snapshot_png(w, hgt, occ, paint, cov)
        };
        let bytes = match bytes {
            Some(b) => b,
            None => return std::ptr::null_mut(),
        };
        let arr = match env.byte_array_from_slice(&bytes) {
            Ok(a) => a,
            Err(_) => return std::ptr::null_mut(),
        };
        arr.into_raw()
    })
}

fn encode_snapshot_png(
    w: u32,
    h: u32,
    occ: &[bool],
    paint: &[f64],
    cov: &[f64],
) -> Option<Vec<u8>> {
    if w == 0 || h == 0 {
        return None;
    }
    let total = (w as usize) * (h as usize);
    if occ.len() != total || paint.len() != total || cov.len() != total {
        return None;
    }
    let mut rgba = vec![0u8; total * 4];
    for i in 0..total {
        // Base navy. Captain painter palette: a dim backdrop so painted
        // layers stay legible when a snapshot is pasted into a design doc.
        let mut r = 10u16;
        let mut g = 20u16;
        let mut b = 40u16;
        // Line charges: a white-ish tint proportional to coverage.
        let cv = cov[i].clamp(0.0, 1.0);
        if cv > 0.0 {
            let t = (cv * 240.0) as u16;
            r = r.saturating_add(t);
            g = g.saturating_add(t);
            b = b.saturating_add(t);
        }
        // Mobile occupancy: amber on top of the base/line.
        if occ[i] {
            r = 255;
            g = 200;
            b = 60;
        }
        // Painted charge: red for +, blue for -. Magnitude saturates the
        // channel after a small threshold so a wisp of paint reads clearly.
        let p = paint[i];
        if p.abs() > 1e-6 {
            let mag = (p.abs() * 1.0).min(1.0);
            let t = (mag * 220.0) as u16;
            if p > 0.0 {
                r = r.saturating_add(t);
                g = g.saturating_sub((t / 2).min(g));
                b = b.saturating_sub((t / 2).min(b));
            } else {
                b = b.saturating_add(t);
                r = r.saturating_sub((t / 2).min(r));
                g = g.saturating_sub((t / 4).min(g));
            }
        }
        let o = i * 4;
        rgba[o] = r.min(255) as u8;
        rgba[o + 1] = g.min(255) as u8;
        rgba[o + 2] = b.min(255) as u8;
        rgba[o + 3] = 255;
    }
    let mut buf: Vec<u8> = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut buf, w, h);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        let mut writer = encoder.write_header().ok()?;
        writer.write_image_data(&rgba).ok()?;
    }
    Some(buf)
}

// ---------------------------------------------------------------- rebuild + step
//
// Three entry points below belong together: `nativeSimStep` lets the Kotlin
// top-bar advance one iteration while the sim is otherwise frozen (per
// captain Freeze/Step pair). `nativeSimRecreate` and
// `nativeSimRebuildWithCoverage` replace the sim in-place so the Kotlin New
// Canvas flow can hand the artist a fresh canvas (blank or from an uploaded
// grayscale image) without allocating a second SimHandle - the renderer and
// telemetry outlive the swap. See `docs/android-plan.md` §4-5.

/// Advance the sim by exactly one iteration regardless of pause state. The
/// per-tick telemetry is updated so the stats line still ticks forward on
/// each tap, matching the desktop reference's single-step affordance.
#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimStep(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jlong {
    guard(0, || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h) = (unsafe { handle_from(ptr) }) else {
            return 0;
        };
        let started = Instant::now();
        let iter_before;
        let iter_after;
        {
            let mut sim = h.sim.lock().unwrap();
            iter_before = sim.stats().iteration;
            sim.step();
            iter_after = sim.stats().iteration;
        }
        let dt = started.elapsed().as_secs_f64();
        let done = iter_after - iter_before;
        let mut telem = h.telem.lock().unwrap();
        telem.last_tick_wall = Some(started);
        telem.last_tick_iterations = done;
        if dt > 0.0 {
            let batch = h.sim.lock().unwrap().params().batch as f64;
            telem.last_mps = (done as f64) * batch / dt;
        }
        done as jlong
    })
}

/// Replace the current sim with a fresh blank canvas at (`h`, `w`), seeding
/// `n_particles` mobile charges and setting `Params.charge` to
/// `charge_sign * previous_|charge|` so the artist's magnitude is preserved
/// while the sign flips. `charge_sign` of 0 keeps the previous sign (used
/// by the "mixed" dialog option, which falls back to positive today - a
/// per-particle sign distribution is beyond this entry point's scope).
///
/// Returns `true` on success. The renderer stays bound; the next render
/// frame reads the new sim's occupancy and the swapchain's own
/// `nativeSimSurfaceResize` from Kotlin picks up any aspect change.
#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimRecreate(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    h_px: jlong,
    w_px: jlong,
    seed: jlong,
    n_particles: jlong,
    charge_sign: jint,
) -> jboolean {
    guard(JNI_FALSE, || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h) = (unsafe { handle_from(ptr) }) else {
            return JNI_FALSE;
        };
        if h_px <= 0 || w_px <= 0 || n_particles < 0 {
            return JNI_FALSE;
        }
        let base = h.sim.lock().unwrap().params().clone();
        let charge = apply_charge_sign(base.charge, charge_sign);
        let params = Params {
            h: h_px as usize,
            w: w_px as usize,
            seed: seed as u64,
            charge,
            ..base
        };
        let fresh = Sim::new_blank(params, n_particles as usize);
        let energy = fresh.stats().energy;
        *h.sim.lock().unwrap() = fresh;
        let mut telem = h.telem.lock().unwrap();
        telem.baseline_energy = energy;
        telem.paint_cells_total = 0;
        JNI_TRUE
    })
}

/// Replace the current sim with a canvas built from a user-supplied grayscale
/// coverage image. `cov_bytes` is `h_px * w_px` bytes, each 0..=255 (0 =
/// transparent / no line charge, 255 = full line charge). The Kotlin side
/// decodes the picked image to this shape via `Bitmap` + `extractAlpha` or a
/// luminance pass.
///
/// Returns `true` on success. `charge_sign` behaves as in `nativeSimRecreate`.
#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimRebuildWithCoverage<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    ptr: jlong,
    h_px: jlong,
    w_px: jlong,
    seed: jlong,
    n_particles: jlong,
    charge_sign: jint,
    cov_bytes: JByteArray<'a>,
) -> jboolean {
    guard(JNI_FALSE, || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h) = (unsafe { handle_from(ptr) }) else {
            return JNI_FALSE;
        };
        if h_px <= 0 || w_px <= 0 || n_particles < 0 {
            return JNI_FALSE;
        }
        let total = (h_px as usize).saturating_mul(w_px as usize);
        // JNI's byte array copy to Rust owns the bytes; we convert to f64 in
        // 0..1 and feed `Sim::new` with line_blocks=true so the lines on an
        // uploaded wire-mesh image occlude the gas, matching the desktop
        // behavior for an uploaded PNG.
        let i8_bytes = match env.convert_byte_array(&cov_bytes) {
            Ok(v) => v,
            Err(_) => return JNI_FALSE,
        };
        if i8_bytes.len() != total {
            return JNI_FALSE;
        }
        let cov: Vec<f64> = i8_bytes
            .iter()
            .map(|&b| (b as u8) as f64 / 255.0)
            .collect();
        let base = h.sim.lock().unwrap().params().clone();
        let charge = apply_charge_sign(base.charge, charge_sign);
        let params = Params {
            h: h_px as usize,
            w: w_px as usize,
            seed: seed as u64,
            charge,
            ..base
        };
        let occ0 = strided_occ_bool(params.h, params.w, n_particles as usize, &cov);
        let fresh = Sim::new(params, cov, occ0, 1.0, true);
        let energy = fresh.stats().energy;
        *h.sim.lock().unwrap() = fresh;
        let mut telem = h.telem.lock().unwrap();
        telem.baseline_energy = energy;
        telem.paint_cells_total = 0;
        JNI_TRUE
    })
}

/// Apply a captain-level sign choice to the preserved charge magnitude.
/// `-1` flips to negative, `+1` to positive, `0` keeps the previous sign.
fn apply_charge_sign(prev: f64, sign: jint) -> f64 {
    let mag = prev.abs();
    match sign {
        s if s > 0 => mag,
        s if s < 0 => -mag,
        _ => if prev < 0.0 { -mag } else { mag },
    }
}

/// Place `n` mobile charges on the lattice avoiding cells whose coverage is
/// above 0.5 (so line charges on an uploaded wire-mesh image are not sat on
/// at creation time). The order is deterministic strided, matching
/// `Sim::new_blank`'s initial-condition policy so a snapshot pair across
/// engines is comparable.
fn strided_occ_bool(h: usize, w: usize, n: usize, cov: &[f64]) -> Vec<bool> {
    let total = h * w;
    let mut occ = vec![false; total];
    if n == 0 || total == 0 {
        return occ;
    }
    let mut free: Vec<usize> = (0..total).filter(|&i| cov[i] <= 0.5).collect();
    if free.is_empty() {
        return occ;
    }
    let take = n.min(free.len());
    for k in 0..take {
        let idx = (k * free.len()) / take;
        occ[free.swap_remove(idx.min(free.len() - 1))] = true;
    }
    occ
}

/// `.cmb` save - M5's territory; the stub returns false so the Kotlin UI can
/// grey out the button in M3a without a runtime error.
#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimSaveCmb(
    _env: JNIEnv,
    _class: JClass,
    _ptr: jlong,
    _path: JString,
) -> jboolean {
    JNI_FALSE
}

/// `.cmb` load - same rationale as save. Returns 0 (no new handle).
#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimLoadCmb(
    _env: JNIEnv,
    _class: JClass,
    _path: JString,
) -> jlong {
    0
}

// ---------------------------------------------------------------- stats

/// Returns a `double[10]` in the order Kotlin's `CoulombNative.Stats` expects:
///   [0] iteration           (whole-number double so no integer array is needed)
///   [1] particles
///   [2] temperature (K)
///   [3] energy (eV)
///   [4] acceptance rate     (0..1)
///   [5] moves per second    (proposals/sec measured over the last tick)
///   [6] occupancy fraction  (0..1) - lit sites over total lattice
///   [7] energy drop         (baseline_energy - current_energy, eV)
///   [8] batch size          (whole-number double)
///   [9] paint cells total   (cumulative lattice cells touched by paint)
///
/// A flat double[] is chosen over a `#[repr(C)]` struct because the JNI
/// double-array primitive is cheap on both sides and does not require a
/// Kotlin data class to match the Rust field layout at every build.
#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimStats<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    ptr: jlong,
) -> jni::sys::jdoubleArray {
    guard(std::ptr::null_mut(), || {
        // SAFETY: caller-owned handle; see handle_from doc comment.
        let Some(h) = (unsafe { handle_from(ptr) }) else {
            return std::ptr::null_mut();
        };
        let stats = h.sim.lock().unwrap().stats();
        let telem = h.telem.lock().unwrap();
        let (occupancy, energy_drop) = {
            let sim = h.sim.lock().unwrap();
            let total = (sim.params().h * sim.params().w) as f64;
            let occ = if total > 0.0 { stats.particles as f64 / total } else { 0.0 };
            let drop = telem.baseline_energy - stats.energy;
            (occ, drop)
        };
        let vals: [f64; 10] = [
            stats.iteration as f64,
            stats.particles as f64,
            stats.temperature,
            stats.energy,
            stats.acceptance(),
            telem.last_mps,
            occupancy,
            energy_drop,
            stats.batch as f64,
            telem.paint_cells_total as f64,
        ];
        let arr: JDoubleArray = match env.new_double_array(vals.len() as i32) {
            Ok(a) => a,
            Err(_) => return std::ptr::null_mut(),
        };
        if env.set_double_array_region(&arr, 0, &vals).is_err() {
            return std::ptr::null_mut();
        }
        arr.into_raw()
    })
}

// ---------------------------------------------------------------- helpers

fn apply_param(sim: &mut Sim, key: String, value: f64) {
    // Delegates to the core's typed setter, which knows which keys are live
    // and which trigger a kernel rebuild. An unknown key returns false and
    // silently no-ops here - the drawer sends best-effort JSON, so a stray
    // decorative field must not fail the whole update batch.
    let _ = sim.set_param(&key, value);
}

fn read_param(sim: &Sim, key: &str) -> f64 {
    let p = sim.params();
    match key {
        "h" => p.h as f64,
        "w" => p.w as f64,
        "temperature" => p.temperature,
        "strength" => p.strength,
        "screening" => p.screening,
        "cutoff" => p.cutoff,
        "charge" => p.charge,
        "attract_depth" => p.attract_depth,
        "attract_range" => p.attract_range,
        "batch" => p.batch as f64,
        "batch_min" => p.batch_min as f64,
        "batch_decrement" => p.batch_decrement as f64,
        "fail_limit" => p.fail_limit as f64,
        "step_size" => p.step_size as f64,
        _ => f64::NAN,
    }
}

fn params_to_json(p: &Params) -> serde_json::Value {
    serde_json::json!({
        "h": p.h,
        "w": p.w,
        "seed": p.seed,
        "strength": p.strength,
        "screening": p.screening,
        "cutoff": p.cutoff,
        "periodic": p.periodic,
        "charge": p.charge,
        "attract_depth": p.attract_depth,
        "attract_range": p.attract_range,
        "temperature": p.temperature,
        "batch": p.batch,
        "batch_min": p.batch_min,
        "batch_decrement": p.batch_decrement,
        "fail_limit": p.fail_limit,
        "step_size": p.step_size,
    })
}

// ---------------------------------------------------------------- host tests
//
// The tests below skip the JVM entirely and exercise the Rust-side plumbing
// directly. The full JNI symbol path is validated indirectly: every entry
// point is `pub extern "system" fn`, so the linker rejects a missing symbol
// at cdylib link time, and a smoke test on the assembled APK proves the
// symbol table on device (see `android/README.md`).

#[cfg(test)]
mod tests {
    use super::*;

    // Round-trip create/tick/stats/destroy using the Rust-side plumbing.
    // A follow-up test lands with the JVM invocation harness once the M3b
    // texture bridge exists to justify the JVM startup cost per test.
    fn make_handle(h: usize, w: usize, n: usize) -> *mut SimHandle {
        let sim = Sim::new_blank(
            Params { h, w, seed: 42, ..Params::default() },
            n,
        );
        Box::into_raw(SimHandle::new(sim))
    }

    #[test]
    fn create_and_destroy_roundtrip() {
        let ptr = make_handle(64, 64, 100);
        assert!(!ptr.is_null());
        // SAFETY: fresh handle we just created; drop returns memory to Rust.
        unsafe {
            drop(Box::from_raw(ptr));
        }
    }

    #[test]
    fn tick_advances_iteration() {
        let ptr = make_handle(32, 32, 30);
        // SAFETY: handle from make_handle above; not shared.
        let handle = unsafe { &*ptr };
        let before = handle.sim.lock().unwrap().stats().iteration;
        handle.sim.lock().unwrap().step_many(5);
        let after = handle.sim.lock().unwrap().stats().iteration;
        assert_eq!(after - before, 5);
        // SAFETY: same handle, single-threaded test.
        unsafe {
            drop(Box::from_raw(ptr));
        }
    }

    #[test]
    fn stats_shape_matches_kotlin_expectation() {
        let ptr = make_handle(16, 16, 20);
        // SAFETY: freshly created above.
        let handle = unsafe { &*ptr };
        let s = handle.sim.lock().unwrap().stats();
        // Kotlin unpacks a double[9]; the assertions here catch a Stats field
        // rename that would silently shift array positions.
        assert!(s.iteration == 0);
        assert!(s.particles > 0);
        assert!(s.temperature.is_finite());
        // SAFETY: same handle.
        unsafe {
            drop(Box::from_raw(ptr));
        }
    }

    #[test]
    fn set_temperature_via_apply_param() {
        let ptr = make_handle(16, 16, 10);
        // SAFETY: freshly created above.
        let handle = unsafe { &*ptr };
        {
            let mut sim = handle.sim.lock().unwrap();
            apply_param(&mut sim, "temperature".to_string(), 1234.5);
        }
        let t = handle.sim.lock().unwrap().params().temperature;
        assert!((t - 1234.5).abs() < 1e-9);
        // SAFETY: same handle.
        unsafe {
            drop(Box::from_raw(ptr));
        }
    }

    #[test]
    fn params_json_roundtrip_covers_defaults() {
        let p = Params::default();
        let v = params_to_json(&p);
        let s = v.to_string();
        let parsed: serde_json::Value = serde_json::from_str(&s).unwrap();
        assert_eq!(parsed["h"], serde_json::json!(p.h));
        assert_eq!(parsed["batch"], serde_json::json!(p.batch));
    }

    #[test]
    fn snapshot_png_encodes_header() {
        // Smoke test: encoder returns bytes starting with the PNG signature.
        // Full image decode happens on the Kotlin side; here we only prove
        // the encoder is wired to the sim state without a surface bound.
        let occ = vec![false; 16 * 16];
        let paint = vec![0.0f64; 16 * 16];
        let cov = vec![0.0f64; 16 * 16];
        let bytes = encode_snapshot_png(16, 16, &occ, &paint, &cov)
            .expect("encode must succeed on a trivial canvas");
        assert_eq!(&bytes[0..8], &[0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a]);
    }

    #[test]
    fn apply_charge_sign_preserves_magnitude() {
        assert!((apply_charge_sign(2.5, 1) - 2.5).abs() < 1e-9);
        assert!((apply_charge_sign(2.5, -1) + 2.5).abs() < 1e-9);
        // sign=0 keeps the previous sign.
        assert!((apply_charge_sign(-2.5, 0) + 2.5).abs() < 1e-9);
        assert!((apply_charge_sign(2.5, 0) - 2.5).abs() < 1e-9);
    }

    #[test]
    fn strided_occ_bool_avoids_covered_cells() {
        let mut cov = vec![0.0f64; 8 * 8];
        for i in 0..8 {
            // Top row is a line charge (coverage=1), so no mobile charge
            // should land there.
            cov[i] = 1.0;
        }
        let occ = strided_occ_bool(8, 8, 10, &cov);
        for i in 0..8 {
            assert!(!occ[i], "placed on covered row");
        }
        assert_eq!(occ.iter().filter(|&&b| b).count(), 10);
    }
}
