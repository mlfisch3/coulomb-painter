"""Interaction potential and lattice convolutions for the Coulomb gas.

Two-dimensional gas of unit charges on a lattice.  Mobile charges interact
through a short-range attraction plus long-range Coulomb repulsion (optionally
screened, always cut at a finite range so the neighbourhood sum stays cheap):

    V(r) = strength * e^{-r/lambda} / r  -  depth * e^{-r/a}

The attractive well applies only between mobile charges - painted fixed charge
is purely repulsive, so a "-" brush pulls the gas toward itself only through
Coulomb, without binding it.
"""

from __future__ import annotations

import numpy as np


KB_EV = 8.617333262e-5     # Boltzmann constant, eV/K


def potential(r: np.ndarray, strength: float, screening: float, cutoff: float,
              attract_depth: float = 0.0, attract_range: float = 0.0,
              attractive: bool = True) -> np.ndarray:
    """Interaction potential at distance ``r``, truncated at ``cutoff``.

    ``attractive`` gates the well.  Fixed charges pass ``False`` so they never
    bind particles to themselves, which is a design choice inherited from the
    reference prototype - it keeps the well meaning one thing.
    """
    out = np.zeros_like(r, dtype=np.float64)
    m = (r > 0) & (r <= cutoff)
    rr = r[m]
    v = strength / rr
    if screening > 0:
        v = v * np.exp(-rr / screening)
    if attractive and attract_depth != 0 and attract_range > 0:
        v = v - attract_depth * np.exp(-rr / attract_range)
    out[m] = v
    return out


def build_kernel(cutoff: float, strength: float, screening: float,
                 attract_depth: float = 0.0, attract_range: float = 0.0,
                 attractive: bool = True):
    """Precomputed neighbourhood kernel for one particle."""
    rc = max(1, int(np.ceil(cutoff)))
    d = np.arange(-rc, rc + 1)
    r = np.hypot(d[:, None], d[None, :]).astype(float)
    K = potential(r, strength, screening, cutoff, attract_depth, attract_range,
                  attractive)
    return rc, K


def convolve(field: np.ndarray, kernel: np.ndarray, rc: int,
             periodic: bool = False, cache: dict | None = None,
             cache_key=None) -> np.ndarray:
    """FFT convolution under the current boundary condition.

    The kernel spectrum is cached because it does not change between calls; on
    a big lattice, recomputing it every time roughly triples the cost of the
    field build.
    """
    h, w = field.shape
    if periodic:
        H, W, off = h, w, 0
        src = field
    else:
        H, W, off = h + 2 * rc, w + 2 * rc, rc
        src = np.zeros((H, W), dtype=np.float64)
        src[rc:rc + h, rc:rc + w] = field

    fk = None
    if cache is not None and cache_key is not None:
        fk = cache.get((cache_key, H, W))
    if fk is None:
        ker = np.zeros((H, W))
        ker[np.ix_(np.arange(-rc, rc + 1) % H,
                   np.arange(-rc, rc + 1) % W)] = kernel
        fk = np.fft.rfft2(ker, (H, W))
        if cache is not None and cache_key is not None:
            cache[(cache_key, H, W)] = fk
    out = np.fft.irfft2(np.fft.rfft2(src, (H, W)) * fk, (H, W))
    return out[off:off + h, off:off + w]


def total_energy(occ: np.ndarray, u_edge: np.ndarray, kernel: np.ndarray,
                 rc: int, periodic: bool, charge: float,
                 cache: dict | None = None) -> float:
    """Full lattice energy: 1/2 sum over pairs plus q sum over fixed field."""
    q = float(charge)
    f = occ.astype(np.float64)
    conv = convolve(f, kernel, rc, periodic=periodic, cache=cache,
                    cache_key="mm")
    e_mm = 0.5 * q * q * float((f * conv).sum())
    e_edge = q * float((f * u_edge).sum())
    return e_mm + e_edge
