"""`python -m coulomb_painter` (and the `coulomb-painter` console script)."""

from __future__ import annotations

import argparse

from .app import run


def main() -> None:
    ap = argparse.ArgumentParser(prog="coulomb-painter",
                                 description="Painter for Coulomb-gas dynamics.")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8770)
    ap.add_argument("--open", action="store_true",
                    help="open the browser once the server is up")
    args = ap.parse_args()
    run(host=args.host, port=args.port, open_browser=args.open)


if __name__ == "__main__":
    main()
