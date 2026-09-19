"""Painting charge onto a running Coulomb gas.

Adapted from `coulomb-brush/brush.py`.  The brush lays down a signed, smoothly
tapered ridge of *fixed* charge; a separate mobile-charge stamp reuses the same
radial profile so the two brush targets behave the same everywhere except in
what they write.

Physical locality lives in the coupling radius: a painted cell pushes on mobile
charge only out to that radius, with a raised-cosine shoulder so its influence
tapers instead of stopping at a wall.  Computational locality lives in the
patch: a stroke changes ``u_edge`` only inside its own bounding box grown by
the coupling radius, so the update is a small convolution added into the
existing field, never a whole-lattice FFT.  For a 12 px stroke on a 512 lattice
that is three orders of magnitude less arithmetic - the difference between a
brush that tracks the mouse and one that does not.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np
from scipy.signal import fftconvolve


@dataclass
class Brush:
    """Brush settings for one stroke.  Lengths are in lattice cells."""

    sign: float = 1.0            # +1 repels the gas, -1 gathers it
    magnitude: float = 1.0       # peak charge density of one fast pass
    density: float = 1.0         # charge laid per unit area
    thickness: float = 8.0       # stroke width, full width
    flow: float = 1.0            # charge laid per cell of travel
    hardness: float = 0.5        # plateau fraction; 1 = hard edge, 0 = all falloff
    penetrability: float = 1.0   # 1 = paint never blocks, 0 = blocks wherever it lands
    coupling: float = 12.0       # radius over which painted charge is felt at all
    budget: float = 60.0         # move attempts per painted cell after a stroke
    pulse: float = 2.0           # temperature multiplier at the start of the relax
    ms: float = 40.0             # wall-clock ceiling on the relax, milliseconds
    target: str = "fixed"        # "fixed" or "mobile"

    @classmethod
    def from_params(cls, p, override=None):
        b = cls(
            sign=float(getattr(p, "brush_sign", 1.0)),
            magnitude=float(getattr(p, "brush_magnitude", 1.0)),
            density=float(getattr(p, "brush_density", 1.0)),
            thickness=float(getattr(p, "brush_thickness", 8.0)),
            flow=float(getattr(p, "brush_flow", 1.0)),
            hardness=float(getattr(p, "brush_hardness", 0.5)),
            penetrability=float(getattr(p, "brush_penetrability", 1.0)),
            coupling=float(getattr(p, "brush_coupling", 12.0)),
            budget=float(getattr(p, "brush_budget", 60.0)),
            pulse=float(getattr(p, "brush_pulse", 2.0)),
            ms=float(getattr(p, "brush_ms", 40.0)),
            target=str(getattr(p, "brush_target", "fixed")))
        for k, v in (override or {}).items():
            if not hasattr(b, k):
                continue
            if k == "target":
                b.target = "mobile" if str(v).lower() == "mobile" else "fixed"
                continue
            try:
                setattr(b, k, float(v))
            except (TypeError, ValueError):
                pass
        b.sign = 1.0 if b.sign >= 0 else -1.0
        b.thickness = max(1.0, b.thickness)
        b.coupling = max(1.0, b.coupling)
        b.hardness = min(max(b.hardness, 0.0), 1.0)
        b.penetrability = min(max(b.penetrability, 0.0), 1.0)
        b.target = "mobile" if b.target == "mobile" else "fixed"
        return b


def radial_alpha(d: np.ndarray, radius: float, hardness: float) -> np.ndarray:
    """Unit plateau out to ``hardness*radius``, raised cosine to zero at ``radius``.

    ``hardness = 1`` degenerates to a hard disc, ``hardness = 0`` to a pure
    cosine bump.  The same shape serves the brush cross-section and the
    coupling shoulder, deliberately: both are "influence that must taper, not
    stop".
    """
    h = min(max(float(hardness), 0.0), 1.0)
    inner = radius * h
    a = np.zeros(d.shape, dtype=np.float64)
    a[d <= inner] = 1.0
    if radius > inner:
        m = (d > inner) & (d < radius)
        t = (d[m] - inner) / (radius - inner)
        a[m] = 0.5 * (1.0 + np.cos(math.pi * t))
    return a


def coupling_kernel(strength: float, screening: float, coupling: float,
                    hardness: float):
    """Potential a painted cell casts on mobile charge, tapered at ``coupling``.

    Purely repulsive in form, exactly like the line charges.  The r = 0 entry
    is zero because self-energy is not part of the model, so a painted cell
    does not act on a particle sitting on it.
    """
    rp = max(1, int(math.ceil(coupling)))
    d = np.arange(-rp, rp + 1)
    r = np.hypot(d[:, None], d[None, :]).astype(float)
    K = np.zeros_like(r)
    m = (r > 0) & (r <= coupling)
    rr = r[m]
    v = strength / rr
    if screening > 0:
        v = v * np.exp(-rr / screening)
    K[m] = v
    K *= radial_alpha(r, coupling, hardness)
    return rp, K


def stroke_samples(points, spacing: float = 0.5, first: bool = False):
    """Resample a polyline into (y, x, dose) stamps of roughly equal spacing.

    ``dose`` is arc length carried by the stamp, so a fast stroke and a slow
    one lay the same charge per unit length, while a stroke that dwells keeps
    emitting events with a floor of ``spacing`` of notional travel each.
    """
    pts = np.asarray(points, dtype=float)
    if pts.ndim != 2 or pts.shape[0] == 0:
        return []
    out = []
    if first:
        out.append((pts[0, 0], pts[0, 1], spacing))
    for i in range(1, len(pts)):
        y0, x0 = pts[i - 1]
        y1, x1 = pts[i]
        ds = math.hypot(y1 - y0, x1 - x0)
        m = max(1, int(math.ceil(ds / spacing)))
        dose = max(ds, spacing) / m
        for k in range(1, m + 1):
            t = k / m
            out.append((y0 + (y1 - y0) * t, x0 + (x1 - x0) * t, dose))
    return out


def stroke_patch(points, br: Brush, first: bool = False):
    """Charge laid by a stroke, as a patch and its origin in lattice coords.

    Returns ``(y0, x0, dq, cov)``.  ``dq`` is signed charge density; ``cov`` is
    the raw radial profile in [0, 1], reused by both brush targets - the
    fixed-charge path adds ``dq`` into the paint field, and the mobile-charge
    path uses ``cov`` to weight per-cell add/remove probabilities.
    """
    samples = stroke_samples(points, first=first)
    if not samples:
        return None
    R = max(0.5, br.thickness / 2.0)
    rr = int(math.ceil(R)) + 1
    ys = [s[0] for s in samples]
    xs = [s[1] for s in samples]
    y0 = int(math.floor(min(ys))) - rr
    x0 = int(math.floor(min(xs))) - rr
    y1 = int(math.ceil(max(ys))) + rr + 1
    x1 = int(math.ceil(max(xs))) + rr + 1
    dq = np.zeros((y1 - y0, x1 - x0), dtype=np.float64)
    cov = np.zeros_like(dq)
    # Normalise by the along-path integral of the profile, exactly
    # R * (1 + hardness) for a raised-cosine shoulder.  One fast pass then
    # lays a ridge whose peak is magnitude * density * flow regardless of
    # thickness, on the same scale as a fully inked line.
    norm = max(R * (1.0 + min(max(br.hardness, 0.0), 1.0)), 1e-9)
    amp = br.sign * br.magnitude * br.density * br.flow / norm
    gy = np.arange(y0, y1, dtype=float)
    gx = np.arange(x0, x1, dtype=float)
    for sy, sx, dose in samples:
        iy0 = max(0, int(math.floor(sy - R)) - y0)
        iy1 = min(dq.shape[0], int(math.ceil(sy + R)) + 1 - y0)
        ix0 = max(0, int(math.floor(sx - R)) - x0)
        ix1 = min(dq.shape[1], int(math.ceil(sx + R)) + 1 - x0)
        if iy0 >= iy1 or ix0 >= ix1:
            continue
        d = np.hypot(gy[iy0:iy1, None] - sy, gx[None, ix0:ix1] - sx)
        a = radial_alpha(d, R, br.hardness)
        dq[iy0:iy1, ix0:ix1] += amp * dose * a
        np.maximum(cov[iy0:iy1, ix0:ix1], a, out=cov[iy0:iy1, ix0:ix1])
    return y0, x0, dq, cov


def potential_patch(dq: np.ndarray, K: np.ndarray, rp: int) -> np.ndarray:
    """Edge potential a charge patch casts, over the patch grown by ``rp``.

    Exactly the term the global FFT would have produced, at a fraction of the
    cost: the kernel is radially symmetric, so full-mode output index i maps to
    lattice index ``y0 + i - rp``.
    """
    return fftconvolve(dq, K, mode="full")


def patch_index(y0: int, x0: int, ph: int, pw: int, h: int, w: int,
                periodic: bool):
    """Row/col indices for writing a patch into the lattice.

    Returns ``(rows, cols, sy, sx)`` for
    ``field[np.ix_(rows, cols)] += patch[sy, sx]``, or None if the patch misses
    the lattice entirely.  Under a periodic boundary a patch larger than the
    lattice would alias onto itself, which fancy indexing would silently get
    wrong, so that case is refused and the caller falls back to a full rebuild.
    """
    if periodic:
        if ph > h or pw > w:
            return None
        rows = np.arange(y0, y0 + ph) % h
        cols = np.arange(x0, x0 + pw) % w
        return rows, cols, slice(None), slice(None)
    ry0, ry1 = max(0, y0), min(h, y0 + ph)
    rx0, rx1 = max(0, x0), min(w, x0 + pw)
    if ry0 >= ry1 or rx0 >= rx1:
        return None
    return (np.arange(ry0, ry1), np.arange(rx0, rx1),
            slice(ry0 - y0, ry1 - y0), slice(rx0 - x0, rx1 - x0))
