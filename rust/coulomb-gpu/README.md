# coulomb-gpu

wgpu port of the M1 CPU physics kernel.
Implements the tiled 2x2 coloured Metropolis sweep in WGSL against `coulomb-core`'s Hamiltonian, validated on the desktop before Android hardware enters the picture.

## Kernel structure

- One workgroup per tile, 32 lanes per workgroup.
  A workgroup performs `moves_per_tile` sequential single-particle Metropolis moves confined to its tile interior.
- Tiles of one colour are separated by a full tile on the lattice.
  With tile edge `L > cutoff` no two same-colour tiles can share a pair-potential contribution during their concurrent update, so the energy decomposes exactly across colours and the tiles are independent (see `projects/coulomb-brush/gpu_backend.py` for the same argument in the reference).
- The tile grid gets a fresh random `(oy, ox)` offset every launch.
  Confining moves to tile interiors freezes motion across tile seams, and the per-launch offset restores ergodicity by rotating those seams across the lattice.

## Warp broadcast / warp reduction

WebGPU 0.19 does not yet expose subgroup ops as a stable portable feature.
The CUDA reference uses `__shfl_sync(lane 0)` to broadcast the chosen move and `__shfl_down_sync` to reduce the neighbourhood sum; this crate uses workgroup-shared memory for both, with a single `workgroupBarrier` after the write.
The workgroup path is ~30% slower than a subgroup path on desktop, but portable to every wgpu backend on every desktop and every phone we plan to ship.
When wgpu picks up stable subgroup ops the WGSL can gain a conditional subgroup path, but the outer Rust API does not change.

## u_edge upload contract

`u_edge` is the potential from fixed line charges plus the incremental paint field.
It is built by `coulomb-core`'s FFT rebuild on the CPU and uploaded to the GPU as a `f32` storage buffer at construction time.
GPU-side patching (the incremental brush stroke) is deferred to M5; a M2 sim is initialised with the full CPU-built `u_edge` and its content stays constant across the GPU's own sweeps.
The desktop reference retired its GPU FFT for the same reason (`projects/coulomb-brush/gpu_backend.py:444` documents why); the incremental patch upload path is a much smaller transfer than a whole-lattice rebuild.

## Adapter selection

The library picks the first adapter matching `WGPU_BACKEND`, preferring `DiscreteGpu` > `IntegratedGpu` > `VirtualGpu` > `Cpu`.
Environment overrides:

- `WGPU_BACKEND=vulkan|metal|dx12|gl` restricts the search to one backend.
- `WGPU_ADAPTER_NAME=<substring>` pins to an adapter whose name (case-insensitive) contains the substring - useful to force `llvmpipe` for CPU validation on a machine with a discrete GPU.

Under WSL2 with no `/dev/dri` node exposed, install Mesa's Lavapipe (`libvulkan_lvp.so`) and run with `VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.x86_64.json` so the Vulkan loader can find the CPU ICD.
Adapter search failures print the exact override commands to try.

## Occupancy storage choice

Occupancy lives as a `Storage<u32>` buffer, one word per cell.
An `R8Uint` storage texture would halve the memory footprint but currently requires the `TEXTURE_ADAPTER_SPECIFIC_FORMAT_FEATURES` feature to be a write target for both the compute pass and the render pass, and that feature is not portable to every backend we target.
The storage buffer path is uniformly fast on lavapipe, desktop Vulkan, and the Adreno subset of the M3 target list.
The M2 kernel keeps the compact byte packing option available: the WGSL can switch to `u32` array of `4x u8` cells if a later profile shows the memory bandwidth mattering.

## Binaries

- `coulomb-bench`: launches the kernel on a scenario and prints MPS, render FPS, adapter identity, and peak GPU buffer allocation.
- `coulomb-validate`: extended in M2 to run the CPU core, the WGSL kernel, and the Python reference on the same scenario and compare all three pairwise.

## Non-goals

- Brush-stroke `u_edge` patching on the GPU: M5.
- Android SurfaceControl integration and JNI bridge: M3.
- Cross-compilation to `aarch64-linux-android`: M2b.
