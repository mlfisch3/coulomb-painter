"""Flask front-end for the painter.

The simulation runs in a background thread and is manipulated only through a
priority lock, so control endpoints land within one iteration and the render
endpoint can lag arbitrarily without blocking physics.  Every mutation of the
simulator (paint, params, reset, canvas rebuild) goes through the lock; only
read-only stat / render calls take it for the minimum window.

Endpoint surface mirrors the reference `anneal_gui.py` (init, update,
control, paint, save, snapshot, zoom, images, upload, history, undo,
clear_paint) so the front-end can shadow the reference's front panel while
enforcing the painter's mobile-first layout constraints.
"""

from __future__ import annotations

import base64
import io
import os
import threading
import time
import webbrowser
from dataclasses import asdict

import numpy as np
from flask import Flask, jsonify, request, send_from_directory
from PIL import Image

from . import brush as B
from .render import render_png, render_zoom
from .sim import (BUILD_KEYS, CoulombSim, LIVE_KEYS, Params, REBUILD_KEYS,
                  params_from_dict)


HERE = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.join(HERE, "web")


class Engine:
    """Owns the simulator and the annealing thread.

    ``lock`` here guards ``sim`` (which can be swapped by a New Canvas call);
    ``sim.priority_lock()`` guards the arrays inside a given simulator.
    """

    def __init__(self, p: Params):
        self.lock = threading.Lock()
        self.sim = CoulombSim(p)
        # Uploaded image bytes are cached so a rebuild triggered by a
        # resolution change does not need the browser to re-post the file.
        self.upload_bytes: bytes | None = None
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True,
                                        name="anneal")
        self._thread.start()

    def _run(self):
        """Anneal in short bursts, yielding to any waiting control thread.

        Each burst runs a small number of iterations under the sim lock and
        breaks as soon as any priority-lock waiter arrives.  A frozen or
        paused simulator sleeps briefly instead of stepping so we do not spin.
        """
        while not self._stop.is_set():
            with self.lock:
                sim = self.sim
            p = sim.p
            if p.paused or p.frozen:
                time.sleep(0.05)
                continue
            burst = 24
            steps = 0
            with sim.lock:
                for _ in range(burst):
                    if sim.lock_wait > 0 or self._stop.is_set():
                        break
                    if p.paused or p.frozen:
                        break
                    sim.step()
                    steps += 1
            if steps == 0:
                time.sleep(0.01)

    def rebuild(self, p: Params,
                upload_bytes: bytes | None = None) -> CoulombSim:
        """Swap in a fresh simulator, keeping the thread alive."""
        with self.lock:
            self.upload_bytes = upload_bytes if upload_bytes is not None \
                else self.upload_bytes
            self.sim = CoulombSim(p, upload_bytes=self.upload_bytes)
        return self.sim

    def stop(self):
        self._stop.set()
        self._thread.join(timeout=1.0)


ENGINE: Engine | None = None


def get_engine() -> Engine:
    global ENGINE
    if ENGINE is None:
        ENGINE = Engine(Params())
    return ENGINE


def _apply_live(sim: CoulombSim, data: dict) -> tuple[dict, bool]:
    """Apply LIVE_KEYS and REBUILD_KEYS in place.  Returns (applied, rebuilt).

    Unknown keys are ignored: a stray field from the client must not silently
    mutate the simulator.  BUILD_KEYS are refused - the caller must go through
    ``/api/init`` for anything that changes the lattice shape or drawing.
    """
    applied: dict = {}
    needs_rebuild = False
    for k, v in data.items():
        if k in BUILD_KEYS:
            continue
        if k not in LIVE_KEYS and k not in REBUILD_KEYS:
            continue
        cur = getattr(sim.p, k, None)
        try:
            if isinstance(cur, bool):
                v = bool(v) if not isinstance(v, str) \
                    else v.lower() in ("1", "true", "on", "yes")
            elif isinstance(cur, int) and not isinstance(cur, bool):
                v = int(round(float(v)))
            elif isinstance(cur, float):
                v = float(v)
        except (TypeError, ValueError):
            continue
        setattr(sim.p, k, v)
        applied[k] = v
        if k in REBUILD_KEYS:
            needs_rebuild = True
    if needs_rebuild:
        sim.build_interaction()
        sim.energy = sim.total_energy()
    return applied, needs_rebuild


