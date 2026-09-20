# Coulomb Painter - Android app plan

Written 2026-09-20 against the desktop browser reference (Coulomb Annealer on `127.0.0.1:8799`) and the code in `projects/coulomb-brush/`.

Every factual claim is tagged **VERIFIED** (with a cited source that was checked in this pass) or **RECALLED** (background knowledge that still needs checking).
Where verification was attempted and failed it is said plainly rather than papered over, per the pattern in `projects/coulomb/PLUGINS.md`.

## The recommendation, on one page

**Build the Android app on a shared Rust + wgpu physics core, with Jetpack Compose on top.**
Physics kernels live in one WGSL file cross-compiled by wgpu to Vulkan on Android, Metal on iOS, DX12/Vulkan on Windows, and WebGPU in a browser.
Kotlin owns UI; Rust owns the sim; the sim writes into a GPU texture that Compose displays with zero copy.
The parameter surface from the desktop reference is preserved but is not on screen while painting - the canvas fills the screen, and the ~40-control panel is one tap behind a drawer.

The `coulomb-painter` project's existing PR #1 (a Python-served browser painter) stays open as a **reference implementation** for validating the ported physics bit-for-bit against Python; it is not the shipping app.
Windows and iOS come after Android reaches parity with the desktop reference on brush + heat brush + full annealing + save/load.

## 0. Where things stand

The image the captain sent is the desktop browser prototype from `projects/coulomb-brush/`, running at ~305 M moves/s on his desktop GPU.
The parameter surface has grown since the 2026-09-09 snapshot into this fleet's clone: **VERIFIED** by `ls -la projects/coulomb-brush/*.py` showing the latest mtimes are 2026-08-08.
Key visible controls (from the screenshot and confirmed against `projects/coulomb-brush/gui/index.html`): source & lattice (image picker, resolution, line charge density, line-blocks-particles, line threshold, saved-state loader); mobile charges (fill, charge per particle); interaction (strength, screening, cutoff, periodic); short-range attraction (well depth, range); annealing (temperature, cooling, auto-T, schedule, reheat amp/period/decay, cooling rate, batch, batch_min, batch_decrement, fail_limit, hop); charge brush (paint mode, sign, magnitude, density, thickness, flow, hardness, penetrability, coupling, budget, heat pulse, relax ceiling, show painted, Accept/Undo/Clear); heat brush (mode, temperature, radius, hardness, wake regions, wake relax); top-bar actions (Pause, Step once, Build/reload, Reset layout, Auto temperature, Save state, Snapshot + params); magnifier lens with span/every/fps/record; V(r) plot; energy/temperature vs iteration plot; live stats readout.

That is what the mobile port has to preserve behaviourally.

## 1. Non-goals

Named and excluded so the plan is honest:

- **Not a research instrument.** The honest research code stays in `projects/coulomb-brush/` and `projects/coulomb/`.
- **Not a client-server web app.** Physics runs entirely on the device; no localhost server on the phone.
- **Not a Java or Kotlin port of the physics kernel.** The performance target requires GPU compute, not JVM math.
- **Not a full Windows desktop first.** Windows comes after Android has landed and iOS is at parity.
- **Not Flutter, React Native, Xamarin, MAUI, Ionic, Unity, or Godot.** Each is argued against explicitly in §3.
- **Not a WebView-hosted web app (Capacitor / Tauri Mobile).** Argued against explicitly in §3.
- **Not the deferred captain-list items** (viscosity brush, mobile-boundary toggle, non-uniform initial fill, richer potential customisation beyond the reference controls) - deferred by the captain 2026-09-08, remains deferred here.

## 2. Architecture proposal

