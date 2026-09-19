"""The painter's live simulator.

A single thread owns the lattice.  Every iteration checks a small set of
boolean flags, so Pause, Freeze, and Reset land within one iteration no matter
how far behind the display is.  Rendering never happens inside that loop; the
browser pulls frames from a separate endpoint that copies the state under a
short lock, so graphics can lag arbitrarily without ever delaying a control
action.

Structure inherited from `projects/coulomb-brush/anneal_gui.py::CoulombSim`,
adapted to the painter's expanded control surface: full Params surface from
the reference, CPU backend only for this ship, stroke tracking for Undo /
Clear paint, add-uniform-charges, auto-temperature probe, and a magnifier
crop.  The GPU-only heat brush is stubbed to keep the payload shape identical
to the reference's; the CPU path always returns a note explaining that it is
GPU-only.
"""

from __future__ import annotations

import contextlib
import io
import threading
import time
from dataclasses import dataclass, field, asdict, fields as dc_fields

import numpy as np
from PIL import Image

from . import brush as B
from . import coulomb as C
from .coulomb import KB_EV


@dataclass
class Params:
    # --- source and lattice
    image: str = "blank:1920x1080"          # "blank:WxH" for an empty sheet
    resolution: int = 512                   # long edge in lattice cells
    line_density: float = 1.0
    line_blocks: bool = True
    line_threshold: float = 0.5

    # --- mobile charges
    fill: float = 0.30
    charge: float = 1.0

    # --- interaction
    strength: float = 1.0
    screening: float = 0.0
    cutoff: float = 8.0
    periodic: bool = False

    # --- short-range attraction, mobile-mobile only
    attract_depth: float = 0.0
    attract_range: float = 1.5

    # --- annealing
    temperature: float = 5000.0
    cooling: bool = False
    auto_cools: bool = True
    cooling_rate: float = 0.97              # per 1000 iterations
    schedule: str = "geometric"             # geometric | cosine
    reheat_amp: float = 1.0
    reheat_period: int = 20000
    reheat_decay: float = 0.94
    batch: int = 64
    batch_min: int = 1
    batch_decrement: int = 1
    fail_limit: int = 12
    step_size: int = 2

    # --- temperature brush (GPU only in the reference; UI controls only here)
    heat_temperature: float = 4000.0
    heat_radius: float = 40.0
    heat_hardness: float = 0.6
    heat_wake: int = 8
    heat_wake_rate: float = 0.06

    # --- charge brush
    brush_sign: float = 1.0
    brush_magnitude: float = 4.0
    brush_density: float = 1.0
    brush_thickness: float = 10.0
    brush_flow: float = 1.0
    brush_hardness: float = 0.5
    brush_penetrability: float = 1.0
    brush_coupling: float = 12.0
    brush_budget: float = 60.0
    brush_pulse: float = 2.0
    brush_ms: float = 40.0
    brush_target: str = "fixed"             # "fixed" or "mobile"

    # --- compute backend
    backend: str = "cpu"                    # cpu | gpu (gpu falls back to cpu)
    moves_per_tile: int = 64
    tile: int = 0                           # 0 = auto

    # --- display
    color_mode: str = "density"             # density | flat | gray
    show_edges: bool = True
    show_paint: bool = True
    smooth: float = 1.2
    palette: str = "vivid"                  # vivid | true
    display_px: int = 1200

    # --- runtime toggles (client-facing)
    live_view: bool = True
    frozen: bool = False
    paused: bool = False


