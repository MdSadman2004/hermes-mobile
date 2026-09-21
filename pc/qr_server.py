#!/usr/bin/env python3
r"""qr_server.py — always-on pairing page at http://localhost:9120/  (NO LOGIN).

WHY A SEPARATE SERVER
  The dashboard gates every page behind a login, so the pairing QR was only
  reachable after you had already authenticated - useless when the phone is not
  paired yet. Rather than punch a hole in the dashboard's auth (which would also
  expose it on the LAN), this serves the QR from its own tiny server.

SECURITY
  Binds 127.0.0.1 ONLY. The page shows the dashboard password in plaintext, so
  it must never be reachable off-box; a loopback bind means only someone already
  at this PC can read it. Refuses to start on any other interface.

PERMANENT PAIRING
  The QR prefers the TAILSCALE address. A LAN IP is handed out by DHCP and
  changes when the router reboots or you join another network - which silently
  breaks a saved profile. The tailnet address (100.x) is assigned per-machine and
  is stable everywhere: home Wi-Fi, mobile data, another country. Pair once.

USAGE
  python qr_server.py            # serve on http://localhost:9120
  python qr_server.py --port N
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import html
import io
import json
import re
import socket
import sys
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

PC_DIR = Path(r"D:\HermesMobile\pc")
PWFILE = PC_DIR / ".dashboard-password"
CONFIG = Path(r"D:\.hermes\config.yaml")
DASH_PORT = 9119
sys.path.insert(0, str(PC_DIR / "vendor"))


def dashboard_status():
    try:
        with urllib.request.urlopen(
                f"http://127.0.0.1:{DASH_PORT}/api/status", timeout=4) as r:
            return json.loads(r.read().decode())
    except Exception:
        return None


# Shared with hermes-dashboard.py. The local copy here used bare
# `startswith("100.")`, which is not the tailnet range - it would happily emit
# 100.200.x.x as the pairing host and the phone could never reach it.
from netinfo import is_tailnet, lan_ip, tailscale_ip  # noqa: E402


def credential_ok(pw: str) -> bool:
    m = re.search(r"password_hash:\s*(\S+)", CONFIG.read_text(encoding="utf-8"))
    if not m:
        return False
    try:
        _, n, r, p, s_b64, h_b64 = m.group(1).split("$")
        want = base64.b64decode(h_b64)
        got = hashlib.scrypt(pw.encode(), salt=base64.b64decode(s_b64),
                             n=int(n), r=int(r), p=int(p), dklen=len(want))
        return got == want
    except Exception:
        return False


def username() -> str:
    m = re.search(r"basic_auth:.*?username:\s*(\S+)", CONFIG.read_text(encoding="utf-8"), re.S)
    return m.group(1) if m else "sadman"


def build_payload(host: str) -> dict:
    pw = PWFILE.read_text(encoding="utf-8").strip()
    user = username()
    return {"v": 1, "name": socket.gethostname(), "host": host, "port": DASH_PORT,
            "auth": "gated", "provider": "basic", "username": user, "password": pw,
            "fingerprint": hashlib.sha256(f"{user}:{pw}".encode()).hexdigest()}


def qr_svg(data: str) -> str:
    """Inline SVG so the page needs no static files and no internet."""
    try:
        import qrcode
        import qrcode.image.svg as svg
        img = qrcode.make(data, image_factory=svg.SvgPathImage, border=2)
        buf = io.BytesIO()
        img.save(buf)
        return buf.getvalue().decode()
    except Exception as e:
        return f"<p style='color:#f66'>QR render failed: {html.escape(str(e))}</p>"


PAGE = """<!doctype html><html><head><meta charset="utf-8">
<title>Hermes pairing</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
 body{{background:#0d1117;color:#e6edf3;font:15px/1.55 system-ui,Segoe UI,sans-serif;
      margin:0;padding:32px;display:flex;justify-content:center}}
 .w{{max-width:760px;width:100%}}
 h1{{font-size:20px;margin:0 0 4px}}
 .sub{{color:#8b949e;margin:0 0 24px}}
 .card{{background:#161b22;border:1px solid #30363d;border-radius:10px;
        padding:20px;margin-bottom:16px}}
 .qr{{background:#fff;padding:14px;border-radius:8px;display:inline-block}}
 .qr svg{{width:280px;height:280px;display:block}}
 table{{border-collapse:collapse;width:100%}}
 td{{padding:5px 0;vertical-align:top}}
 td:first-child{{color:#8b949e;width:110px}}
 code{{background:#0d1117;border:1px solid #30363d;border-radius:5px;
       padding:2px 7px;font-size:13px;word-break:break-all}}
 .ok{{color:#3fb950}} .bad{{color:#f85149}} .warn{{color:#d29922}}
 .foot{{color:#8b949e;font-size:13px;margin-top:22px}}
</style></head><body><div class="w">
<h1>Hermes &mdash; pair your phone</h1>
<p class="sub">Scan with Hermes Remote. This page is localhost-only.</p>
{body}
<p class="foot">Auto-refreshes every 30s &middot; served by qr_server.py on this PC only</p>
</div><script>setTimeout(()=>location.reload(),30000)</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass  # keep the console clean

    def do_GET(self):
        if self.path.startswith("/payload"):
            host = tailscale_ip() or lan_ip()
            b = json.dumps(build_payload(host)).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)
            return

        st = dashboard_status()
        pw = PWFILE.read_text(encoding="utf-8").strip() if PWFILE.exists() else ""
        ts, lan = tailscale_ip(), lan_ip()
        host = ts or lan

        parts = []
        if not st:
            parts.append('<div class="card"><b class="bad">Dashboard is not running.</b>'
                         "<br>Start it: <code>python D:\\HermesMobile\\pc\\hermes-dashboard.py</code>"
                         "</div>")
        elif not pw:
            parts.append('<div class="card"><b class="bad">.dashboard-password missing.</b></div>')
        elif not credential_ok(pw):
            parts.append('<div class="card"><b class="bad">Password does not match config.yaml.</b>'
                         "<br>A QR built from it would be rejected. Fix:<br>"
                         "<code>python D:\\HermesMobile\\pc\\fix_dashboard_password.py</code>"
                         "</div>")
        else:
            payload = json.dumps(build_payload(host), separators=(",", ":"))
            parts.append('<div class="card"><div class="qr">' + qr_svg(payload) + "</div></div>")
            parts.append(
                '<div class="card"><table>'
                f"<tr><td>Pair host</td><td><code>{html.escape(host)}:{DASH_PORT}</code>"
                f'{" &nbsp;<span class=ok>tailnet &mdash; works anywhere, never changes</span>" if ts else " &nbsp;<span class=warn>LAN only &mdash; changes with DHCP</span>"}</td></tr>'
                f"<tr><td>Username</td><td><code>{html.escape(username())}</code></td></tr>"
                f"<tr><td>Password</td><td><code>{html.escape(pw)}</code></td></tr>"
                f"<tr><td>LAN</td><td><code>http://{html.escape(lan)}:{DASH_PORT}</code></td></tr>"
                + (f"<tr><td>Tailscale</td><td><code>http://{html.escape(ts)}:{DASH_PORT}</code></td></tr>" if ts else "")
                + f"<tr><td>Hermes</td><td>v{html.escape(str(st.get('version')))} &middot; "
                  f"gateway {html.escape(str(st.get('gateway_state')))} &middot; "
                  f'<span class="ok">credential verified</span></td></tr>'
                "</table></div>")

        body = PAGE.format(body="\n".join(parts)).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9120)
    a = ap.parse_args()
    # 127.0.0.1 ONLY - this page prints the password in the clear.
    srv = HTTPServer(("127.0.0.1", a.port), Handler)
    print(f"Pairing page: http://localhost:{a.port}/   (localhost only, no login)")
    print("Ctrl+C to stop.")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