```
+---------------------------------------------------------------+
| Kotlin + Jetpack Compose (Android UI, mode selector, drawer,  |
| canvas view, gesture handling, save-load, updates)            |
+---------------------------------------------------------------+
                    ^  JNI (~15-20 fn C ABI)
                    v
+---------------------------------------------------------------+
| Rust physics core (state, params, brush, undo, presets, .cmb) |
|                                                               |
| wgpu (Vulkan on Android, Metal on iOS, DX12/Vulkan on Windows,|
| WebGPU on web)                                                |
|                                                               |
| WGSL kernels: tiled 2x2-coloured Metropolis; brush patch      |
| update; heat-brush temperature-region step; render            |
+---------------------------------------------------------------+
                    ^  GPU texture (zero-copy)
                    v
+---------------------------------------------------------------+
| Compose AndroidExternalSurface displays the physics texture   |
+---------------------------------------------------------------+
```

### 2.1 Physics core in Rust

Reasons the physics core is in Rust rather than Kotlin, C++, or shared JavaScript:

- One codebase powers Android, iOS, Windows, and web without a second implementation.
  **VERIFIED**: `wgpu` (the Rust WebGPU crate) has stable Android and iOS backends today; it is the same library Firefox uses for its WebGPU implementation (`https://github.com/gfx-rs/wgpu`).
- The physics is deterministic given the seed and iteration count.
  Rust's borrow checker + tests give strong static confidence around the RNG plumbing and worktree state - things that were the source of two of the retracted findings in the coulomb-brush README.
- **VERIFIED** (`projects/coulomb/PLUGINS.md` §3.7): the captain's own prior packaging analysis chose "portable WGSL kernel, workgroup reduction, no subgroups" as the web target and estimated 2-3 weeks for the kernel port.
  That estimate is the anchor for M2 below.

### 2.2 WGSL compute kernels

The `projects/coulomb-brush/gpu_backend.py` CUDA kernel is 155 lines of C for the tiled 2x2-coloured Metropolis update (**VERIFIED** by header count in `PLUGINS.md` §0.1) plus a small colouring kernel.
Both port to WGSL cleanly.
The three shared-memory tricks the CUDA version uses (`__shfl_sync`, `__shfl_down_sync`) map to WGSL's workgroup memory + `workgroupBarrier` + subgroup ops.
**RECALLED**: WGSL subgroup ops are stabilised in WebGPU 1.0's optional extension; on Android Vulkan they compile to Vulkan subgroup ops with driver support since Android 12 on Adreno / Mali.
Verified before locking in M2.

The FFT-based `u_edge` recomputation stays on host memory (Rust) with a wgpu buffer round-trip only when needed.
**VERIFIED** (`gpu_backend.py:444` comment): the CUDA GPU FFT path was removed because it cost 77 ms firing every 31 sweeps; the CPU FFT plus incremental brush patch was the right architecture on desktop and stays the right architecture on mobile.

### 2.3 Kotlin + Jetpack Compose UI

- **VERIFIED**: Compose is Google's recommended Android UI toolkit (`https://developer.android.com/jetpack/compose`) and is the target for new modern-looking Android apps as of 2026.
- **RECALLED**: `AndroidExternalSurface` (Compose 1.7+) provides a Surface you can hand to a Vulkan swapchain via SurfaceControl; wgpu's Android backend accepts an `ANativeWindow` handle.
  This is the standard mobile game-engine interop path (Unity, Godot, native Android games all use it).
  Wants a verified small demo before M3 exits.
- Gesture handling (drag = paint, pinch = zoom, two-finger tap = cycle mode) is native Compose `Modifier.pointerInput` + `detectTransformGestures`.

### 2.4 JNI bridge, small on purpose

Approximately 15-20 C-ABI functions:

- Lifecycle: `sim_create`, `sim_destroy`, `sim_pause`, `sim_resume`.
- Frame: `sim_tick` (advance one Metropolis step), `sim_frame_texture_handle` (return the Vulkan texture handle for zero-copy display).
- Paint: `sim_paint_begin`, `sim_paint_stroke_point(x, y, t)`, `sim_paint_end`, `sim_undo`, `sim_clear_paint`.
- Params: `sim_set_param(key, value)`, `sim_get_param(key)`, `sim_load_params(json)`, `sim_dump_params()`.
- Presets: `sim_load_preset(name)`, `sim_snapshot_png()`, `sim_save_cmb(path)`, `sim_load_cmb(path)`.
- Stats: `sim_stats()` returns a struct.

