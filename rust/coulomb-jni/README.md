# coulomb-jni

JNI bridge from `coulomb-core` (M1) to the Compose Android app (M3a).
Produces `libcoulomb_jni.so` for `aarch64-linux-android` and `armv7-linux-androideabi`, cross-compiled by `cargo xtask android-build`.

## What Kotlin sees

The Kotlin side is `object com.coulombpainter.CoulombNative`, which calls `System.loadLibrary("coulomb_jni")` in its `init` block.
Every JNI entry point in `src/lib.rs` is a hand-declared `pub extern "system" fn` whose symbol is `Java_com_coulombpainter_CoulombNative_<methodName>`, matching Android's JNIEnv name-lookup rules for a Kotlin `external fun`.
The mangling is hand-written (rather than declared through jni-rs's `#[jni]` macro) so the mapping is grep-able from either language and shows up in `nm libcoulomb_jni.so` in a form a reader can pair with the Kotlin file at a glance.

## Entry points

Grouped as in `docs/android-plan.md` §2.4:

- Lifecycle: `nativeSimCreate(h, w, seed, nParticles) -> jlong`, `nativeSimDestroy`, `nativeSimPause`, `nativeSimResume`.
- Frame: `nativeSimTick(handle, iters) -> jlong`, `nativeSimFrameTextureHandle(handle) -> jlong` (stub returns 0 until M3b wires SurfaceControl to a real wgpu texture).
- Paint: `nativeSimPaintBegin(handle, sign)`, `nativeSimPaintStrokePoint(handle, x, y, t)`, `nativeSimPaintEnd(handle)`, `nativeSimUndo(handle) -> jboolean`, `nativeSimClearPaint(handle)`.
- Params: `nativeSimSetParam(handle, key, value)`, `nativeSimGetParam(handle, key) -> jdouble`, `nativeSimLoadParamsJson(handle, json) -> jboolean`, `nativeSimDumpParamsJson(handle) -> jstring`.
- Presets and I/O: `nativeSimLoadPreset(handle, name) -> jboolean` (recognises `blank` in M3a), `nativeSimSnapshotPng(handle) -> jbyteArray` (stub, empty), `nativeSimSaveCmb(handle, path) -> jboolean` and `nativeSimLoadCmb(path) -> jlong` (both stubs; M5 wires the real `.cmb` format).
- Stats: `nativeSimStats(handle) -> jdouble[9]` (iteration, particles, temperature, energy, acceptance rate, moves/sec, occupancy fraction, energy drop, batch).

The stats array is flat rather than a `#[repr(C)]` struct on the Rust side.
A struct-shape mismatch across a Kotlin data class + Rust struct pair silently corrupts fields at every build unless the Kotlin side re-derives the layout; a `double[9]` is unpacked by index in the Kotlin `Stats.from(array)` constructor, so a Rust reorder shows up as a wrong field value the first time it is read, not a memory hazard.

## Handle discipline

`nativeSimCreate` returns a `Box::into_raw` pointer cast to `jlong`.
Every other entry point takes this `jlong` and short-circuits on 0.
`nativeSimDestroy` is the only entry point that reclaims memory; the Kotlin side calls it from `onDestroy` and never touches the handle afterward.
A double-destroy would UB across the FFI boundary in the standard Rust way, so `SimViewModel` on the Kotlin side owns the handle and never publishes it to a place that could outlive the model.

## Panic safety

FFI is `extern "C"`, which aborts on unwind; a panic inside `coulomb-core` would kill the whole process rather than surface as an exception in Kotlin.
Every entry point wraps its body in `panic::catch_unwind`, returning a "safe zero" default (0 handle, `JNI_FALSE`, empty array) on panic.
The Kotlin side reads these as "the sim is not ready yet", which is the same code path as during startup.
This is intentionally cheap; a real error reporting channel (Result-shaped stats, a logcat tag) belongs in M4 alongside the parameters drawer where a bad `sim_load_params_json` needs a visible failure state.

## Testing

`cargo test -p coulomb-jni` runs the host-side unit tests in `src/lib.rs` under `#[cfg(test)]`.
These exercise the Rust-side plumbing (handle round-trip, tick advances iteration count, stats-array shape, param apply, params JSON round-trip) without linking a JVM.

The full JNI symbol table is validated indirectly:

- The crate is `crate-type = ["cdylib", "rlib"]`, so a missing `#[no_mangle]` symbol fails the cdylib link at build time.
- `cargo xtask android-build` reports the produced `.so` sizes and exits non-zero on any failing target.
- The APK's `libcoulomb_jni.so` is inspected in the M3a PR body (`unzip -l app-debug.apk | grep libcoulomb_jni.so`) so the shipped library is confirmed present for both ABIs.

A JVM-in-process test using `jni = { features = ["invocation"] }` is deferred: it requires `LD_LIBRARY_PATH` pointing at the JVM's `libjvm.so` at test time, which is fragile across CI runners, and the host tests already cover what would be exercised through the JVM at this stage.
It lands with M3b, when the SurfaceControl bridge introduces a call that a static test can only meaningfully verify through the real JVM lifecycle.

## Build

The crate is a member of the `rust/` Cargo workspace.
`cargo build -p coulomb-jni` builds for the host; the Android cross-compile is:

```bash
# From rust/
cargo xtask android-build
```

which runs `cargo ndk -t arm64-v8a -t armeabi-v7a build --release -p coulomb-core -p coulomb-gpu -p coulomb-jni`.
The Android Gradle wrapper (`android/app/build.gradle.kts`) hooks the same command as a `preBuild` task dependency, so opening the project in Android Studio produces the `.so` files automatically.
