#!/usr/bin/env python3
"""Python reference physics for coulomb-validate.

Runs the same scenario the Rust binary is validating and prints a JSON
`RunResult` to stdout that the parent process compares. It imports
`coulomb.py` and `brush.py` from `projects/coulomb-brush/` as the task
requires, but adds screening, the short-range attraction well, batch
decrement-on-dead-proposals, the u_edge FFT construction, and the
incremental brush patch update on top of them, because those are the
physics the Rust core actually ports and none of them are present in
`coulomb.py` in isolation. The added pieces mirror what `anneal_gui.py`
does on its CPU path.

The reference is deliberately its own module rather than a subclass of
`CoulombAnnealer` so the port-vs-Python comparison is a comparison of
two implementations of the SAME physics, not of two different Python
class hierarchies.
"""

from __future__ import annotations

import json
import math
import os
import sys
from pathlib import Path

import numpy as np


HERE = Path(__file__).resolve().parent
COULOMB_BRUSH = (HERE / ".." / ".." / "reference").resolve()

# The reference brush code sits in a fleet-managed sibling worktree,
# not in this repo. The wrapper script `fetch_reference.py` writes its
# path here. Fall back to the standard fleet location when unset.
DEFAULT_REFERENCE = Path(
    os.environ.get(
        "COULOMB_REFERENCE",
        "/home/drd/PROJECT/CLAUDE/FM-COULOMB/fm-coulomb/projects/coulomb-brush",
    )
)


def _import_reference():
    """Import brush and coulomb from projects/coulomb-brush/."""
    for candidate in (COULOMB_BRUSH, DEFAULT_REFERENCE):
        if candidate.exists() and (candidate / "coulomb.py").exists():
            sys.path.insert(0, str(candidate))
            import coulomb  # noqa: F401 - required by task
            import brush as B
            return coulomb, B
    raise SystemExit(
        "cannot find coulomb-brush reference; set COULOMB_REFERENCE"
    )


COULOMB, BRUSH_MOD = _import_reference()
KB_EV = COULOMB.KB_EV


def potential(r, params, attractive):
    """V(r) = (strength/r) * exp(-r/screening) - depth*exp(-r/range).

    Matches `CoulombSim.potential` from anneal_gui.py, kept in a plain
    function so it can vectorise on numpy arrays and scalars alike.
    """
    r = np.asarray(r, dtype=np.float64)
    out = np.zeros_like(r, dtype=np.float64)
    m = (r > 0) & (r <= params["cutoff"])
    rr = r[m]
    v = params["strength"] / rr
    if params["screening"] > 0:
        v = v * np.exp(-rr / params["screening"])
    if (
        attractive
        and params["attract_depth"] != 0.0
        and params["attract_range"] > 0.0
    ):
        v = v - params["attract_depth"] * np.exp(
            -rr / params["attract_range"]
        )
    out[m] = v
    return out


def build_kernel(params, attractive):
    rc = max(1, int(math.ceil(params["cutoff"])))
    d = np.arange(-rc, rc + 1)
    r = np.hypot(d[:, None], d[None, :])
    return rc, potential(r, params, attractive)


def convolve(field, kernel, rc, periodic):
    h, w = field.shape
    if periodic:
        H, W, off = h, w, 0
        src = field
    else:
        H, W, off = h + 2 * rc, w + 2 * rc, rc
        src = np.zeros((H, W))
        src[rc : rc + h, rc : rc + w] = field
    ker = np.zeros((H, W))
    ker[
        np.ix_(np.arange(-rc, rc + 1) % H, np.arange(-rc, rc + 1) % W)
    ] = kernel
    out = np.fft.irfft2(
        np.fft.rfft2(src, (H, W)) * np.fft.rfft2(ker, (H, W)),
        (H, W),
    )
    return out[off : off + h, off : off + w]


