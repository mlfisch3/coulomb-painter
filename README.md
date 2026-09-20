# Coulomb Painter

A painter for exploring Coulomb-gas dynamics.
Paint fixed or mobile charges onto a canvas and watch them arrange under short-range attraction and long-range repulsion, with the same physics the research prototype in `coulomb-brush` established.

The goal is a painter-first experience, along the lines of Photoshop's basic surface: new canvas, save, open, export.
Not a research instrument, and not a lab notebook - the honest scientific version of the same engine lives in a separate research repository.

## Status

MVP.
Browser-only, CPU-only physics, no save/load yet.
Enough for a session of "new canvas, paint, watch it anneal, tweak knobs" but not much more.
The follow-up ships are called out in `.claude` and in the ship task brief.

## How to run

```bash
python -m venv .venv && source .venv/bin/activate
pip install -e .
coulomb-painter                       # then open http://127.0.0.1:8770
# or, without installing:
pip install numpy scipy Pillow flask
python -m coulomb_painter --open
```

The server prints its URL on startup.
Open it in a modern browser (Chrome, Firefox, Safari).
The first time it loads, a "New canvas" dialog asks for resolution and initial fill; pick 256 for a fast lattice, 1024 for a pretty one.

## What is here

- **`coulomb_painter/sim.py`** - the live simulator: CPU batch Metropolis, priority-lock discipline so paint and reset land within one iteration.
- **`coulomb_painter/coulomb.py`** - the interaction potential and its FFT convolution helpers.
- **`coulomb_painter/brush.py`** - the brush geometry, stroke sampling, and the incremental-patch update from `coulomb-brush/brush.py`.
- **`coulomb_painter/gpu_backend.py`** - shell around the tiled-Metropolis decomposition; the CUDA kernel from `coulomb-brush/gpu_backend.py` is deliberately not ported yet, and the class raises so the CPU path cannot be silently bypassed.
- **`coulomb_painter/render.py`** - server-side PNG colouring of the frame.
- **`coulomb_painter/app.py`** - Flask endpoints and the annealing thread.
- **`coulomb_painter/web/`** - the browser UI (single-file HTML/CSS/JS).

## Painting

- **Sign +/-.**  Positive charge repels the mobile gas; negative gathers it.
- **Fixed vs Mobile target.**  Fixed charge is pinned like a line drawing; mobile charge stamps particles directly (add on +, remove on -).
- **Thickness** on the bottom bar for quick access; **magnitude, hardness, flow, density, coupling** in the Brush tab.

## Simulation controls

- **Pause / Resume** - the annealer stops; painting is still allowed.
- **Freeze / Run** - painting accumulates without the annealer stepping; press Run to release the anneal.
- **Reset** - returns to the state the New Canvas dialog left it in.
- **Live view** - turn frame rendering off when a big lattice starves the CPU.

## Physics controls

- **Coulomb strength**, **cutoff**, **boundary** (hard wall vs periodic).
- **Well depth** and **well range** (short-range attraction, mobile-mobile only).
- **Temperature**, **cooling on/off**, **batch size**, **max hop**.

## Layout

Mobile-first: the canvas anchors the view at every breakpoint, and no page ever scrolls.
Controls that do not fit at the tight case (portrait phone, ~360-420 dp wide) live in tabs behind a **Controls** popup that renders at ~85% opacity with a soft blur so the canvas remains visible behind it.
Every control has a `?` tooltip; inline multi-sentence descriptions are avoided on purpose.

## Not shipped in the MVP

Deferred to follow-up ships:

- Save / load project (`.cmb`), export image (PNG/SVG), open image.
- Preset charge-boundary patterns.
- GPU backend (shell exists; CUDA kernel is not ported yet).
- Tauri Windows wrapper.
- WebGPU/WASM physics port for Android.
- Viscosity brush and other captain-deferred controls.

## Licence

BSD 2-Clause.
See `LICENSE`.
