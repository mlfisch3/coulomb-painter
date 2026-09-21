# Coulomb Painter Android app (M3a)

The Compose UI shell for the Rust + wgpu physics core.
M3a lands the app shell and JNI bridge without the on-device texture display; M3b wires the real render path when the reference S24 is available.

## Prerequisites

- JDK 17 (Temurin or OpenJDK).
- Android SDK Platform 35 and Build-Tools 35.0.0.
- Android NDK r27 or newer.
- Rust 1.95 with the `aarch64-linux-android` and `armv7-linux-androideabi` targets (`rustup target add ...`).
- `cargo-ndk` on `PATH` (`cargo install cargo-ndk`).

Point Android Studio (or the CLI build) at the SDK and NDK either via a `local.properties` file in `android/`:

```
sdk.dir=/path/to/Android/Sdk
ndk.dir=/path/to/Android/Sdk/ndk/<version>
```

or via the environment variables `ANDROID_SDK_ROOT`, `ANDROID_HOME`, and `ANDROID_NDK_HOME` (or `ANDROID_NDK_ROOT`).
`local.properties` is gitignored so each checkout carries its own paths.

## Build

From `android/`:

```bash
./gradlew :app:assembleDebug
```

The debug APK lands at `android/app/build/outputs/apk/debug/app-debug.apk`.
Inspect it with:

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libcoulomb_jni.so
```

which lists the shared library for both ABIs the app supports.

## How the Rust cross-compile plugs into Gradle

`android/app/build.gradle.kts` registers a `rustAndroidBuild` task that runs

```
cargo run -q -p xtask -- android-build
```

against the `rust/` workspace one level up.
That xtask runs the naga WGSL validator (M2) plus `cargo ndk` for both Android targets, producing `libcoulomb_jni.so`, `libcoulomb_gpu.so`, and `libcoulomb_core.so` under `rust/target/<triple>/release/`.
Only `libcoulomb_jni.so` is copied into the APK; the other two are statically linked into it as rlibs and land in `libcoulomb_jni.so` by the time it is written.
The copy task `copyRustJniLibs` stages the shared library under `build/intermediates/rustJniLibs/{arm64-v8a,armeabi-v7a}/`, which is then added to the `main` source set's `jniLibs` search path.
`preBuild` depends on `copyRustJniLibs`, so a plain Android Studio build (Sync + Run) produces the JNI library without a separate shell step.

## Project layout

```
android/
  settings.gradle.kts      root project + module list
  build.gradle.kts         plugin versions (AGP, Kotlin, Compose)
  gradle.properties        Gradle JVM args, AndroidX flags
  gradle/wrapper/          Gradle 8.9 wrapper
  gradlew, gradlew.bat     wrapper launchers
  app/
    build.gradle.kts       module config + cargo-ndk hook
    proguard-rules.pro     keeps CoulombNative from R8 rename
    src/main/
      AndroidManifest.xml
      java/com/coulombpainter/
        CoulombNative.kt         JNI wrapper object
        SimViewModel.kt          lifecycle + tick loop + stats poll
        MainActivity.kt          ComponentActivity + setContent
        ui/                      Compose layout
          CoulombPainterApp.kt   top-level scaffold
          StubCanvas.kt          placeholder canvas
          ParametersDrawer.kt    accordion drawer
          BrushDetailsSheet.kt   bottom sheet
          NewCanvasDialog.kt     resolution + fill dialog
          HelpTooltip.kt         `?` popover with one line per topic
          theme/Theme.kt         Material 3 palette (mockup tokens)
      res/                     icons, colors, themes, XML
```

## What is stubbed in M3a

- The canvas is a slow-pulsing radial gradient (`StubCanvas`), not a real physics texture.
- Paint / heat gestures are no-ops.
  A two-finger tap (via `detectTapGestures.onDoubleTap`) cycles the mode selector so the interaction wiring itself is proven end-to-end.
- `nativeSimFrameTextureHandle` returns 0 in Rust; the Compose canvas ignores it.
- `.cmb` save/load, PNG snapshot, and the preset library beyond `blank` are stubs; the JNI surface is present so M4 and M5 do not need a wire-up pass.

## Verification checklist

- `./gradlew :app:assembleDebug` produces `app/build/outputs/apk/debug/app-debug.apk`.
- `unzip -l app-debug.apk | grep libcoulomb_jni.so` shows both `lib/arm64-v8a/libcoulomb_jni.so` and `lib/armeabi-v7a/libcoulomb_jni.so`.
- `cargo test -p coulomb-jni` passes on the host (from `rust/`).
- `nm -D lib/arm64-v8a/libcoulomb_jni.so | grep Java_com_coulombpainter_CoulombNative_` lists the 20 JNI symbols.

## Deferred to M3b (needs the S24)

- SurfaceControl + wgpu texture display of the real physics frame.
- Touch to paint pipeline (drag -> `nativeSimPaintStrokePoint` batch -> `nativeSimPaintEnd`).
- `adb install` and real-device UI verification.
- Thermal telemetry via `PowerManager.getThermalHeadroom`, battery-aware fps cap (M5).
- `.cmb` save/load (M5), snapshot PNG encoder (M4).
- Sideload update flow to GitHub Releases (M6).
