#!/usr/bin/env python3
r"""foundry_pair_qr.py - render a pairing QR for the foundry node (Debian box).

WHY
  make_pair_qr.py pairs against THIS PC's dashboard. The phone should also be
  able to attach to foundry (the always-on node), so it needs foundry's own
  pairing payload: host 100.121.66.11 (tailnet - works on any network), the
  dashboard username, and the dashboard password.

  The password is read off foundry over ssh and goes straight into the QR
  image. It is NEVER printed to stdout - only booleans and metadata are.

USAGE
  python foundry_pair_qr.py                 # -> D:\Temp\foundry-pair-qr.png
  python foundry_pair_qr.py -o <path>       # custom path

The image is a credential: delete it once the phone has scanned it.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
sys.path.insert(0, str(Path(__file__).parent / "vendor"))


def ssh_read(ssh_host: str, remote_path: str) -> str:
    r = subprocess.run(
        ["ssh", "-o", "ConnectTimeout=10", ssh_host, f"cat {remote_path}"],
        capture_output=True, text=True, timeout=30,
    )
    if r.returncode != 0:
        raise SystemExit(f"ssh failed ({r.returncode}): {r.stderr.strip()}")
    return r.stdout.strip()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ssh", default="foundry", help="ssh-config host alias")
    ap.add_argument("--host", default="100.121.66.11", help="tailnet address the phone dials")
    ap.add_argument("--port", type=int, default=9119)
    ap.add_argument("--user", default="sadman")
    ap.add_argument("--name", default="foundry")
    ap.add_argument("--secret-file", default="~/foundry-secrets/dashboard-password.txt")
    ap.add_argument("-o", "--out", default=r"D:\Temp\foundry-pair-qr.png")
    a = ap.parse_args()

    pw = ssh_read(a.ssh, a.secret_file)
    if not pw:
        raise SystemExit("empty password from foundry secrets")

    payload = {
        "v": 1,
        "name": a.name,
        "host": a.host,
        "port": a.port,
        "auth": "gated",
        "provider": "basic",
        "username": a.user,
        "password": pw,
    }
    blob = json.dumps(payload, separators=(",", ":"))

    try:
        import qrcode
    except ImportError:
        print("qrcode not available - raw payload withheld (contains the password)", file=sys.stderr)
        return 2

    qr = qrcode.QRCode(border=2, box_size=10)
    qr.add_data(blob)
    qr.make(fit=True)
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    qr.make_image(fill_color="black", back_color="white").save(out)

    # Read the image back and confirm it decodes to the same payload.
    # Only booleans are printed, never the password itself.
    try:
        import cv2

        decoded, _, _ = cv2.QRCodeDetector().detectAndDecode(cv2.imread(str(out)))
        got = json.loads(decoded)
        ok = (
            got.get("host") == a.host
            and got.get("username") == a.user
            and got.get("password") == pw
            and got.get("port") == a.port
        )
        print(f"decode check: host={got.get('host')} port={got.get('port')} "
              f"user={got.get('username')} password_matches={got.get('password') == pw} all_ok={ok}")
    except Exception as e:  # cv2 optional - report, don't fail
        print(f"decode check skipped: {e}")

    print(f"image: {out} ({out.stat().st_size} bytes)")
    print("NOTE: this file is a credential - delete after the phone pairs.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
