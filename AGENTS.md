# Project agent memory

Coulomb Painter is a native Android app on a shared Rust + wgpu physics core.
The full plan and per-milestone exit criteria are in `docs/android-plan.md`; read it before scoping any change here.

## Layout

- `docs/android-plan.md` - authoritative plan, non-goals, milestone list.
- `rust/` - Cargo workspace for the shared physics core.
  See `rust/README.md` for how the CPU reference, WGSL kernel (M2), and validator relate.
- `rust/coulomb-jni/` - JNI bridge for the Android app.
  `rust/coulomb-jni/README.md` documents the ~20-function C ABI and the panic-catch contract.
- `android/` - Compose Android Studio project (M3a).
  Its `README.md` covers prerequisites, `./gradlew :app:assembleDebug`, and how the Rust cross-compile is hooked into the Gradle build.
- Nothing under `rust/target/` or `android/*/build/` is committed.

## Physics reference

The desktop annealer whose physics this project ports lives outside this repository, in the fleet-managed sibling `projects/coulomb-brush/` (Python + optional CUDA).
The Rust validator finds it at `$COULOMB_REFERENCE` or the default `/home/drd/PROJECT/CLAUDE/FM-COULOMB/fm-coulomb/projects/coulomb-brush`.
Do not edit that reference from this repository; it is read-only for us.

## Validation approach

Between engines with independent RNG streams (Rust `Pcg64Mcg`, numpy PCG64, WGSL xorshift), single-snapshot energy has O(sqrt(N)*kB*T) thermal noise that swamps any 1% comparison at hot temperature.
The `rust/coulomb-validate` binary therefore compares single-move acceptance rate and the mean of the running energy sampled across the second half of the run, averaged over independent chains.
Particle count is checked for exact equality (charge is conserved by construction).

`T*_gpu.json` scenarios exercise the third engine (`coulomb-gpu`) alongside the CPU and Python references and pairwise-compare all three.
On a machine without a real GPU, install Mesa Lavapipe and pin the ICD via `VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.x86_64.json`.
`coulomb-gpu`'s own README covers the WGSL kernel structure, the `u_edge` upload contract, and the `WGPU_BACKEND` / `WGPU_ADAPTER_NAME` env overrides.

## Maintaining this file

Keep entries useful to almost every future session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
