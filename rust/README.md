# coulomb-painter/rust

Rust workspace for the Coulomb Painter physics kernel.

M1 milestone.
This CPU implementation is the deterministic cross-platform reference against which the WGSL GPU kernel (M2) will be validated.
`coulomb-core` is deliberately single-threaded and CPU-only; performance is not a goal here, correctness against the desktop physics is.

## Crates

- `coulomb-core`: the physics library.
  Batch-Metropolis on a mobile-charge occupancy grid, `exp(-r/L)/r` screening, short-range attractive well between mobile charges, `u_edge` FFT construction from line charges via `rustfft`, and the incremental brush-patch update (a stroke touches only its own bounded box grown by the coupling radius, not a whole-lattice FFT).
- `coulomb-validate`: a binary that runs a JSON scenario against `coulomb-core` and against a Python subprocess that imports `projects/coulomb-brush/coulomb.py` and `brush.py`.
  It compares single-move acceptance rate and end-of-run mean energy at each temperature, and it exit-codes non-zero if any comparison is outside tolerance.

## Toolchain

`rust-toolchain.toml` pins Rust 1.95.0 with the rustfmt and clippy components.
No nightly features.

## How to run

The Python subprocess reads `coulomb.py` and `brush.py` from the fleet-managed reference at `/home/drd/PROJECT/CLAUDE/FM-COULOMB/fm-coulomb/projects/coulomb-brush`.
Override the path with the `COULOMB_REFERENCE` environment variable when running from another checkout.

```bash
# from projects/coulomb-painter (the repo root)
cd rust
cargo run -p coulomb-validate --release -- coulomb-validate/scenarios/T50k.json
```

Substitute `T200k.json` or `T10k.json` for the other temperatures.
Each scenario runs in a few minutes; the hot temperatures need long trajectories so the sample-mean of the equilibrium energy converges below the 1% tolerance despite independent RNG streams between Rust and numpy.

Unit tests for the physics kernel:

```bash
cargo test -p coulomb-core --release
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

## What's not here yet

- The WGSL GPU kernel: M2.
- Multithreading of the CPU kernel: not needed for a validation reference; the GPU path is M2's territory.
- Android JNI bindings, `.cmb` save/load, Compose UI: M3-M5.
