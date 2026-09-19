# Coulomb Painter

A painter for exploring Coulomb-gas dynamics.
Paint fixed or mobile charges onto a canvas and watch them arrange under short-range attraction and long-range repulsion, with the same physics the research prototype in `coulomb-brush` established.

The goal is a painter-first experience, along the lines of Photoshop's basic surface: new canvas, save, open, export.
Not a research instrument, and not a lab notebook - the honest scientific version of the same engine lives in a separate research repository.

## Status

Bootstrapping.
Nothing playable yet.
The first drop is a browser painter with a Windows wrapper (Tauri), served locally by a Python physics backend.
An Android build follows once the physics kernel is ported to a portable browser runtime (WebGPU with a WASM fallback), so that no Python is bundled with the mobile app.

## Layout

The scaffold and application code are populated by the first ship task and this section will be filled in as it lands.

## Licence

BSD 2-Clause.
See `LICENSE`.