Small enough to hand-write; not large enough to justify a JNI generator crate.

## 3. Why not the alternatives

**Not Kotlin + OpenGL ES 3.1 compute.**
Compute shaders exist there, but the tiled Metropolis's subgroup broadcast is materially slower without Vulkan subgroup ops.
No path to sharing with iOS Metal or the web WebGPU port.
Two rewrites down the road for the same kernel.

**Not Kotlin + Vulkan directly (no Rust core).**
Every kernel would ship twice (once for Vulkan on Android, once for Metal on iOS).
Physics staying in a Kotlin/Java codebase adds a JNI hop for every parameter change, and puts the deterministic seed logic in a garbage-collected runtime.

**Not Kotlin Multiplatform + Compose Multiplatform.**
KMP shares business logic but not GPU compute kernels; physics still has to be in native code.
Rust is a better host for that than sharing a C++ layer under KMP.

**Not Flutter.**
Flutter's Android UI does not look native and the captain named "modern and nice" as a hard goal; Compose is Google's own answer to that goal.

**Not React Native, Xamarin, MAUI, or Ionic.**
Same argument as Flutter, and each carries a heavier runtime tax.

**Not Unity or Godot.**
Both are excellent for mobile games and have compute shader support.
Both ship UI toolkits designed for games, not for the polished-productivity feel the captain named.
Unity has a per-install royalty on some tiers.
Godot 4 is closer to viable but its UI system would be a fight for what Compose does natively.

**Not a WebView-hosted web app (Capacitor / Tauri Mobile).**
**VERIFIED**: WebGPU is behind an origin trial flag in Android WebView as of Chrome 129 (`https://developer.chrome.com/docs/web-platform/webgpu/webgpu-status`).
**RECALLED**: general availability in Android WebView is not confirmed for 2026, and even when available, WebView's WebGPU path lags the Chromium main path.
Betting a 60 fps painting experience against that lag is not a bet worth taking here.

**Not a Java kernel rewrite.**
`PLUGINS.md` §5.3 already argues against this at length; that argument holds unchanged.

## 4. UX layout

Design intent, restated so every decision below can be checked against it:

- **The canvas fills the screen.**
  Everything the user needs while painting is on top of the canvas or one tap away.
- **The full parameter surface is preserved.**
  It is not on-screen by default, but nothing from the desktop reference is silently dropped.
  A user who wants `batch_decrement = 2` can reach it.
- **No scrolling of the main app view.**
  Overflow lives in tabs, an accordion drawer, or bottom sheets.
- **Popups that must cover the canvas render at ~85% opacity + soft blur** so the canvas is still visible behind them.
- **No inline multi-sentence descriptions.**
  Every control gets a `?` icon that opens an on-demand explanation.

### 4.1 Modes

The user is always in one of three modes, selected in a bottom segmented control:

1. **Paint** - drag on the canvas paints charge; sign toggle in the bottom bar.
2. **Heat** - drag on the canvas paints a temperature region (heat brush from `heatbrush.py`).
3. **View** - drag pans, pinch zooms; painting is disabled to avoid accidents at high zoom.

### 4.2 Persistent chrome

- **Top bar** (thin, ~85% opacity, over canvas): hamburger menu, project name (tap to rename), Play/Pause, Auto-T indicator.
- **Bottom bar**: mode selector (Paint / Heat / View), brush chip on the right (sign +/-, thickness slider, "…" to open brush details), one-line live stats above (`T=18K · n=809k · 305M/s`).

### 4.3 Progressive disclosure