def create_app() -> Flask:
    app = Flask(__name__, static_folder=None)

    # ---- static -------------------------------------------------------

    @app.route("/")
    def index():
        return send_from_directory(WEB, "index.html")

    @app.route("/<path:name>")
    def static_file(name):
        return send_from_directory(WEB, name)

    # ---- state and control -------------------------------------------

    @app.get("/api/state")
    def api_state():
        sim = get_engine().sim
        return jsonify({"params": asdict(sim.p), "stats": sim.stats()})

    @app.post("/api/init")
    def api_init():
        """Full rebuild: image, resolution, fill, line settings all applied."""
        data = request.get_json(force=True, silent=True) or {}
        p = params_from_dict(data)
        eng = get_engine()
        # If the client asks for an upload spec without having uploaded, fall
        # back to blank so the payload cannot be silently rejected.
        if p.image.startswith("upload:") and eng.upload_bytes is None:
            p.image = "blank:1920x1080"
        sim = eng.rebuild(p)
        return jsonify({"ok": True, "params": asdict(sim.p),
                        "stats": sim.stats()})

    @app.post("/api/update")
    def api_update():
        """Apply live parameter changes (temperature, cooling, brush, ...).

        LIVE_KEYS are applied in place; REBUILD_KEYS rebuild the kernel and
        re-project the painted contribution.  BUILD_KEYS are refused so a
        stray keystroke cannot destroy the canvas the artist is working on.
        """
        data = request.get_json(force=True, silent=True) or {}
        sim = get_engine().sim
        with sim.priority_lock():
            applied, rebuilt = _apply_live(sim, data)
        return jsonify({"ok": True, "applied": applied, "rebuilt": rebuilt,
                        "params": asdict(sim.p)})

    @app.post("/api/control")
    def api_control():
        """Pause / run / step / reset / auto_temp / add_uniform / freeze."""
        data = request.get_json(force=True, silent=True) or {}
        action = str(data.get("action", ""))
        sim = get_engine().sim
        info: dict = {"ok": True, "action": action}
        with sim.priority_lock():
            if action == "pause":
                sim.p.paused = True
                info["running"] = False
            elif action == "run":
                sim.p.paused = False
                sim.p.frozen = False
                info["running"] = True
            elif action == "step":
                sim.p.paused = True
                sim.step()
                info["running"] = False
            elif action == "reset":
                sim.reset()
            elif action == "freeze":
                sim.p.frozen = True
            elif action == "unfreeze":
                sim.p.frozen = False
            elif action == "auto_temp":
                t = sim.auto_temperature()
                info["temperature"] = t
            elif action == "add_uniform":
                sim.add_uniform_charges()
            else:
                return jsonify({"ok": False,
                                "error": f"unknown action: {action}"}), 400
        info["stats"] = sim.stats()
        info["params"] = asdict(sim.p)
        return jsonify(info)

    # ---- painting -----------------------------------------------------

    @app.post("/api/paint")
    def api_paint():
        data = request.get_json(force=True, silent=True) or {}
        pts = data.get("points") or []
        if not pts:
            return jsonify({"ok": True, "painted": 0})
        first = bool(data.get("first"))
        last = bool(data.get("last"))
        override = data.get("brush") or {}
        sim = get_engine().sim
        br = B.Brush.from_params(sim.p, override=override)
        # points arrive as [x, y] in lattice coords; sim expects (y, x)
        yx = [(float(y), float(x)) for x, y in pts]
        with sim.priority_lock():
            info = sim.paint_stroke(yx, br, first=first, last=last)
        info["stats"] = sim.stats()
        info["ok"] = True
        return jsonify(info)

    @app.post("/api/undo")
    def api_undo():
        sim = get_engine().sim
        with sim.priority_lock():
            ok = sim.undo_stroke()
        return jsonify({"ok": True, "undid": ok, "stats": sim.stats()})

    @app.post("/api/clear_paint")
    def api_clear_paint():
        sim = get_engine().sim
        with sim.priority_lock():
            sim.clear_paint()
        return jsonify({"ok": True, "stats": sim.stats()})

    # ---- images and upload -------------------------------------------

    @app.get("/api/images")
    def api_images():
        # The painter starts from nothing; there are no bundled drawings.
        # The client always has the two synthetic aspects plus whatever the
        # user has uploaded this session.
        opts = ["blank:1920x1080", "blank:1080x1920", "blank:1024x1024"]
        if get_engine().upload_bytes is not None:
            opts.append("upload:current")
        return jsonify(opts)

    @app.post("/api/upload")
    def api_upload():
        f = request.files.get("file")
        if f is None:
            return jsonify({"ok": False, "error": "no file"}), 400
        blob = f.read()
        # validate: refuse anything Pillow cannot open, up front
        try:
            Image.open(io.BytesIO(blob)).verify()
        except Exception as exc:
            return jsonify({"ok": False,
                            "error": f"not an image: {exc}"}), 400
        eng = get_engine()
        eng.upload_bytes = blob
        return jsonify({"ok": True, "path": "upload:current",
                        "bytes": len(blob)})

    # ---- history ------------------------------------------------------

    @app.get("/api/history")
    def api_history():
        sim = get_engine().sim
        with sim.priority_lock():
            h = list(sim.history)
        return jsonify({"history": h})

    # ---- save / snapshot ---------------------------------------------

    @app.post("/api/save")
    def api_save():
        # Full project save (.cmb) is deferred per the ship brief.  The
        # button still exists in the reference-shaped UI, so it returns a
        # message instead of silently doing nothing.
        return jsonify({"ok": False,
                        "error": ("Save state (.cmb) is deferred to a "
                                  "follow-up ship.")}), 200

    @app.post("/api/snapshot")
    def api_snapshot():
        """Encode a full-resolution PNG and return it inline as a data URL.

        This is the browser-friendly version of the reference's "Snapshot +
        params": no filesystem write, no path back to a run directory - the
        browser gets the bytes and can save them wherever it likes.
        """
        sim = get_engine().sim
        with sim.priority_lock():
            occ = sim.occ.copy()
            paint = sim.paint.copy() if sim.paint.any() else None
            cov = sim.cov.copy()
            p = sim.p
        png = render_png(occ, paint, cov,
                         max_px=max(occ.shape),
                         smooth=p.smooth, color_mode=p.color_mode,
                         palette=p.palette, show_edges=p.show_edges,
                         show_paint=p.show_paint)
        return jsonify({
            "ok": True,
            "data_url": "data:image/png;base64,"
                        + base64.b64encode(png).decode("ascii"),
            "bytes": len(png), "params": asdict(p),
        })

    # ---- rendering ----------------------------------------------------

    _EMPTY_PNG = (b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01"
                  b"\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89"
                  b"\x00\x00\x00\rIDATx\x9cc\x00\x01\x00\x00\x05\x00\x01"
                  b"\r\n-\xb4\x00\x00\x00\x00IEND\xaeB`\x82")

    @app.get("/api/frame.png")
    def api_frame():
        sim = get_engine().sim
        if not sim.p.live_view:
            return _EMPTY_PNG, 200, {"Content-Type": "image/png",
                                     "Cache-Control": "no-store"}
        try:
            max_px = int(request.args.get("max_px",
                                          sim.p.display_px or 900))
        except (TypeError, ValueError):
            max_px = 900
        with sim.priority_lock():
            occ = sim.occ.copy()
            paint = sim.paint.copy() if sim.paint.any() else None
            cov = sim.cov.copy() if sim.p.show_edges and sim.cov.any() \
                else None
            p = sim.p
        png = render_png(occ, paint, cov,
                         max_px=max_px, smooth=p.smooth,
                         color_mode=p.color_mode, palette=p.palette,
                         show_edges=p.show_edges, show_paint=p.show_paint)
        return png, 200, {"Content-Type": "image/png",
                          "Cache-Control": "no-store"}

    @app.get("/api/zoom.png")
    def api_zoom():
        try:
            cx = int(float(request.args.get("cx", 0)))
            cy = int(float(request.args.get("cy", 0)))
            span = int(request.args.get("span", 160))
            out = int(request.args.get("out", 560))
        except (TypeError, ValueError):
            return _EMPTY_PNG, 200, {"Content-Type": "image/png"}
        sim = get_engine().sim
        with sim.priority_lock():
            occ, paint = sim.crop_occ(cy, cx, span)
            p = sim.p
        png = render_zoom(occ, paint, out=out,
                          color_mode=p.color_mode, palette=p.palette,
                          show_paint=p.show_paint)
        return png, 200, {"Content-Type": "image/png",
                          "Cache-Control": "no-store"}

    return app


def run(host: str = "127.0.0.1", port: int = 8770,
        open_browser: bool = False) -> None:
    app = create_app()
    url = f"http://{host}:{port}"
    print(f"Coulomb Painter: {url}")
    if open_browser:
        try:
            webbrowser.open(url)
        except Exception:
            pass
    try:
        app.run(host=host, port=port, threaded=True, use_reloader=False)
    finally:
        if ENGINE is not None:
            ENGINE.stop()
