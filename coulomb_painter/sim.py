"""The painter's live simulator.

A single thread owns the lattice.  Every iteration checks a small set of
boolean flags, so Pause, Freeze, and Reset land within one iteration no matter
how far behind the display is.  Rendering never happens inside that loop; the
browser pulls frames from a separate endpoint that copies the state under a
short lock, so graphics can lag arbitrarily without ever delaying a control
action.

Structure inherited from `projects/coulomb-brush/anneal_gui.py::CoulombSim`,
trimmed to the painter MVP: CPU backend only, no image ingestion, mobile-brush
support alongside the fixed-charge brush.
"""

from __future__ import annotations

import contextlib
import threading
import time
from dataclasses import dataclass, field, asdict, fields as dc_fields

import numpy as np

from . import brush as B
from . import coulomb as C
from .coulomb import KB_EV


@dataclass
class Params:
    # lattice
    resolution: int = 256           # long edge in lattice cells
    fill: float = 0.30              # initial mobile fill fraction

    # mobile-charge properties
    charge: float = 1.0

    # interaction
    strength: float = 1.0
    screening: float = 0.0          # exp(-r/screening); 0 disables
    cutoff: float = 8.0
    periodic: bool = False

    # short-range attraction between mobile charges only
    attract_depth: float = 0.0
    attract_range: float = 1.5

    # annealing schedule
    temperature: float = 4000.0
    cooling: bool = False
    cooling_rate: float = 0.97      # per 1000 iterations
    batch: int = 48
    step_size: int = 2

    # brush defaults
    brush_sign: float = 1.0
    brush_magnitude: float = 3.0
    brush_density: float = 1.0
    brush_thickness: float = 12.0
    brush_flow: float = 1.0
    brush_hardness: float = 0.5
    brush_coupling: float = 10.0
    brush_target: str = "fixed"     # "fixed" or "mobile"

    # display / runtime state
    live_view: bool = True          # render frames on request
    frozen: bool = False            # Freeze mode: annealer does not step
    paused: bool = False


LIVE_KEYS = {"temperature", "cooling", "cooling_rate", "batch", "step_size",
             "charge",
             "brush_sign", "brush_magnitude", "brush_density",
             "brush_thickness", "brush_flow", "brush_hardness",
             "brush_coupling", "brush_target",
             "live_view", "frozen", "paused"}
REBUILD_KEYS = {"strength", "screening", "cutoff", "periodic",
                "attract_depth", "attract_range"}


def params_from_dict(d) -> Params:
    """Best-effort coercion of a JSON payload into ``Params``."""
    p = Params()
    if not d:
        return p
    valid = {f.name: f.type for f in dc_fields(Params)}
    for k, v in d.items():
        if k not in valid:
            continue
        try:
            cur = getattr(p, k)
            if isinstance(cur, bool):
                setattr(p, k, bool(v))
            elif isinstance(cur, int):
                setattr(p, k, int(v))
            elif isinstance(cur, float):
                setattr(p, k, float(v))
            else:
                setattr(p, k, v)
        except (TypeError, ValueError):
            pass
    return p