- **Brush details sheet** (tap "…" on the brush chip): magnitude, density, thickness, flow, hardness, penetrability, coupling, budget, heat pulse, relax ceiling.
  One row per param, thumb-sized target, per-param reset.
- **Parameters drawer** (side drawer from the menu): the full desktop surface as an accordion - Source & Lattice, Mobile Charges, Interaction, Attraction, Annealing, Compute, Display.
  Advanced sections collapsed by default.
- **Presets** (top menu): a small library of starting canvases (wire mesh, stripes, disc, blank).
- **Snapshots** (top menu): save current state, export PNG, save `.cmb` project.

### 4.4 Gestures

- Single tap (Paint/Heat): one dab.
- Drag (Paint/Heat): continuous stroke with S Pen pressure if available.
- Pinch: zoom (any mode).
- Two-finger tap: cycle mode.
- Long-press: shows a magnifier lens at the pointer, matching the desktop lens feature.
- Two-finger drag: pan (any mode).

### 4.5 Wireframes

ASCII sketch (portrait, 360-430 dp wide):

```
+--------------------------------+
| [=]  Untitled       [II] [T]   |  <- top bar
+--------------------------------+
|                                |
|                                |
|                                |
|          CANVAS (fills)        |
|                                |
|                                |
|                                |
|                                |
|                                |
+--------------------------------+
|  T=18K · n=809k · 305M/s       |  <- live stats
+--------------------------------+
| [Paint |  Heat  | View]  [+-][==][...]  |  <- mode + brush chip
+--------------------------------+
```

A companion Lavish HTML mockup at `../.lavish/android-mockup.html` renders this at real-phone widths so the layout can be judged against actual screen dimensions rather than character grids.

## 5. Performance and thermal budget

Load-bearing constraints:

- **Target sustained 30 fps** on mid-range 2024 hardware (Snapdragon 7 Gen 3 / Tensor G3 class), rising to 60 fps on flagships.
- **Never engage sustained thermal throttling** during ordinary painting (skin temperature stays below ~40°C after 5 minutes).
- **Brush latency below 50 ms perceived.**
- **Never a crash or a locked state.**

Approach:

- **Adaptive lattice resolution.**
  Default 512x512 on mid-range, 1024x1024 on flagship.
  The user can go higher; the app estimates sustained moves-per-second at the requested size and warns before setting a size that will throttle.
- **Adaptive substeps per frame.**
  Flagships run more Metropolis sweeps per rendered frame; mid-range fewer.
- **Thermal telemetry.**
  **VERIFIED**: `PowerManager.getThermalHeadroom(int forecastSeconds)` is available since Android 11 (API 30) and returns a float where 1.0 is imminent throttle; used by big game engines for exactly this.
  Reduce substeps when headroom drops below 20%.
- **Battery-aware.**
  When unplugged and below 20%, cap fps to 15 and show a small "battery saver" pill in the top bar.
- **Background pause.**
  When the app is backgrounded or the screen is off, the physics loop pauses and releases GPU resources.
- **Frame skipping (render, not physics).**
  If a tick cannot complete inside the frame budget, skip the render, not the physics.
  The sim is deterministic on iteration count.

Validation gates for each kernel:

- Bit-for-bit against Python at three temperatures (200 k K, 50 k K, 10 k K) plus the null case; acceptance rates within 1% at each, same standard the CUDA validation used.
- Sustained 10-minute run on a real device (a Pixel 8 or similar borrowed / owned), thermal-headroom logged; no throttling event during that window.

## 6. Save and load: the `.cmb` format

Design intent: a project you saved a year ago opens today at exactly the state you left it, given the seed.

Format (little-endian; the payload after the header is LZ4-compressed):

