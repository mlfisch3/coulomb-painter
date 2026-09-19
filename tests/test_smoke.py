"""End-to-end smoke tests: sim init, step, both brush targets, render, HTTP.

Deliberately thin.  The painter MVP is small enough that most bugs will be
caught by manual play, but the shape of the API and the two paint targets are
easy to break silently, so they get an automated tripwire.
"""

from __future__ import annotations

import numpy as np

from coulomb_painter import brush, coulomb, render, sim
from coulomb_painter.app import create_app


def test_sim_init_and_step():
    p = sim.Params(resolution=96, fill=0.25, temperature=3000.0)
    s = sim.CoulombSim(p, seed=1)
    n0 = s.n
    for _ in range(100):
        s.step()
    assert s.n == n0, "step must not create or destroy particles"
    assert s.iteration == 100
    assert 0.0 <= s.stats()["accept_rate"] <= 1.0


def test_paint_fixed_updates_u_edge():
    p = sim.Params(resolution=96, fill=0.20)
    s = sim.CoulombSim(p, seed=1)
    assert not s.u_edge.any()
    br = brush.Brush.from_params(p, override={"target": "fixed",
                                              "magnitude": 4.0,
                                              "thickness": 8.0})
    info = s.paint_stroke([(40.0, 40.0), (60.0, 60.0)], br,
                          first=True, last=True)
    assert info["target"] == "fixed"
    assert info["painted"] > 0
    assert float(np.abs(s.paint).sum()) > 0.0
    assert float(np.abs(s.u_edge).sum()) > 0.0


def test_paint_mobile_removes_particles():
    p = sim.Params(resolution=96, fill=0.5)
    s = sim.CoulombSim(p, seed=2)
    n0 = s.n
    br = brush.Brush.from_params(p, override={"target": "mobile",
                                              "sign": -1.0,
                                              "thickness": 20.0,
                                              "magnitude": 1.0})
    info = s.paint_stroke([(50.0, 50.0)], br, first=True, last=True)
    assert info["target"] == "mobile"
    assert info["removed"] > 0
    assert s.n == n0 - info["removed"]


def test_render_png_shape():
    p = sim.Params(resolution=64, fill=0.3)
    s = sim.CoulombSim(p, seed=3)
    png = render.render_png(s.occ, s.paint, s.cov, max_px=200)
    assert png[:8] == b"\x89PNG\r\n\x1a\n"


def test_undo_stroke_restores_state():
    p = sim.Params(resolution=96, fill=0.20)
    s = sim.CoulombSim(p, seed=5)
    paint_before = s.paint.sum()
    n_before = s.n
    br = brush.Brush.from_params(p, override={"target": "fixed",
                                              "magnitude": 4.0,
                                              "thickness": 8.0})
    s.paint_stroke([(40.0, 40.0), (60.0, 60.0)], br, first=True, last=True)
    assert s.paint.sum() != paint_before
    assert s.undo_stroke()
    # undo restores fixed-charge to zero (or its prior value)
    assert abs(s.paint.sum() - paint_before) < 1e-6
    # mobile paint undo path
    br2 = brush.Brush.from_params(p, override={"target": "mobile",
                                               "sign": -1.0,
                                               "thickness": 12.0})
    info = s.paint_stroke([(50.0, 50.0)], br2, first=True, last=True)
    assert info["removed"] > 0
    assert s.undo_stroke()
    assert s.n == n_before


def test_flask_endpoints():
    app = create_app().test_client()
    # index and static
    assert app.get("/").status_code == 200
    assert app.get("/style.css").status_code == 200
    assert app.get("/app.js").status_code == 200
    # state
    j = app.get("/api/state").get_json()
    assert "params" in j and "stats" in j
    # new canvas via /api/init (full rebuild)
    r = app.post("/api/init", json={"image": "blank:128x128",
                                    "resolution": 128, "fill": 0.2})
    j = r.get_json()
    assert j["ok"] and j["stats"]["lattice"] == [128, 128]
    # paint
    r = app.post("/api/paint", json={"points": [[50, 50], [60, 60]],
                                     "first": True, "last": True})
    assert r.get_json()["ok"]
    # reset via /api/control
    assert app.post("/api/control", json={"action": "reset"}).get_json()["ok"]
    # live update
    r = app.post("/api/update", json={"temperature": 3500,
                                       "brush_sign": -1})
    assert r.get_json()["ok"]
    # undo (no strokes: still ok)
    assert app.post("/api/undo").get_json()["ok"]
    # clear paint
    assert app.post("/api/clear_paint").get_json()["ok"]
    # zoom
    r = app.get("/api/zoom.png?cx=64&cy=64&span=32&out=128")
    assert r.status_code == 200 and r.data[:8] == b"\x89PNG\r\n\x1a\n"
    # images list
    lst = app.get("/api/images").get_json()
    assert isinstance(lst, list) and any(s.startswith("blank:") for s in lst)
    # frame
    r = app.get("/api/frame.png")
    assert r.status_code == 200
    assert r.data[:8] == b"\x89PNG\r\n\x1a\n"
