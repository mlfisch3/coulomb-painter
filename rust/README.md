# coulomb-painter/rust

Rust workspace for the Coulomb Painter physics kernel.

M2 milestone (WGSL GPU port) sits on top of the M1 CPU reference.
The CPU crate is deliberately single-threaded and correctness-first; the GPU crate ports the same Hamiltonian to wgpu so the desktop-validated kernel is the one M3 attaches to Android.

## Crates

- `coulomb-core`: the CPU physics library.
  Batch-Metropolis on a mobile-charge occupancy grid, `exp(-r/L)/r` screening, short-range attractive well between mobile charges, `u_edge` FFT construction from line charges via `rustfft`, and the incremental brush-patch update (a stroke touches only its own bounded box grown by the coupling radius, not a whole-lattice FFT).
- `coulomb-gpu`: wgpu / WGSL port.
  Tiled 2x2 coloured Metropolis, one workgroup per tile, workgroup-shared broadcast and reduction in place of CUDA subgroup shuffles.
  See `coulomb-gpu/README.md` for the kernel layout and adapter-selection env overrides.
- `coulomb-bench`: local benchmark binary that runs `coulomb-gpu` on a scenario and prints MPS, render FPS, adapter identity, peak GPU buffer bytes, and a machine-readable JSON summary.
- `coulomb-validate`: runs a JSON scenario through `coulomb-core`, `coulomb-gpu` (when the scenario opts in with `gpu_chains > 0`), and a Python subprocess that imports `projects/coulomb-brush/coulomb.py` and `brush.py`.
  Compares acceptance rate, end-of-run mean energy, and particle count pairwise across all engines and exit-codes non-zero on any tolerance violation.

## Toolchain

`rust-toolchain.toml` pins Rust 1.95.0 with the rustfmt and clippy components.
No nightly features.

## How to run

The Python subprocess reads `coulomb.py` and `brush.py` from the fleet-managed reference at `/home/drd/PROJECT/CLAUDE/FM-COULOMB/fm-coulomb/projects/coulomb-brush`.
Override the path with the `COULOMB_REFERENCE` environment variable when running from another checkout.

```bash
# from projects/coulomb-painter (the repo root)
cd rust
# CPU vs Python only.
cargo run -p coulomb-validate --release -- coulomb-validate/scenarios/T50k.json
# CPU vs GPU vs Python (three-way).
cargo run -p coulomb-validate --release -- coulomb-validate/scenarios/T50k_gpu.json
```

Substitute `T200k[_gpu].json` or `T10k[_gpu].json` for the other temperatures.
Each scenario runs in a few minutes; the hot temperatures need long trajectories so the sample-mean of the equilibrium energy converges below the 1% tolerance despite independent RNG streams between engines.

The GPU scenarios (`T*_gpu.json`) enable the `coulomb-gpu` engine.
On a machine without a real Vulkan-capable GPU, install Mesa's Lavapipe and pin the ICD:

```bash
sudo apt install mesa-vulkan-drivers
VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.x86_64.json \
  cargo run -p coulomb-validate --release -- coulomb-validate/scenarios/T50k_gpu.json
```

Local bench:

```bash
cargo run -p coulomb-bench --release -- \
  --scenario coulomb-validate/scenarios/T50k_gpu.json --duration 30s
```

Unit tests:

```bash
cargo test -p coulomb-core --release
cargo test -p coulomb-gpu  --release      # needs a wgpu adapter
```

## Validation approach

`coulomb-validate` reports three quantities per stage:

- Single-move acceptance rate.
  A statistical property of the Metropolis chain that averages out RNG-stream differences and is the natural quantity for a physics-parity test - matches the same standard the desktop CUDA-vs-CPU validation uses (`projects/coulomb-brush/README.md`, "Validated against the CPU").
- Mean energy across the second half of the run, sampled at fixed iteration intervals.
  The single-snapshot energy has variance `O(sqrt(N) * kB * T)` from thermal fluctuations that make a same-seed comparison thermodynamically meaningless when the RNG streams differ (they always will between Rust `Pcg64Mcg` and numpy's PCG64).
  The tail mean converges to the equilibrium `<E>` as the sample count grows.
- Particle count, which the physics conserves exactly.

Bit-for-bit is not required for the trajectory, but the initial energy is bit-identical (14 significant figures) because initial placement is deterministic and both engines build `u_edge` and the mobile-mobile kernel from the same closed-form expressions.

## Android build

M2b cross-compiles `coulomb-core` and `coulomb-gpu` to `aarch64-linux-android` (arm64-v8a) and `armv7-linux-androideabi` (armeabi-v7a) and validates the WGSL kernel down to SPIR-V so Android's Vulkan backend will accept it.
No device or emulator is involved at this milestone; JNI glue and the Compose UI arrive with M3.

Prerequisites (one-time):

```bash
# 1. Android NDK. r27d (27.3.13750724) is what M2b was landed with; r26+ is fine.
#    Install via Android Studio's SDK Manager, sdkmanager --install "ndk;27.3.13750724",
#    or setup-ndk in CI. Then export ANDROID_NDK_HOME to that install.
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/27.3.13750724

# 2. cargo-ndk (thin wrapper that hands cargo the right linker + sysroot).
cargo install cargo-ndk --locked

# 3. Rust std for both android targets, on the pinned toolchain.
rustup target add aarch64-linux-android armv7-linux-androideabi
```

Then, from `rust/`:

```bash
cargo xtask android-build
```

That single command:

1. Fails loudly if `ANDROID_NDK_HOME` (or `ANDROID_NDK_ROOT`) is not set or does not point at an NDK install.
2. Runs `naga` (pinned to the same version wgpu 0.19 uses) over `coulomb-gpu`'s WGSL sources, validates them, and lowers each to SPIR-V.
   A parse or validation error fails the task before any expensive cross-compile starts.
   The task is defined in `xtask/` and exposed as a `cargo` alias in `rust/.cargo/config.toml`; `cargo xtask validate-wgsl` runs the WGSL step on its own.
3. Runs `cargo ndk -t arm64-v8a -t armeabi-v7a build --release -p coulomb-core -p coulomb-gpu`.
4. Prints the built `.so` paths and sizes.

Expected outputs (release, unstripped, NDK r27d, Rust 1.95, sizes are approximate; the arm64 build is a few percent smaller than debug):

```
target/aarch64-linux-android/release/libcoulomb_core.so   ~ 450 KB
target/aarch64-linux-android/release/libcoulomb_gpu.so    ~ 780 KB
target/armv7-linux-androideabi/release/libcoulomb_core.so ~   7 KB
target/armv7-linux-androideabi/release/libcoulomb_gpu.so  ~ 335 KB
```

The `libcoulomb_core.so` on armv7 is small on purpose.
`coulomb-core` exports no C ABI symbols today, so under `-C link-arg=--gc-sections` (the Android default) the cdylib is nearly runtime-only.
Its physics is linked in transitively wherever downstream crates use the rlib (which is what `coulomb-gpu` does, and what M3's JNI crate will do).

The `crate-type = ["rlib", "cdylib"]` declaration on both crates is what lets the same `cargo build` produce both: the rlib for the workspace's own bench/validate/test consumers, and a `.so` for `cargo ndk`.

CI: `.github/workflows/android-build.yml` runs the same task on `ubuntu-latest`.
It is not (yet) a required check for merge - see the M2b PR body.

## What's not here yet

- Android JNI bindings, Jetpack Compose UI, SurfaceControl: M3.
- `.cmb` save/load, brush undo persistence across GPU restart, GPU-side brush `u_edge` patching: M5.