class CoulombSim:
    """CPU simulator for the painter.

    Not thread-safe by itself: every method that touches ``occ``, ``paint`` or
    ``u_edge`` from outside the sim thread must go through :meth:`priority_lock`.
    That lock is a hint-fair variant of ``threading.Lock``: waiters bump a
    counter that the annealing burst polls, so a paint or reset request that
    arrives mid-burst is served on the next iteration, not several bursts
    later.
    """

    def __init__(self, p: Params, seed: int = 0):
        self.p = p
        self.rng = np.random.default_rng(seed)
        self.lock = threading.Lock()
        self.lock_wait = 0
        self._paint_kernel_key = None
        self._fft_cache: dict = {}
        # canvas shape from resolution: square for simplicity of the MVP
        self.h = self.w = int(max(32, p.resolution))
        self.paint = np.zeros((self.h, self.w), dtype=np.float64)
        self.u_edge = np.zeros((self.h, self.w), dtype=np.float64)
        self.blocked = np.zeros((self.h, self.w), dtype=bool)
        self.build_interaction()
        self.reset()

    @contextlib.contextmanager
    def priority_lock(self):
        """Take the sim lock ahead of the annealing burst.

        Python locks are not fair, so a bare acquire from a control thread can
        lose the race to the annealer several bursts running.  Announcing the
        wait lets the burst break early - see :meth:`_run_thread`.
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
        # painted fixed charge is the only source of a non-zero field in the
        # MVP: there is no drawing to load.  Any rebuild triggered by a knob
        # change has to put the painted contribution back or a stroke would
        # evaporate whenever the artist touches the cutoff.
        self.u_edge = np.zeros((self.h, self.w), dtype=np.float64)
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
        """Return to the initial New-Canvas state.

        Clears painted charge, unblocks every site, sprinkles particles at the
        target fill fraction, and zeroes counters.  Interaction geometry is
        untouched because ``cutoff`` and related keys have not changed.
        """
        p = self.p
        self.paint[:] = 0.0
        self.blocked[:] = False
        self._paint_kernel_key = None
        self._fft_cache.clear()
        self.u_edge[:] = 0.0
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
        self.t_start = time.perf_counter()
        self.moves_per_s = 0.0

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
        self._cool()
        return accept

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
        zero within seconds.
        """
        p = self.p
        if not p.cooling:
            self._base_temp = p.temperature
            return
        rate = min(max(p.cooling_rate, 1e-6), 1.0) ** 0.001
        self._base_temp = max(1e-3, self._base_temp * rate)
        p.temperature = self._base_temp

    # ---- painting ---------------------------------------------------------

    def paint_stroke(self, points, br: B.Brush, first: bool = False,
                     last: bool = False) -> dict:
        """Lay one packet of a stroke onto the canvas.

        Every step here is confined to the stroke's own bounding box, so a
        mouse move on a 1024 lattice costs milliseconds rather than tens.
        The fixed-charge target updates ``u_edge`` incrementally, exactly the
        term the global FFT would have produced.  The mobile-charge target
        rolls a per-cell add-or-remove decision weighted by the same radial
        coverage the fixed target uses.
        """
        p = self.p
        res = B.stroke_patch(points, br, first=first)
        if res is None:
            return {"painted": 0}
        y0, x0, dq, cov = res
        if br.target == "mobile":
            return self._paint_mobile(y0, x0, dq, cov, br, last)
        return self._paint_fixed(y0, x0, dq, cov, br, last)

    def _paint_fixed(self, y0, x0, dq, cov, br: B.Brush, last: bool) -> dict:
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

        rp, K = self.paint_kernel()
        du = B.potential_patch(dq, K, rp)
        uplace = B.patch_index(y0 - rp, x0 - rp, du.shape[0], du.shape[1],
                               self.h, self.w, p.periodic)
        if uplace is None:
            # Only reachable when the influence patch wraps onto itself on a
            # small torus; correctness first, take the full rebuild.
            self.build_interaction()
            self.energy = self.total_energy()
        else:
            urows, ucols, usy, usx = uplace
            uix = np.ix_(urows, ucols)
            du_sub = du[usy, usx]
            self.u_edge[uix] += du_sub
            # E carries q * sum(occ * u_edge); occ did not change, so the
            # whole effect of the new fixed charge is exactly q * sum(occ * du)
            # over the patch.
            self.energy += p.charge * float((self.occ[uix] * du_sub).sum())

        painted = int((cov_sub > 0).sum())
        if last:
            self.energy = self.total_energy()
        return {"painted": painted, "charge": float(dq_sub.sum()),
                "target": "fixed"}

    def _paint_mobile(self, y0, x0, dq, cov, br: B.Brush, last: bool) -> dict:
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

        prob = np.clip(cov_sub, 0.0, 1.0)
        roll = self.rng.random(prob.shape) < prob
        added = removed = 0
        if br.sign > 0:
            can_add = roll & ~occ_sub & ~blk_sub
            if can_add.any():
                new_occ = occ_sub | can_add
                self.occ[ix] = new_occ
                added = int(can_add.sum())
        else:
            can_remove = roll & occ_sub
            if can_remove.any():
                new_occ = occ_sub & ~can_remove
                self.occ[ix] = new_occ
                removed = int(can_remove.sum())

        if added or removed:
            ys, xs = np.nonzero(self.occ)
            self.pos = np.stack([ys, xs], axis=1).astype(np.int64)
            self.n = len(self.pos)
            self.energy = self.total_energy()

        return {"painted": int((cov_sub > 0).sum()),
                "added": added, "removed": removed, "target": "mobile"}

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
            "paused": bool(p.paused),
            "frozen": bool(p.frozen),
            "live_view": bool(p.live_view),
            "cooling": bool(p.cooling),
        }
