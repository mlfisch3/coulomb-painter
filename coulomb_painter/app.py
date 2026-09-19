"""Flask front-end for the painter.

The simulation runs in a background thread and is manipulated only through a
priority lock, so control endpoints land within one iteration and the render
endpoint can lag arbitrarily without blocking physics.  Every mutation of the
simulator (paint, params, reset, canvas rebuild) goes through the lock; only
read-only stat / render calls take it for the minimum window.
"""

from __future__ import annotations

import os
import threading
import time
import webbrowser
from dataclasses import asdict

from flask import Flask, jsonify, request, send_from_directory

from . import brush as B
from .render import render_png
from .sim import CoulombSim, Params, params_from_dict, LIVE_KEYS, REBUILD_KEYS


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

    def new_canvas(self, p: Params) -> CoulombSim:
        """Swap in a fresh simulator, keeping the thread alive."""
        with self.lock:
            self.sim = CoulombSim(p)
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


def create_app() -> Flask:
    app = Flask(__name__, static_folder=None)

    @app.route("/")
    def index():
        return send_from_directory(WEB, "index.html")

    @app.route("/<path:name>")
    def static_file(name):
        return send_from_directory(WEB, name)

    # ---- state ---------------------------------------------------------

    @app.post("/api/new_canvas")
    def api_new_canvas():
        data = request.get_json(force=True, silent=True) or {}
        p = params_from_dict(data)
        sim = get_engine().new_canvas(p)
        return jsonify({"ok": True, "params": asdict(sim.p),
                        "stats": sim.stats()})

    @app.post("/api/reset")
    def api_reset():
        sim = get_engine().sim
        with sim.priority_lock():
            sim.reset()
        return jsonify({"ok": True, "stats": sim.stats()})

    @app.post("/api/params")
    def api_params():
        """Apply a live control change (temperature, cooling, brush, ...).

        Keys in ``REBUILD_KEYS`` rebuild the interaction kernel and re-project
        the painted contribution; keys in ``LIVE_KEYS`` are applied in place.
        Anything else is ignored - unknown keys must not silently mutate the
        simulator.
        """
        data = request.get_json(force=True, silent=True) or {}
        sim = get_engine().sim
        applied = {}
        with sim.priority_lock():
            needs_rebuild = any(k in REBUILD_KEYS for k in data)
            for k, v in data.items():
                if k not in LIVE_KEYS and k not in REBUILD_KEYS:
                    continue
                cur = getattr(sim.p, k, None)
                try:
                    if isinstance(cur, bool):
                        v = bool(v)
                    elif isinstance(cur, int) and not isinstance(cur, bool):
                        v = int(v)
                    elif isinstance(cur, float):
                        v = float(v)
                except (TypeError, ValueError):
                    continue
                setattr(sim.p, k, v)
                applied[k] = v
            if needs_rebuild:
                sim.build_interaction()
                sim.energy = sim.total_energy()
        return jsonify({"ok": True, "applied": applied,
                        "params": asdict(sim.p)})

    @app.get("/api/state")
    def api_state():
        sim = get_engine().sim
        return jsonify({"params": asdict(sim.p), "stats": sim.stats()})

    # ---- painting ------------------------------------------------------

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
        # points arrive as [x, y] in lattice coords from the client so a
        # rotated screen never needs a second convention on the server; sim
        # expects (y, x).
        yx = [(float(y), float(x)) for x, y in pts]
        with sim.priority_lock():
            info = sim.paint_stroke(yx, br, first=first, last=last)
        info["stats"] = sim.stats()
        info["ok"] = True
        return jsonify(info)

    # ---- rendering -----------------------------------------------------

    @app.get("/api/frame.png")
    def api_frame():
        sim = get_engine().sim
        if not sim.p.live_view:
            # a 1x1 transparent PNG keeps the client's <img> alive without
            # spending any CPU on rendering
            return (b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01"
                    b"\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89"
                    b"\x00\x00\x00\rIDATx\x9cc\x00\x01\x00\x00\x05\x00\x01"
                    b"\r\n-\xb4\x00\x00\x00\x00IEND\xaeB`\x82"), 200, \
                   {"Content-Type": "image/png"}
        max_px = int(request.args.get("max_px", 900))
        with sim.priority_lock():
            occ = sim.occ.copy()
            paint = sim.paint.copy() if sim.paint.any() else None
        png = render_png(occ, paint, max_px=max_px)
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
