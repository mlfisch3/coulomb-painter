"""Colour a lattice frame for the browser.

Rendering must never happen inside the simulation lock for longer than it
takes to copy the arrays out.  All the real image work lives here, on the
caller's thread, against those copies.

Options mirror the reference: ``palette`` (vivid, best looking, or true, which
rises monotonically in luminance so a flattened-to-grey screenshot preserves
density ordering); ``color_mode`` in {density, flat, gray}; ``smooth`` for the
reconstruction filter; ``show_edges`` and ``show_paint`` for the two overlays.
"""

from __future__ import annotations

import io

import numpy as np
from PIL import Image


BG = np.array([10, 14, 28], dtype=np.float32)
EDGE_COL = np.array([64, 224, 208], dtype=np.float32)
PAINT_POS = np.array([255, 104, 84], dtype=np.float32)
PAINT_NEG = np.array([96, 168, 255], dtype=np.float32)
PARTICLE_COL = np.array([255, 214, 140], dtype=np.float32)


VIVID_STOPS = [(0.00, (24, 34, 66)), (0.25, (34, 96, 158)),
               (0.50, (68, 178, 186)), (0.72, (245, 197, 96)),
               (0.90, (238, 122, 71)), (1.00, (252, 246, 226))]

TRUE_STOPS = [(0.00, (16, 24, 52)), (0.22, (30, 82, 140)),
              (0.44, (52, 148, 176)), (0.62, (118, 196, 172)),
              (0.80, (226, 198, 124)), (0.92, (246, 216, 154)),
              (1.00, (255, 248, 236))]


def _build_ramp(stops):
    xs = np.linspace(0, 1, 256)
    out = np.zeros((256, 3), np.float32)
    for i, x in enumerate(xs):
        for (a, ca), (b, cb) in zip(stops[:-1], stops[1:]):
            if a <= x <= b:
                t = (x - a) / max(b - a, 1e-9)
                out[i] = np.array(ca) * (1 - t) + np.array(cb) * t
                break
    return out


RAMPS = {"vivid": _build_ramp(VIVID_STOPS), "true": _build_ramp(TRUE_STOPS)}


def _block_mean(a: np.ndarray, k: int) -> np.ndarray:
    if k <= 1:
        if a.dtype == np.bool_:
            return a.astype(np.float32)
        return a.astype(np.float32, copy=False)
    h, w = a.shape
    hh, ww = (h // k) * k, (w // k) * k
    v = a[:hh, :ww].reshape(h // k, k, w // k, k)
    if a.dtype == np.bool_:
        return v.sum(axis=(1, 3), dtype=np.uint16).astype(np.float32) / (k * k)
    return v.mean(axis=(1, 3), dtype=np.float32)


def _colour(field: np.ndarray, color_mode: str, palette: str) -> np.ndarray:
    """Turn a normalised density field into a uint8 RGB image."""
    hi = max(float(field.max()), 1e-6)
    idx = np.clip(field * (255.0 / hi), 0, 255).astype(np.uint8)
    lo = np.linspace(0.0, 1.0, 256, dtype=np.float32)
    if color_mode == "gray":
        g = (18.0 + lo * 237.0).astype(np.uint8)
        lut = np.stack([g, g, g], axis=1)
    else:
        base = RAMPS.get(palette, RAMPS["vivid"]) if color_mode == "density" \
            else np.broadcast_to(PARTICLE_COL, (256, 3)).astype(np.float32)
        lut = (BG[None, :] * (1.0 - lo[:, None])
               + base * lo[:, None]).astype(np.uint8)
    return lut[idx]


def render_png(occ: np.ndarray, paint: np.ndarray | None = None,
               cov: np.ndarray | None = None,
               max_px: int = 900, smooth: float = 1.0,
               color_mode: str = "density", palette: str = "vivid",
               show_edges: bool = True, show_paint: bool = True) -> bytes:
    """Encode a frame as PNG.

    Downsample first, then colour.  Building a full-resolution float RGB and
    shrinking it afterwards costs an order of magnitude more work on a big
    lattice and gives the same answer up to floating-point rounding.
    """
    h, w = occ.shape
    k = max(1, int(np.ceil(max(h, w) / float(max_px))))
    rho = _block_mean(occ, k)
    if smooth > 0:
        from scipy.ndimage import gaussian_filter
        field = gaussian_filter(rho, sigma=float(smooth))
    elif color_mode == "density":
        from scipy.ndimage import uniform_filter
        field = uniform_filter(rho, size=3 if k > 1 else 7)
    else:
        field = rho
    img = _colour(field, color_mode, palette)

    if show_edges and cov is not None:
        cv = _block_mean(np.clip(cov, 0, 1).astype(np.float32), k)
        m = cv > 0.004
        if m.any():
            if color_mode == "gray":
                # brighten the mask a little for grayscale so lines don't
                # disappear against a dense field
                g = (cv[m] * 200 + 40).clip(0, 255).astype(np.uint8)
                img[m] = np.stack([g, g, g], axis=1)
            else:
                a = np.clip(cv[m], 0, 1)[:, None]
                img[m] = ((1.0 - a) * img[m].astype(np.float32)
                          + a * EDGE_COL[None, :]).clip(0, 255).astype(np.uint8)

    if show_paint and paint is not None and float(np.abs(paint).max()) > 1e-9:
        paint_small = _block_mean(paint.astype(np.float32), k)
        pos = np.clip(paint_small, 0.0, None)
        neg = np.clip(-paint_small, 0.0, None)
        m = max(float(pos.max()), float(neg.max()), 1e-6)
        ap = (pos / m)[..., None]
        an = (neg / m)[..., None]
        img = (img.astype(np.float32) * (1.0 - ap - an)
               + PAINT_POS[None, None, :] * ap
               + PAINT_NEG[None, None, :] * an).clip(0, 255).astype(np.uint8)

    out = Image.fromarray(img, mode="RGB")
    buf = io.BytesIO()
    out.save(buf, format="PNG", compress_level=1)
    return buf.getvalue()


def render_zoom(occ: np.ndarray, paint: np.ndarray | None,
                out: int = 560,
                color_mode: str = "density", palette: str = "vivid",
                show_paint: bool = True) -> bytes:
    """1:1 crop upsampled to ``out`` px with nearest-neighbour."""
    field = occ.astype(np.float32)
    img = _colour(field, color_mode, palette)
    if show_paint and paint is not None and float(np.abs(paint).max()) > 1e-9:
        pos = np.clip(paint, 0.0, None)
        neg = np.clip(-paint, 0.0, None)
        m = max(float(pos.max()), float(neg.max()), 1e-6)
        ap = (pos / m)[..., None]
        an = (neg / m)[..., None]
        img = (img.astype(np.float32) * (1.0 - ap - an)
               + PAINT_POS[None, None, :] * ap
               + PAINT_NEG[None, None, :] * an).clip(0, 255).astype(np.uint8)
    im = Image.fromarray(img, mode="RGB")
    if out and out != im.size[0]:
        im = im.resize((out, out), Image.NEAREST)
    buf = io.BytesIO()
    im.save(buf, format="PNG", compress_level=1)
    return buf.getvalue()