class PySim:
    """Reference batch-Metropolis, mirroring the Rust `Sim` step for step."""

    def __init__(self, sc):
        self.sc = sc
        self.params = {
            "cutoff": sc["cutoff"],
            "screening": sc.get("screening", 0.0),
            "strength": sc.get("strength", 1.0),
            "attract_depth": sc.get("attract_depth", 0.0),
            "attract_range": sc.get("attract_range", 1.5),
            "charge": sc.get("charge", 1.0),
        }
        self.h = sc["h"]
        self.w = sc["w"]
        self.periodic = sc.get("periodic", False)
        self.rc, self.kernel = build_kernel(self.params, attractive=True)
        _, self.kernel_edge = build_kernel(self.params, attractive=False)
        self.rng = np.random.default_rng(sc["seed"])

        self.cov = np.zeros((self.h, self.w), dtype=np.float64)
        self.paint = np.zeros((self.h, self.w), dtype=np.float64)
        self.blocked = np.zeros((self.h, self.w), dtype=bool)
        self.u_edge = convolve(
            self.cov, self.kernel_edge, self.rc, self.periodic
        )

        # Deterministic strided placement, identical to Rust's
        # `Sim::new_blank`. Same initial state removes O(sqrt(N)*kT)
        # initial-condition drift from the equilibrium comparison.
        total = self.h * self.w
        n = min(sc["particles"], total)
        self.occ = np.zeros(total, dtype=bool)
        for k in range(n):
            idx = (k * total) // n
            self.occ[idx] = True
        self.occ = self.occ.reshape(self.h, self.w)
        ys, xs = np.nonzero(self.occ)
        self.pos = np.stack([ys, xs], axis=1).astype(np.int64)
        self.n = int(self.pos.shape[0])
        self.temperature = 0.0
        self.batch = int(sc.get("batch", 64))
        self.batch_min = int(sc.get("batch_min", 1))
        self.batch_decrement = int(sc.get("batch_decrement", 1))
        self.fail_limit = int(sc.get("fail_limit", 12))
        self.step_size = int(sc.get("step_size", 2))
        self.fail_streak = 0

        self.iteration = 0
        self.proposed = 0
        self.accepted = 0
        self.energy = self.total_energy()

    # ------------------------------------------------------ energy

    def total_energy(self):
        q = self.params["charge"]
        occ = self.occ.astype(float)
        conv = convolve(occ, self.kernel, self.rc, self.periodic)
        e_mm = 0.5 * q * q * float((occ * conv).sum())
        e_edge = q * float((occ * self.u_edge).sum())
        return e_mm + e_edge

    def _gather(self, ys, xs, occ):
        """Neighbourhood energy sums; mirrors anneal_gui._gather."""
        rc = self.rc
        d = np.arange(-rc, rc + 1)
        dy = d[:, None] * np.ones((1, 2 * rc + 1), dtype=int)
        dx = np.ones((2 * rc + 1, 1), dtype=int) * d[None, :]
        ay = ys[:, None, None] + dy[None, :, :]
        ax = xs[:, None, None] + dx[None, :, :]
        if self.periodic:
            win = occ[ay % self.h, ax % self.w]
        else:
            ok = (ay >= 0) & (ay < self.h) & (ax >= 0) & (ax < self.w)
            win = (
                occ[
                    np.clip(ay, 0, self.h - 1),
                    np.clip(ax, 0, self.w - 1),
                ]
                * ok
            )
        return (win * self.kernel[None, :, :]).sum(axis=(1, 2))

    def _pair_energy(self, pts):
        if len(pts) < 2:
            return 0.0
        dy = pts[:, 0][:, None] - pts[:, 0][None, :]
        dx = pts[:, 1][:, None] - pts[:, 1][None, :]
        if self.periodic:
            dy = (dy + self.h // 2) % self.h - self.h // 2
            dx = (dx + self.w // 2) % self.w - self.w // 2
        r = np.hypot(dy, dx)
        v = potential(r, self.params, attractive=True)
        return 0.5 * float(v.sum())

    # ---------------------------------------------------------- step

    def step(self):
        self.iteration += 1
        if self.n == 0:
            return False
        m = max(1, min(self.batch, self.n))
        idx = self.rng.choice(self.n, size=m, replace=False)
        old = self.pos[idx]
        d = self.rng.integers(
            -self.step_size, self.step_size + 1, size=(m, 2)
        )
        new = old + d
        keep = (d != 0).any(axis=1)
        if self.periodic:
            new[:, 0] %= self.h
            new[:, 1] %= self.w
        else:
            keep &= (
                (new[:, 0] >= 0)
                & (new[:, 0] < self.h)
                & (new[:, 1] >= 0)
                & (new[:, 1] < self.w)
            )
        new = np.clip(new, [0, 0], [self.h - 1, self.w - 1])
        idx, old, new, keep = (
            idx[keep],
            old[keep],
            new[keep],
            keep[keep],
        )
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
        if len(idx) == 0:
            self.fail_streak += 1
            if self.fail_streak >= self.fail_limit:
                self.fail_streak = 0
                self.batch = max(
                    self.batch_min, self.batch - self.batch_decrement
                )
            return False
        self.fail_streak = 0

        q = self.params["charge"]
        without = self.occ.copy().astype(float)
        without[old[:, 0], old[:, 1]] = 0.0
        e_old = float(
            self._gather(old[:, 0], old[:, 1], without).sum()
        )
        e_new = float(
            self._gather(new[:, 0], new[:, 1], without).sum()
        )
        e_old += self._pair_energy(old)
        e_new += self._pair_energy(new)
        de = q * q * (e_new - e_old) + q * float(
            self.u_edge[new[:, 0], new[:, 1]].sum()
            - self.u_edge[old[:, 0], old[:, 1]].sum()
        )
        self.proposed += 1
        kt = KB_EV * max(self.temperature, 1e-9)
        accept = de <= 0 or self.rng.random() < math.exp(-de / kt)
        if accept:
            self.occ[old[:, 0], old[:, 1]] = False
            self.occ[new[:, 0], new[:, 1]] = True
            self.pos[idx] = new
            self.energy += de
            self.accepted += 1
        return bool(accept)

    # -------------------------------------------------------- paint

    def paint_stroke(self, points, br, first):
        """Match the Rust `paint_stroke` on the incremental u_edge path.

        Uses `brush.stroke_patch`, `brush.coupling_kernel`, and
        `brush.potential_patch` from projects/coulomb-brush/brush.py.
        """
        p_arr = np.asarray(points, dtype=np.float64)
        res = BRUSH_MOD.stroke_patch(p_arr, br, first=first)
        if res is None:
            return
        y0, x0, dq, cov_p = res
        rp, K = BRUSH_MOD.coupling_kernel(
            self.params["strength"],
            self.params["screening"],
            br.coupling,
            br.hardness,
        )
        du = BRUSH_MOD.potential_patch(dq, K, rp)

        # Place the dq patch onto self.paint (bounded, non-periodic).
        ry0, ry1 = max(0, y0), min(self.h, y0 + dq.shape[0])
        rx0, rx1 = max(0, x0), min(self.w, x0 + dq.shape[1])
        if ry0 < ry1 and rx0 < rx1:
            sy0, sx0 = ry0 - y0, rx0 - x0
            self.paint[ry0:ry1, rx0:rx1] += dq[
                sy0 : sy0 + (ry1 - ry0), sx0 : sx0 + (rx1 - rx0)
            ]

        # Place the du patch onto u_edge, grown by rp.
        uy0, ux0 = y0 - rp, x0 - rp
        uy1 = min(self.h, uy0 + du.shape[0])
        ux1 = min(self.w, ux0 + du.shape[1])
        uy0c, ux0c = max(0, uy0), max(0, ux0)
        if uy0c < uy1 and ux0c < ux1:
            sy = uy0c - uy0
            sx = ux0c - ux0
            de_field = 0.0
            patch = du[
                sy : sy + (uy1 - uy0c), sx : sx + (ux1 - ux0c)
            ]
            self.u_edge[uy0c:uy1, ux0c:ux1] += patch
            de_field = float(
                (
                    self.occ[uy0c:uy1, ux0c:ux1].astype(float) * patch
                ).sum()
            ) * self.params["charge"]
            self.energy += de_field

    # ------------------------------------------------------ scenario

    def run_stage(self, st, prev):
        self.temperature = st["temperature"]
        for stroke in st.get("strokes", []):
            br_cfg = st.get("brush") or {}
            br = BRUSH_MOD.Brush(
                sign=br_cfg.get("sign", 1.0),
                magnitude=br_cfg.get("magnitude", 4.0),
                density=br_cfg.get("density", 1.0),
                thickness=br_cfg.get("thickness", 10.0),
                flow=br_cfg.get("flow", 1.0),
                hardness=br_cfg.get("hardness", 0.5),
                penetrability=br_cfg.get("penetrability", 1.0),
                coupling=br_cfg.get("coupling", 12.0),
            )
            points = [[p["y"], p["x"]] for p in stroke]
            self.paint_stroke(points, br, first=True)
        every = int(st.get("energy_sample_every", 50))
        history = []
        for k in range(int(st["iterations"])):
            self.step()
            if k % every == 0:
                history.append(float(self.energy))
        tail = history[len(history) // 2 :]
        energy_mean_tail = (
            float(np.mean(tail)) if tail else float(self.energy)
        )
        dp = self.proposed - prev["proposed"]
        da = self.accepted - prev["accepted"]
        return {
            "stage": prev["stage"],
            "temperature": st["temperature"],
            "iterations": int(st["iterations"]),
            "proposed": int(dp),
            "accepted": int(da),
            "acceptance": (da / dp) if dp else 0.0,
            "energy": float(self.energy),
            "energy_mean_tail": energy_mean_tail,
            "particles": int(self.n),
        }


def main():
    scenario_path = Path(sys.argv[1])
    sc = json.loads(scenario_path.read_text())
    chains = max(1, int(sc.get("chains", 1)))
    per_chain = [[] for _ in range(len(sc["stages"]))]
    final_energy = 0.0
    final_particles = 0
    base_seed = int(sc["seed"])
    for chain in range(chains):
        sc_chain = dict(sc)
        sc_chain["seed"] = base_seed + chain * 997
        sim = PySim(sc_chain)
        prev = {"proposed": 0, "accepted": 0, "stage": 0}
        for i, st in enumerate(sc["stages"]):
            prev["stage"] = i
            rep = sim.run_stage(st, prev)
            per_chain[i].append(rep)
            prev = {
                "proposed": sim.proposed,
                "accepted": sim.accepted,
                "stage": i + 1,
            }
        final_energy = float(sim.energy)
        final_particles = int(sim.n)
    stages = []
    for i, rows in enumerate(per_chain):
        n = len(rows)
        sum_prop = sum(r["proposed"] for r in rows)
        sum_acc = sum(r["accepted"] for r in rows)
        stages.append({
            "stage": i,
            "temperature": rows[0]["temperature"],
            "iterations": rows[0]["iterations"],
            "proposed": int(sum_prop),
            "accepted": int(sum_acc),
            "acceptance": (sum_acc / sum_prop) if sum_prop else 0.0,
            "energy": sum(r["energy"] for r in rows) / n,
            "energy_mean_tail": sum(r["energy_mean_tail"] for r in rows) / n,
            "particles": rows[0]["particles"],
        })
    out = {
        "stages": stages,
        "final_energy": final_energy,
        "final_particles": final_particles,
    }
    json.dump(out, sys.stdout)


if __name__ == "__main__":
    main()
