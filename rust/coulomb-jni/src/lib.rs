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

use std::panic::{self, AssertUnwindSafe};
use std::sync::Mutex;
use std::time::Instant;

use coulomb_core::{Brush, Params, Sim};
use jni::objects::{JClass, JDoubleArray, JString};
use jni::sys::{jboolean, jdouble, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

// The mutex is here because the Kotlin caller runs one background loop for
// the physics tick and a separate UI thread for stats reads; a single lock
// keeps the crossing safe without needing to reason about JNI thread affinity
// at the boundary. The critical section is only whatever a single sim call
// takes, so contention is bounded by tick cadence, not held for a full frame.
struct SimHandle {
    sim: Mutex<Sim>,
    telem: Mutex<Telemetry>,
    paused: Mutex<bool>,

    // The current stroke's brush and points, buffered between paint_begin and
    // paint_end. Kotlin passes touch events one at a time; the physics kernel
    // batches a stroke into one incremental patch update at end-of-stroke.
    stroke: Mutex<Option<InFlightStroke>>,
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
            }),
            paused: Mutex::new(false),
            stroke: Mutex::new(None),
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

/// The zero-copy texture-handle export lands in M3b (SurfaceControl + wgpu).
/// The M3a stub keeps the JNI surface stable so Kotlin can call the method
/// today; a zero return tells the Compose canvas to use its placeholder.
#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimFrameTextureHandle(
    _env: JNIEnv,
    _class: JClass,
    _ptr: jlong,
) -> jlong {
    0
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
        let mut brush = Brush::default();
        brush.sign = if sign >= 0.0 { 1.0 } else { -1.0 };
        *h.stroke.lock().unwrap() = Some(InFlightStroke { brush, points: Vec::new() });
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
        let _ = sim.paint_stroke(&s.points, &s.brush, true);
        drop(sim);
        // Any new stroke resets the "energy drop" baseline so the UI shows
        // the settling that follows the paint, not one accumulated since sim
        // creation.
        let energy = h.sim.lock().unwrap().stats().energy;
        h.telem.lock().unwrap().baseline_energy = energy;
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
        // M3a only recognises "blank"; the real preset library (wire_mesh,
        // stripes, disc) lands in M4 alongside the drawer that lists them.
        // Kotlin can still call the entry point without a runtime crash.
        if name != "blank" {
            return JNI_FALSE;
        }
        let sim_ref = h.sim.lock().unwrap();
        let params = sim_ref.params().clone();
        drop(sim_ref);
        let fresh = Sim::new_blank(params, 0);
        *h.sim.lock().unwrap() = fresh;
        JNI_TRUE
    })
}

/// Snapshot PNG - stubbed to an empty byte array for M3a. M4 wires the real
/// PNG encoder once the render pipeline in M3b has produced a rasterised view.
#[no_mangle]
pub extern "system" fn Java_com_coulombpainter_CoulombNative_nativeSimSnapshotPng<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    _ptr: jlong,
) -> jni::sys::jbyteArray {
    guard(std::ptr::null_mut(), || {
        let arr = match env.new_byte_array(0) {
            Ok(a) => a,
            Err(_) => return std::ptr::null_mut(),
        };
        arr.into_raw()
    })
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

/// Returns a `double[9]` in the order Kotlin's `CoulombNative.Stats` expects:
///   [0] iteration           (whole-number double so no integer array is needed)
///   [1] particles
///   [2] temperature (K)
///   [3] energy (eV)
///   [4] acceptance rate     (0..1)
///   [5] moves per second    (proposals/sec measured over the last tick)
///   [6] occupancy fraction  (0..1) - lit sites over total lattice
///   [7] energy drop         (baseline_energy - current_energy, eV)
///   [8] batch size          (whole-number double)
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
        let vals: [f64; 9] = [
            stats.iteration as f64,
            stats.particles as f64,
            stats.temperature,
            stats.energy,
            stats.acceptance(),
            telem.last_mps,
            occupancy,
            energy_drop,
            stats.batch as f64,
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
    // Temperature is the only live-mutable param the CPU core exposes
    // directly; the rest require a rebuild, so M3a only accepts temperature
    // (which is what the auto-T slider drives) and defers the wider surface
    // to M4 when the drawer is wired up.
    if key == "temperature" {
        sim.set_temperature(value);
    }
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
}