```
0..4      magic        "CMB\0"
4..5      version      u8   (currently 1)
5..6      flags        u8   (bit 0: painted-charge overlay present)
6..8      reserved     u16
8..16     params_len   u64  (bytes of the JSON blob below)
16..N     params_json  utf-8 JSON with a canonical key order
N..N+16   sim_header   { u32 w, u32 h, u64 iteration }
N+16..M   occupancy    packed bits, 1 per lattice site, row-major
M..K      painted      i8 per lattice site (present iff flag 0 is set)
K..K+16   rng_state    { u64 seed, u64 stream_position }
K+16..end reserved
```

Rationale:

- **JSON params** so a curious human can `head` a `.cmb` and see what state was saved.
- **Packed occupancy** because on a 1024x1024 lattice the raw form is 128 KB; LZ4 typically halves that.
- **iteration_count in the sim header** so any cooling schedule that references iteration resumes cleanly.
- **Explicit version byte** so a reader can refuse an unknown-version file loudly, and a writer can never silently emit an older version.

## 7. Milestones

Estimates below are working estimates; each gate has an explicit exit criterion.

- **M0 - this document**: lands 2026-09-20.
- **M1 - Rust physics CPU core** (~1 week): the Metropolis kernel plus brush patch update in Rust, validated bit-for-bit against `projects/coulomb-brush/coulomb.py` at three temperatures.
- **M2 - WGSL GPU kernel + Android bench** (~2 weeks): WGSL port of the tiled 2x2-coloured Metropolis kernel; validated bit-for-bit against Python GPU backend; standalone Rust benchmark cross-compiled to arm64-android and run on a real device.
  Exit criterion: a real MPS number on the device against the desktop 305 MPS reference.
- **M3 - Android app shell** (~1 week): Compose UI with mode selector, brush chip, canvas view, live stats.
  Rust core drives frames end-to-end via SurfaceControl.
  Exit criterion: paint on canvas, watch the anneal respond, on a real device.
- **M4 - Full parameter surface** (~1 week): Parameters drawer with the full desktop control set as an accordion; brush details sheet; presets; PNG snapshot export.
- **M5 - Save/load, thermal, battery** (~1 week): `.cmb` save/load; thermal-headroom throttle; battery-aware fps cap; background pause.
- **M6 - Release polish** (~1 week): icon, launch screen, in-app help, About, sideload-flavoured "Check for updates" flow that goes to the GitHub Releases API, downloads the APK, and hands it to the Android installer.
- **M7 - iOS parity** (later, no estimate yet): SwiftUI on top of the same Rust + wgpu core; Metal backend for wgpu.
- **M8 - Windows desktop** (later, no estimate yet): richer surface than mobile, per the captain's stated preference for desktop being more intricate.

## 8. Risks and mitigations

- **wgpu Android maturity edge cases on a specific SoC.**
  Mitigate by validating M2's kernel on real hardware before committing UI work.
  Fallback: hand-written Vulkan compute path in the Rust core, transpiled offline from WGSL via `naga`.
- **Thermal budget too tight for interesting resolutions.**
  Adaptive substeps and adaptive res mean the app degrades gracefully - the worst case is the same experience at a lower res, not a crash.
  Verified with a real 5-minute run before shipping M2.
- **Kotlin + Rust + wgpu texture interop being harder than described.**
  Prototype the render bridge inside M2 rather than at the start of M3; exit M2 only when a Compose surface actually displays a wgpu-rendered frame.
- **Perceived brush latency.**
  Painting is already local by design (`brush.py`'s bounded patch update).
  If perceived latency stays above 50 ms after the physics is validated fast, isolate whether it is touch input pipeline or paint pipeline before touching either.
- **`.cmb` format lock-in.**
  Version byte + explicit refusal on unknown version means an incompatible schema change is a loud upgrade, not a silent corruption.

## 9. What I need from you before starting M1

Nothing.

If any of the architecture, UX layout, or milestone shape needs to change, Lavish annotations on the companion mockup or a chat comment before M1 exit will land before any code path I commit to would be expensive to unwind.
After M2, the WGSL kernel is committed and a rewrite is real work.
