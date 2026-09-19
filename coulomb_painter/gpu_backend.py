"""Optional GPU backend: tiled parallel Metropolis for the Coulomb gas.

Deliberately preserved from `coulomb-brush/gpu_backend.py` even though the
first painter MVP runs on CPU only.  The tiled decomposition is the honest way
to get real throughput out of this model:

Partition the lattice into tiles of edge L and colour them in a 2x2 pattern.
Tiles of one colour are separated by a full tile, so the closest any two
points in two same-coloured tiles can be is L.  Confine every proposed move to
its own tile.  If L > cutoff, a particle in one active tile can never interact
with a particle in another active tile, the energy is exactly separable, and
the tiles update simultaneously with no communication.

This is not an approximation: each tile runs an ordinary Metropolis chain on
an independent term of the Hamiltonian, so detailed balance holds.  Confining
moves does freeze motion across tile seams, so the tile grid is given a fresh
random offset every launch, restoring ergodicity - the offset is drawn
independently of the configuration, so it cannot bias sampling.

The full CUDA kernel and warp-per-tile scheduler live in the reference
implementation.  This module is a shell: it advertises whether CuPy is
importable and offers ``build_tile_colours``, which every backend needs, so
the CPU path can reuse the same tile scheme when we come to port it.  The
Python entry point ``GPUEngine`` raises ``NotImplementedError`` until the
kernel is brought over in a follow-up ship - the painter MVP does not need it,
and porting the kernel unread would risk silently drifting from the
reference's validated behaviour.
"""

from __future__ import annotations

import numpy as np

try:                                          # pragma: no cover - env-dependent
    import cupy as cp                         # noqa: F401
    HAVE_CUPY = True
except Exception:                             # pragma: no cover
    HAVE_CUPY = False


def build_tile_colours(h: int, w: int, tile: int, offset_y: int = 0,
                       offset_x: int = 0):
    """Return four lists of tile origins, one per 2x2 colour class.

    Two tiles of the same colour are at least ``tile`` apart in both axes, so
    if the interaction cutoff is strictly less than ``tile``, they cannot
    interact.  The tile grid is offset before every sweep so that motion
    across a seam is not frozen for ever.
    """
    tiles = [[], [], [], []]
    y = offset_y % (2 * tile) - 2 * tile
    while y < h:
        x = offset_x % (2 * tile) - 2 * tile
        while x < w:
            cy = (y // tile) & 1
            cx = (x // tile) & 1
            colour = cy * 2 + cx
            if y >= 0 and x >= 0 and y + tile <= h and x + tile <= w:
                tiles[colour].append((y, x))
            x += tile
        y += tile
    return [np.asarray(c, dtype=np.int64).reshape(-1, 2) for c in tiles]


class GPUEngine:
    """Placeholder that documents the intended shape of the engine.

    Instantiating it raises so the CPU path can never be silently bypassed by
    an incomplete port.  The follow-up ship that lands the CUDA kernel from
    the reference should replace this class in place, keeping the same
    method surface: ``sweep``, ``sync``, ``occupancy``, ``take_energy_delta``,
    ``add_u_edge``, ``set_blocked``.
    """

    def __init__(self, *args, **kwargs):
        raise NotImplementedError(
            "The GPU backend is not wired up in the painter MVP; run on CPU. "
            "Port the CUDA kernel from projects/coulomb-brush/gpu_backend.py "
            "in a follow-up ship to enable it.")