# Keys that apply live (no rebuild).  Interaction-shape keys go in REBUILD_KEYS.
LIVE_KEYS = {
    "charge",
    "temperature", "cooling", "auto_cools", "cooling_rate", "schedule",
    "reheat_amp", "reheat_period", "reheat_decay",
    "batch", "batch_min", "batch_decrement", "fail_limit", "step_size",
    "heat_temperature", "heat_radius", "heat_hardness",
    "heat_wake", "heat_wake_rate",
    "brush_sign", "brush_magnitude", "brush_density", "brush_thickness",
    "brush_flow", "brush_hardness", "brush_penetrability", "brush_coupling",
    "brush_budget", "brush_pulse", "brush_ms", "brush_target",
    "backend", "moves_per_tile", "tile",
    "color_mode", "show_edges", "show_paint", "smooth", "palette",
    "display_px",
    "live_view", "frozen", "paused",
    "line_density",
}
REBUILD_KEYS = {"strength", "screening", "cutoff", "periodic",
                "attract_depth", "attract_range"}
# Changing any of these rebuilds the whole simulator from scratch.
BUILD_KEYS = {"image", "resolution", "fill", "line_threshold", "line_blocks"}


def params_from_dict(d) -> Params:
    """Best-effort coercion of a JSON payload into ``Params``."""
    p = Params()
    if not d:
        return p
    valid = {f.name: getattr(f, "type", None) for f in dc_fields(Params)}
    for k, v in d.items():
        if k not in valid:
            continue
        try:
            cur = getattr(p, k)
            if isinstance(cur, bool):
                setattr(p, k, bool(v) if not isinstance(v, str)
                        else v.lower() in ("1", "true", "on", "yes"))
            elif isinstance(cur, int):
                setattr(p, k, int(v))
            elif isinstance(cur, float):
                setattr(p, k, float(v))
            else:
                setattr(p, k, v)
        except (TypeError, ValueError):
            pass
    return p


# ------------------------------------------------------------ line drawings

def load_coverage(spec: str, resolution: int,
                  upload_bytes: bytes | None = None) -> np.ndarray:
    """Antialiased line coverage in [0, 1], preserving aspect ratio.

    ``spec`` is one of:

    * ``"blank:WxH"`` for an empty sheet of the given aspect (default path in
      the painter - the artist paints from nothing).
    * ``"upload:<size>"`` when ``upload_bytes`` carries an uploaded image.

    Downsampling with LANCZOS keeps drawn lines smooth so the fixed-charge
    density stays fractional rather than staircased even on a coarse lattice.
    """
    if spec.startswith("blank:") or upload_bytes is None and not spec:
        try:
            cw, ch = (int(v) for v in spec.split(":", 1)[1].lower().split("x"))
        except Exception:
            cw, ch = 1920, 1080
        scale = resolution / max(cw, ch)
        return np.zeros((max(8, int(round(ch * scale))),
                         max(8, int(round(cw * scale)))), dtype=np.float64)
    if upload_bytes is not None:
        im = Image.open(io.BytesIO(upload_bytes))
    else:
        # Should not happen in the painter (no bundled images).  Fall through
        # to a blank canvas so a missing image never crashes the app.
        scale = resolution / 1920.0
        return np.zeros((max(8, int(round(1080 * scale))),
                         max(8, int(round(1920 * scale)))), dtype=np.float64)
    if im.mode in ("RGBA", "LA"):
        bg = Image.new("RGB", im.size, (255, 255, 255))
        bg.paste(im, mask=im.split()[-1])
        im = bg
    im = im.convert("L")
    w, h = im.size
    scale = resolution / max(w, h)
    nw, nh = max(8, int(round(w * scale))), max(8, int(round(h * scale)))
    if (nw, nh) != (w, h):
        im = im.resize((nw, nh), Image.LANCZOS)
    a = np.asarray(im, dtype=np.float64) / 255.0
    cov = 1.0 - a
    if cov.max() > 0:
        cov = cov / cov.max()
    return np.clip(cov, 0.0, 1.0)


