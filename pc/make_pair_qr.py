#!/usr/bin/env python3
r"""make_pair_qr.py - render the pairing payload as a PNG you can scan off the screen.

WHY
  render_qr.py draws ASCII in a terminal, which is fine at the PC but useless when
  the QR has to be shown somewhere a phone can see it. This writes a real image to
  disk so it can be displayed (app window, photo viewer, chat) and scanned.

  The payload is taken from the running pairing page when it is up, so the QR is
  always the live one - including the redirect to the TAILNET host that makes the
  pairing work from any network. It falls back to building the payload directly.

  The image contains the dashboard password. It is a credential: treat the PNG the
  way you would treat the password itself, and delete it when you are done pairing.

USAGE
  python make_pair_qr.py                     # -> pc\pair-qr.png
  python make_pair_qr.py -o D:\Temp\qr.png   # custom path
  python make_pair_qr.py --payload           # also print the raw JSON (manual entry)
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
sys.path.insert(0, str(Path(__file__).parent / "vendor"))

from netinfo import pair_host  # noqa: E402

PAGE = "http://localhost:9120/payload"
PORT = 9119
PWFILE = Path(__file__).parent / ".dashboard-password"
DEFAULT_OUT = Path(__file__).parent / "pair-qr.png"


def from_page() -> dict | None:
    try:
        with urllib.request.urlopen(PAGE, timeout=6) as r:
            return json.loads(r.read().decode())
    except Exception:
        return None


def build() -> dict:
    pw = PWFILE.read_text(encoding="utf-8").strip() if PWFILE.exists() else ""
    host, away = pair_host()
    return {"v": 1, "name": "Hermes PC", "host": host, "port": PORT,
            "auth": "gated", "provider": "basic", "username": "sadman", "password": pw,
            "_away_ok": away}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("-o", "--out", default=str(DEFAULT_OUT))
    ap.add_argument("--payload", action="store_true", help="print the raw payload too")
    ap.add_argument("--scale", type=int, default=10, help="pixels per module (default 10)")
    a = ap.parse_args()

    payload = from_page()
    src = "pairing page (live)"
    if not payload:
        payload = build()
        src = "built from .dashboard-password (page down)"
    payload.pop("_away_ok", None)

    host = payload.get("host", "")
    blob = json.dumps(payload, separators=(",", ":"))

    try:
        import qrcode
    except ImportError:
        print("qrcode is not vendored - use the raw payload for manual entry:", file=sys.stderr)
        print(blob)
        return 2

    qr = qrcode.QRCode(border=2, box_size=a.scale)
    qr.add_data(blob)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white")
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)

    o = [int(p) for p in host.split(".")] if host else []
    is_ts = len(o) == 4 and o[0] == 100 and 64 <= o[1] <= 127

    print(f"payload source : {src}")
    print(f"pair host      : {host}:{payload.get('port')}"
          f"   {'TAILNET - works on any network' if is_ts else 'LAN ONLY - fails away from home'}")
    print(f"image          : {out}  ({out.stat().st_size} bytes)")
    if not is_ts:
        print("WARNING: this QR is LAN-only. Run internet-access.ps1 before pairing.")
    if a.payload:
        print(f"raw payload    : {blob}")
    return 0 if is_ts else 1


if __name__ == "__main__":
    raise SystemExit(main())