class CoulombSim:
    """CPU simulator for the painter.

    Not thread-safe by itself: every method that touches ``occ``, ``paint`` or
    ``u_edge`` from outside the sim thread must go through :meth:`priority_lock`.
    That lock is a hint-fair variant of ``threading.Lock``: waiters bump a
    counter that the annealing burst polls, so a paint or reset request that
    arrives mid-burst is served on the next iteration, not several bursts
    later.
    """

    def __init__(self, p: Params, seed: int = 0,
                 upload_bytes: bytes | None = None):
        self.p = p
        self.rng = np.random.default_rng(seed)
        self.lock = threading.Lock()
        self.lock_wait = 0
        self._paint_kernel_key = None
        self._fft_cache: dict = {}
        self.cov = load_coverage(p.image, p.resolution,
                                 upload_bytes=upload_bytes)
        self.h, self.w = self.cov.shape
        self.paint = np.zeros((self.h, self.w), dtype=np.float64)
        self.paint_blocked = np.zeros((self.h, self.w), dtype=bool)
        self.line_blocked = (self.cov > p.line_threshold) if p.line_blocks \
            else np.zeros((self.h, self.w), dtype=bool)
        self.blocked = self.line_blocked | self.paint_blocked
        self.strokes: list[dict] = []           # for Undo
        self.u_edge = np.zeros((self.h, self.w), dtype=np.float64)
        self.build_interaction()
        self.reset()

    @contextlib.contextmanager
    def priority_lock(self):
        """Take the sim lock ahead of the annealing burst.

        Python locks are not fair, so a bare acquire from a control thread can
        lose the race to the annealer several bursts running - measured half a
        second of dead brush in the reference.  Announcing the wait lets the
        burst break early in `Engine._run`.
        """
        self.lock_wait += 1
        try:
            with self.lock:
                yield
        finally:
            self.lock_wait -= 1

    # ---- interaction and paint field --------------------------------------

    def build_interaction(self):
        p = self.p
        self._fft_cache.clear()
        self.rc, self.kernel = C.build_kernel(
            p.cutoff, p.strength, p.screening,
            p.attract_depth, p.attract_range, attractive=True)
        _, self.kernel_edge = C.build_kernel(
            p.cutoff, p.strength, p.screening,
            p.attract_depth, p.attract_range, attractive=False)
        # Fixed field = line coverage * line density (repulsive kernel), plus
        # any painted fixed charge (its own soft kernel).  Any live-parameter
        # rebuild must re-add the painted contribution, or a stroke would
        # evaporate whenever the artist touches the cutoff.
        edge_charge = self.cov * p.line_density
        self.u_edge = C.convolve(edge_charge, self.kernel_edge, self.rc,
                                 periodic=p.periodic,
                                 cache=self._fft_cache, cache_key="edge")
        if self.paint.any():
            rp, K = self.paint_kernel()
            self.u_edge += C.convolve(self.paint, K, rp,
                                      periodic=p.periodic,
                                      cache=self._fft_cache,
                                      cache_key=f"paint:{rp}")

    def paint_kernel(self):
        p = self.p
        key = (p.brush_coupling, p.brush_hardness, p.strength, p.screening)
        if key != self._paint_kernel_key:
            self.rp, self.kernel_paint = B.coupling_kernel(
                p.strength, p.screening, p.brush_coupling, p.brush_hardness)
            self._paint_kernel_key = key
        return self.rp, self.kernel_paint

    # ---- lifecycle --------------------------------------------------------

    def reset(self):
        """Return to the initial state (paint cleared, particles reseeded).

        Clears painted charge, unblocks the paint-blocked sites, sprinkles
        particles at the target fill fraction over the free sites left by the
        drawing, and zeroes counters.  Interaction geometry is untouched
        because ``cutoff`` and related keys have not changed.
        """
        p = self.p
        self.paint[:] = 0.0
        self.paint_blocked[:] = False
        self.strokes.clear()
        self.blocked = self.line_blocked | self.paint_blocked
        # Recompute u_edge without the painted layer so a subsequent stroke
        # cannot subtract against a stale field.
        self.build_interaction()
        free = np.flatnonzero(~self.blocked)
        n = max(0, min(int(round(p.fill * free.size)), free.size))
        self.occ = np.zeros((self.h, self.w), dtype=bool)
        if n:
            pick = self.rng.choice(free, size=n, replace=False)
            self.occ.flat[pick] = True
        ys, xs = np.nonzero(self.occ)
        self.pos = np.stack([ys, xs], axis=1).astype(np.int64)
        self.n = len(self.pos)
        self.iteration = 0
        self.accepted = 0
        self.proposed = 0
        self.recent: list[int] = []
        self.energy = self.total_energy()
        self.energy0 = self.energy
        self._base_temp = p.temperature
        self._amp = max(p.reheat_amp, 0.0)
        self.history: list[tuple[int, float, float]] = [
            (0, p.temperature, self.energy)]
        self.t_start = time.perf_counter()
        self.moves_per_s = 0.0

    def add_uniform_charges(self):
        """Re-seed the gas uniformly at the current fill, keeping any paint.

        Unlike ``reset``, this leaves the painted charge and its blocked cells
        in place.  The reference calls this "Add uniform charges", and it is
        the natural companion to painting into an empty canvas: paint some
        fixed structure, then top up the mobile gas around it.
        """
        p = self.p
        free = np.flatnonzero(~self.blocked)
        n = max(0, min(int(round(p.fill * free.size)), free.size))
        self.occ = np.zeros((self.h, self.w), dtype=bool)
        if n:
            pick = self.rng.choice(free, size=n, replace=False)
            self.occ.flat[pick] = True
        ys, xs = np.nonzero(self.occ)
        self.pos = np.stack([ys, xs], axis=1).astype(np.int64)
        self.n = len(self.pos)
        self.energy = self.total_energy()

    def total_energy(self) -> float:
        return C.total_energy(self.occ, self.u_edge, self.kernel, self.rc,
                              periodic=self.p.periodic, charge=self.p.charge,
                              cache=self._fft_cache)

    # ---- one iteration ----------------------------------------------------

    def step(self) -> bool:
        """Propose a batch, drop collisions, accept or reject the whole batch.

        Batch Metropolis with hard-sphere blocking, as in the reference.
        Dropping one collision can un-vacate a site and invalidate another
        mover, so the collision resolution iterates to a fixed point rather
        than assuming every candidate vacates - that would let two charges
        land on one site and silently destroy a particle.
        """
        p = self.p
        if self.n == 0:
            self.iteration += 1
            self._cool()
            return False
        m = max(1, min(int(p.batch), self.n))
        idx = self.rng.choice(self.n, size=m, replace=False)
        old = self.pos[idx]
        d = self.rng.integers(-p.step_size, p.step_size + 1, size=(m, 2))
        new = old + d

        keep = (d != 0).any(axis=1)
        if p.periodic:
            new[:, 0] %= self.h
            new[:, 1] %= self.w
        else:
            keep &= ((new[:, 0] >= 0) & (new[:, 0] < self.h)
                     & (new[:, 1] >= 0) & (new[:, 1] < self.w))
        new = np.clip(new, [0, 0], [self.h - 1, self.w - 1])
        idx, old, new = idx[keep], old[keep], new[keep]

        if len(idx):
            ok = ~self.blocked[new[:, 0], new[:, 1]]
            idx, old, new = idx[ok], old[ok], new[ok]

        if len(idx):
            flat = new[:, 0] * self.w + new[:, 1]
            _, first = np.unique(flat, return_index=True)
            sel = np.zeros(len(idx), dtype=bool)
            sel[first] = True
            idx, old, new = idx[sel], old[sel], new[sel]

        while len(idx):
            staying = self.occ.copy()
            staying[old[:, 0], old[:, 1]] = False
            ok = ~staying[new[:, 0], new[:, 1]]
            if ok.all():
                break
            idx, old, new = idx[ok], old[ok], new[ok]

        self.iteration += 1
        if len(idx) == 0:
            self._cool()
            self.recent.append(0)
            if len(self.recent) > 200:
                self.recent.pop(0)
            self._history_tick()
            return False

        q = p.charge
        without = self.occ.copy()
        without[old[:, 0], old[:, 1]] = False
        e_old = self._gather(old[:, 0], old[:, 1], without.astype(float)).sum()
        e_new = self._gather(new[:, 0], new[:, 1], without.astype(float)).sum()
        e_old += self._pair_energy(old)
        e_new += self._pair_energy(new)
        de = q * q * (e_new - e_old) + q * (
            self.u_edge[new[:, 0], new[:, 1]].sum()
            - self.u_edge[old[:, 0], old[:, 1]].sum())

        self.proposed += 1
        kt = KB_EV * max(p.temperature, 1e-9)
        accept = de <= 0 or self.rng.random() < np.exp(-de / kt)
        if accept:
            self.occ[old[:, 0], old[:, 1]] = False
            self.occ[new[:, 0], new[:, 1]] = True
            self.pos[idx] = new
            self.energy += de
            self.accepted += 1
        self.recent.append(1 if accept else 0)
        if len(self.recent) > 200:
            self.recent.pop(0)
        self._history_tick()
        self._cool()
        return accept

    def _history_tick(self):
        if self.iteration % 50 != 0:
            return
        self.history.append((self.iteration, self.p.temperature, self.energy))
        if len(self.history) > 4000:
            self.history = self.history[::2]

    def _gather(self, ys: np.ndarray, xs: np.ndarray,
                occ_f: np.ndarray) -> np.ndarray:
        d = np.arange(-self.rc, self.rc + 1)
        dy = d[:, None] * np.ones((1, 2 * self.rc + 1), dtype=int)
        dx = np.ones((2 * self.rc + 1, 1), dtype=int) * d[None, :]
        ay = ys[:, None, None] + dy[None, :, :]
        ax = xs[:, None, None] + dx[None, :, :]
        if self.p.periodic:
            win = occ_f[ay % self.h, ax % self.w]
        else:
            ok = (ay >= 0) & (ay < self.h) & (ax >= 0) & (ax < self.w)
            win = occ_f[np.clip(ay, 0, self.h - 1),
                        np.clip(ax, 0, self.w - 1)] * ok
        return (win * self.kernel[None, :, :]).sum(axis=(1, 2))

    def _pair_energy(self, pts: np.ndarray) -> float:
        """Interactions among the moved particles themselves, counted once.

        The batch's own particles were not in the ``without`` occupancy when
        the neighbourhood sum was taken, so this term has to be added back or
        two movers landing next to each other would look free.
        """
        if len(pts) < 2:
            return 0.0
        dy = pts[:, 0][:, None] - pts[:, 0][None, :]
        dx = pts[:, 1][:, None] - pts[:, 1][None, :]
        if self.p.periodic:
            dy = (dy + self.h // 2) % self.h - self.h // 2
            dx = (dx + self.w // 2) % self.w - self.w // 2
        r = np.hypot(dy, dx)
        v = C.potential(r, self.p.strength, self.p.screening, self.p.cutoff,
                        self.p.attract_depth, self.p.attract_range,
                        attractive=True)
        return 0.5 * float(v.sum())

    def _cool(self):
        """Advance the temperature schedule, quoted per 1000 iterations.

        Per-iteration rates are unusable: the loop runs well over a thousand
        iterations a second, so anything like 0.999 per iteration drives T to
        zero within seconds.  The cosine schedule cools the same baseline but
        rides a damped oscillation on top of it, so defects can rearrange
        without melting structure that is already right.
        """
        p = self.p
        if not p.cooling:
            self._base_temp = p.temperature
            return
        rate = min(max(p.cooling_rate, 1e-6), 1.0) ** 0.001
        self._base_temp = max(1e-3, self._base_temp * rate)
        if p.schedule == "cosine" and p.reheat_period > 0:
            decay = min(max(p.reheat_decay, 1e-6), 1.0) ** 0.001
            self._amp = getattr(self, "_amp", max(p.reheat_amp, 0.0)) * decay
            phase = 2.0 * np.pi * (self.iteration % p.reheat_period) \
                / float(p.reheat_period)
            p.temperature = self._base_temp * (
                1.0 + self._amp * (0.5 - 0.5 * np.cos(phase)))
        else:
            p.temperature = self._base_temp

    def auto_temperature(self, target: float = 0.6, samples: int = 60) -> float:
        """Set T so a typical uphill move is accepted with probability ``target``.

        Save state, hammer at effectively infinite T, collect the uphill dE
        samples, then reset the state and derive T from ``-mean(dE)/ln(target)``.
        The batch is forced to 1 during probing so the collected dE come from
        single-particle moves - the whole-batch dE from the annealer is a
        different distribution and would give the wrong temperature.
        """
        p = self.p
        saved = (self.occ.copy(), self.pos.copy(), self.energy, self.iteration,
                 self.accepted, self.proposed, list(self.recent),
                 list(self.history))
        t_saved, batch_saved, cooling_saved = (p.temperature, p.batch,
                                               p.cooling)
        p.temperature = 1e12
        p.batch = 1
        p.cooling = False
        ups = []
        for _ in range(samples):
            e0 = self.energy
            self.step()
            de = self.energy - e0
            if de > 0:
                ups.append(de)
        (self.occ, self.pos, self.energy, self.iteration, self.accepted,
         self.proposed, self.recent, self.history) = saved
        p.temperature = t_saved
        p.batch = batch_saved
        p.cooling = cooling_saved
        if not ups:
            return t_saved
        t_new = float(-np.mean(ups) / (KB_EV * np.log(target)))
        p.temperature = t_new
        self._base_temp = t_new
        if p.auto_cools:
            p.cooling = True
        return t_new

    # ---- painting ---------------------------------------------------------

    def paint_stroke(self, points, br: B.Brush, first: bool = False,
                     last: bool = False) -> dict:
        """Lay one packet of a stroke onto the canvas.

        Every step here is confined to the stroke's own bounding box, so a
        mouse move on a 1024 lattice costs milliseconds rather than tens.
        The fixed-charge target updates ``u_edge`` incrementally, exactly the
        term the global FFT would have produced; the mobile-charge target
        rolls a per-cell add-or-remove decision weighted by the same radial
        coverage the fixed target uses.
        """
        res = B.stroke_patch(points, br, first=first)
        if res is None:
            return {"painted": 0}
        y0, x0, dq, cov = res
        if br.target == "mobile":
            return self._paint_mobile(y0, x0, dq, cov, br, first, last)
        return self._paint_fixed(y0, x0, dq, cov, br, first, last)

    def _paint_fixed(self, y0, x0, dq, cov, br: B.Brush,
                     first: bool, last: bool) -> dict:
        p = self.p
        place = B.patch_index(y0, x0, dq.shape[0], dq.shape[1],
                              self.h, self.w, p.periodic)
        if place is None:
            return {"painted": 0}
        rows, cols, sy, sx = place
        ix = np.ix_(rows, cols)
        dq_sub = dq[sy, sx]
        cov_sub = cov[sy, sx]
        self.paint[ix] += dq_sub

        # Track for Undo: one stroke record per contiguous drag, appended to
        # by every packet until the pointer lifts.
        if first or not self.strokes or self.strokes[-1].get("closed"):
            self.strokes.append({"kind": "fixed", "u": [], "q": [], "b": [],
                                 "closed": False})
            if len(self.strokes) > 32:
                self.strokes.pop(0)
        rec = self.strokes[-1]
        rec["q"].append((rows, cols, dq_sub.copy()))

        rp, K = self.paint_kernel()
        du = B.potential_patch(dq, K, rp)
        uplace = B.patch_index(y0 - rp, x0 - rp, du.shape[0], du.shape[1],
                               self.h, self.w, p.periodic)
        if uplace is None:
            self.build_interaction()
            self.energy = self.total_energy()
        else:
            urows, ucols, usy, usx = uplace
            uix = np.ix_(urows, ucols)
            du_sub = du[usy, usx]
            self.u_edge[uix] += du_sub
            self.energy += p.charge * float((self.occ[uix] * du_sub).sum())
            rec["u"].append((urows, ucols, du_sub.copy()))

        # Penetrability: coverage above (1 - penetrability) closes the site.
        # penetrability = 1 never blocks, 0 blocks everywhere the brush landed.
        # Sites already holding a particle are closed too; the mover test only
        # looks at the destination, so the particle can leave but nothing may
        # enter - charge is conserved either way.
        blocked_new = 0
        if br.penetrability < 1.0:
            thresh = 1.0 - br.penetrability
            newly = (cov_sub > thresh) & ~self.blocked[ix]
            if newly.any():
                self.paint_blocked[ix] = self.paint_blocked[ix] | newly
                self.blocked[ix] = self.blocked[ix] | newly
                rec["b"].append((rows, cols, newly.copy()))
                blocked_new = int(newly.sum())

        if last:
            rec["closed"] = True
            self.energy = self.total_energy()
        return {"painted": int((cov_sub > 0).sum()),
                "charge": float(dq_sub.sum()),
                "blocked_new": blocked_new, "target": "fixed"}

    def _paint_mobile(self, y0, x0, dq, cov, br: B.Brush,
                      first: bool, last: bool) -> dict:
        """Stamp mobile particles.

        Coverage is treated as a per-cell probability that this stamp adds
        (sign > 0) or removes (sign < 0) a particle at that cell.  Cells that
        are blocked, already occupied (for adds), or already empty (for
        removes) are ignored.  Charge conservation is intentionally NOT
        enforced here - the painter is a creation tool, not a physics rule.
        """
        p = self.p
        place = B.patch_index(y0, x0, dq.shape[0], dq.shape[1],
                              self.h, self.w, p.periodic)
        if place is None:
            return {"painted": 0}
        rows, cols, sy, sx = place
        ix = np.ix_(rows, cols)
        cov_sub = cov[sy, sx]
        occ_sub = self.occ[ix]
        blk_sub = self.blocked[ix]

        if first or not self.strokes or self.strokes[-1].get("closed"):
            self.strokes.append({"kind": "mobile", "adds": [], "rems": [],
                                 "closed": False})
            if len(self.strokes) > 32:
                self.strokes.pop(0)
        rec = self.strokes[-1]

        prob = np.clip(cov_sub, 0.0, 1.0)
        roll = self.rng.random(prob.shape) < prob
        added = removed = 0
        if br.sign > 0:
            can_add = roll & ~occ_sub & ~blk_sub
            if can_add.any():
                self.occ[ix] = occ_sub | can_add
                added = int(can_add.sum())
                rec["adds"].append((rows, cols, can_add.copy()))
        else:
            can_remove = roll & occ_sub
            if can_remove.any():
                self.occ[ix] = occ_sub & ~can_remove
                removed = int(can_remove.sum())
                rec["rems"].append((rows, cols, can_remove.copy()))

        if added or removed:
            ys, xs = np.nonzero(self.occ)
            self.pos = np.stack([ys, xs], axis=1).astype(np.int64)
            self.n = len(self.pos)
            self.energy = self.total_energy()

        if last:
            rec["closed"] = True
        return {"painted": int((cov_sub > 0).sum()),
                "added": added, "removed": removed, "target": "mobile"}

    def undo_stroke(self) -> bool:
        """Remove the last stroke's effect: charge, blocks, mobile changes."""
        if not self.strokes:
            return False
        st = self.strokes.pop()
        if st["kind"] == "fixed":
            for rows, cols, sub in st["u"]:
                self.u_edge[np.ix_(rows, cols)] -= sub
            for rows, cols, sub in st["q"]:
                self.paint[np.ix_(rows, cols)] -= sub
            self.paint[np.abs(self.paint) < 1e-9] = 0.0
            if st["b"]:
                for rows, cols, m in st["b"]:
                    ix = np.ix_(rows, cols)
                    self.paint_blocked[ix] = self.paint_blocked[ix] & ~m
                self.blocked = self.line_blocked | self.paint_blocked
            self.energy = self.total_energy()
        else:
            for rows, cols, m in st.get("adds", []):
                ix = np.ix_(rows, cols)
                self.occ[ix] = self.occ[ix] & ~m
            for rows, cols, m in st.get("rems", []):
                ix = np.ix_(rows, cols)
                # do NOT restore into blocked sites - undo cannot violate the
                # blocking invariant even if the block was added by a later,
                # separate stroke
                allowed = m & ~self.blocked[ix]
                self.occ[ix] = self.occ[ix] | allowed
            ys, xs = np.nonzero(self.occ)
            self.pos = np.stack([ys, xs], axis=1).astype(np.int64)
            self.n = len(self.pos)
            self.energy = self.total_energy()
        return True

    def clear_paint(self):
        """Drop the entire painted layer and rebuild the fixed field."""
        self.paint[:] = 0.0
        self.paint_blocked[:] = False
        self.strokes.clear()
        self.blocked = self.line_blocked | self.paint_blocked
        self.build_interaction()
        self.energy = self.total_energy()

    # ---- magnifier crop ----------------------------------------------------

    def crop_occ(self, cy: int, cx: int, span: int) -> tuple[np.ndarray,
                                                              np.ndarray | None]:
        """Return a (span, span) window of occupancy and paint about (cy, cx).

        Used by the magnifier endpoint.  Outside a hard-wall lattice the
        window is padded with the empty-and-blocked treatment the physics
        already uses, so the crop is a faithful picture of what the sim
        thinks is there.
        """
        s = max(4, int(span))
        y0 = int(cy) - s // 2
        x0 = int(cx) - s // 2
        if self.p.periodic:
            rr = np.arange(y0, y0 + s) % self.h
            cc = np.arange(x0, x0 + s) % self.w
            occ = self.occ[np.ix_(rr, cc)]
            paint = self.paint[np.ix_(rr, cc)] if self.paint.any() else None
        else:
            rows = np.arange(y0, y0 + s)
            cols = np.arange(x0, x0 + s)
            occ = np.zeros((s, s), dtype=bool)
            vy = (rows >= 0) & (rows < self.h)
            vx = (cols >= 0) & (cols < self.w)
            if vy.any() and vx.any():
                ry, rx = rows[vy], cols[vx]
                occ[np.ix_(vy, vx)] = self.occ[np.ix_(ry, rx)]
            paint = None
            if self.paint.any():
                paint = np.zeros((s, s), dtype=np.float64)
                if vy.any() and vx.any():
                    paint[np.ix_(vy, vx)] = self.paint[np.ix_(
                        rows[vy], cols[vx])]
        return occ, paint

    # ---- reporting --------------------------------------------------------

    def stats(self) -> dict:
        p = self.p
        rate = float(np.mean(self.recent)) if self.recent else 0.0
        elapsed = max(1e-6, time.perf_counter() - self.t_start)
        return {
            "iteration": self.iteration,
            "particles": int(self.n),
            "temperature": p.temperature,
            "kT_eV": KB_EV * p.temperature,
            "energy": self.energy,
            "energy0": self.energy0,
            "accept_rate": rate,
            "batch": int(p.batch),
            "lattice": [int(self.h), int(self.w)],
            "fill": float(self.occ.mean()),
            "moves_per_s": self.proposed / elapsed,
            "paint_cells": int(np.count_nonzero(self.paint)),
            "paint_charge": float(self.paint.sum()),
            "strokes": len(self.strokes),
            "paused": bool(p.paused),
            "frozen": bool(p.frozen),
            "live_view": bool(p.live_view),
            "cooling": bool(p.cooling),
            "backend": "cpu",
            "backend_note": ("GPU backend is not wired up in the MVP; "
                             "running on CPU."),
        }
