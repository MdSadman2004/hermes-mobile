#!/usr/bin/env python3
r"""hermes-dashboard.py — start the dashboard on localhost and ALWAYS show the QR.

WHY THIS EXISTS
  Two separate scripts used to own this: the watchdog started the dashboard but
  printed no QR, and hermes-remote.ps1 printed the QR but insisted on starting
  its own server. So the common case - "it is already running, just show me the
  code" - had no owner, and re-pairing meant killing a healthy dashboard.

  This script is idempotent: it reuses a healthy dashboard, starts one only if
  none is answering, and prints the pairing QR every single run either way.

BINDING
  Binds 0.0.0.0, which INCLUDES localhost. A localhost-only bind (127.0.0.1)
  cannot be reached by the phone at all - the QR would encode an address that
  only exists inside the PC. So: served on localhost for you, and on the LAN +
  Tailscale addresses the phone actually dials.

CREDENTIAL
  The QR carries the password from .dashboard-password. That file MUST match
  config.yaml's scrypt hash or the phone pairs with a credential the server
  rejects - the exact bug found 2026-09-09. This script verifies the match
  BEFORE printing a QR, and refuses rather than emit a QR that cannot work.

USAGE
  python hermes-dashboard.py            # reuse or start, then show QR
  python hermes-dashboard.py --restart  # force a fresh dashboard
  python hermes-dashboard.py --qr-only  # just print the QR
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERMES_HOME = Path(os.environ.get("HERMES_HOME", r"D:\.hermes"))
CONFIG = HERMES_HOME / "config.yaml"
PYTHON = HERMES_HOME / "hermes-agent" / "venv" / "Scripts" / "python.exe"
AGENT_DIR = HERMES_HOME / "hermes-agent"
PC_DIR = Path(r"D:\HermesMobile\pc")
PWFILE = PC_DIR / ".dashboard-password"
RENDER_QR = PC_DIR / "render_qr.py"
PORT = 9119


def status(host: str, port: int = PORT, timeout: float = 4.0):
    try:
        with urllib.request.urlopen(f"http://{host}:{port}/api/status", timeout=timeout) as r:
            return json.loads(r.read().decode())
    except Exception:
        return None


# Address resolution lives in netinfo.py, so this file, qr_server.py and the
# PowerShell scripts cannot drift apart again. A local copy here previously
# tested `startswith("100.")`, which matched non-tailnet addresses too.
from netinfo import is_tailnet as _is_tailnet, lan_ip, tailscale_ip  # noqa: E402


def credential_ok(pw: str) -> bool:
    """Refuse to print a QR whose password the server will reject."""
    m = re.search(r"password_hash:\s*(\S+)", CONFIG.read_text(encoding="utf-8"))
    if not m:
        return False
    try:
        _, n, r, p, salt_b64, hash_b64 = m.group(1).split("$")
        want = base64.b64decode(hash_b64)
        got = hashlib.scrypt(pw.encode(), salt=base64.b64decode(salt_b64),
                             n=int(n), r=int(r), p=int(p), dklen=len(want))
        return got == want
    except Exception:
        return False


def username() -> str:
    m = re.search(r"basic_auth:.*?username:\s*(\S+)", CONFIG.read_text(encoding="utf-8"), re.S)
    return m.group(1) if m else "sadman"


def kill_port(port: int = PORT) -> None:
    try:
        out = subprocess.run(["netstat", "-ano"], capture_output=True, text=True,
                             timeout=30).stdout
    except Exception:
        return
    for line in out.splitlines():
        if f":{port}" in line and "LISTENING" in line:
            pid = line.split()[-1]
            subprocess.run(["taskkill", "/PID", pid, "/T", "/F"],
                           capture_output=True, timeout=30)
            time.sleep(2)


def start() -> bool:
    """Bind 0.0.0.0 so localhost AND the phone's addresses all answer."""
    env = dict(os.environ, HERMES_HOME=str(HERMES_HOME))
    log = Path(os.environ.get("TEMP", r"D:\Temp")) / "hermes-dashboard.out.log"
    subprocess.Popen(
        [str(PYTHON), "-m", "hermes_cli.main", "dashboard",
         "--host", "0.0.0.0", "--port", str(PORT), "--no-open", "--skip-build"],
        cwd=str(AGENT_DIR), env=env,
        stdout=open(log, "ab"), stderr=subprocess.STDOUT,
        creationflags=getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0),
    )
    for _ in range(40):
        time.sleep(0.75)
        if status("127.0.0.1"):
            return True
    return False


def show_qr(st: dict) -> None:
    pw = PWFILE.read_text(encoding="utf-8").strip() if PWFILE.exists() else ""
    user = username()
    lan = lan_ip()
    ts = tailscale_ip()
    # Prefer the TAILNET address. It answers on home Wi-Fi AND on mobile data,
    # and never changes; a LAN IP is a DHCP lease and moves (this PC has been
    # seen on 10.103.133.152, 10.55.187.243 and 192.168.1.108 in one day).
    # Pairing to a LAN address is exactly what makes the phone work at home
    # and fail everywhere else.
    host = ts or lan
    away_ok = bool(ts)

    if st.get("auth_required"):
        if not pw:
            print("ERROR: auth_required but .dashboard-password is missing.")
            return
        if not credential_ok(pw):
            print("ERROR: .dashboard-password does NOT match config.yaml's hash.")
            print("       A QR built from it would pair with a rejected credential.")
            print("       Fix:  python D:\\Temp\\fix_dashboard_password.py")
            return
        payload = {"v": 1, "name": socket.gethostname(), "host": host, "port": PORT,
                   "auth": "gated", "provider": "basic",
                   "username": user, "password": pw,
                   "fingerprint": hashlib.sha256(f"{user}:{pw}".encode()).hexdigest()}
    else:
        payload = {"v": 1, "name": socket.gethostname(), "host": host, "port": PORT,
                   "auth": "open"}

    print()
    print(f"  Dashboard : http://localhost:{PORT}")
    if away_ok:
        print(f"  Pair host : http://{host}:{PORT}   <- TAILNET: works anywhere, never changes")
        print(f"  LAN       : http://{lan}:{PORT}   (home only - DHCP, do not pair to this)")
        print(f"  Tailscale : http://{ts}:{PORT}   <- away from home")
    else:
        print(f"  Pair host : http://{host}:{PORT}   <- LAN ONLY - unreachable off this Wi-Fi")
        print("  WARNING   : Tailscale is not up on this PC, so the QR cannot work away")
        print("              from home. Fix, then re-run:")
        print("                powershell -ExecutionPolicy Bypass -File D:\\HermesMobile\\pc\\internet-access.ps1")
    print(f"  Login     : {user} / {pw}")
    print(f"  Hermes    : v{st.get('version')}  gateway={st.get('gateway_state')}")
    print()

    blob = json.dumps(payload, separators=(",", ":"))
    try:
        subprocess.run([sys.executable, str(RENDER_QR)], input=blob, text=True, timeout=60)
    except Exception as e:
        print(f"(QR renderer failed: {e})\n{blob}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--restart", action="store_true")
    ap.add_argument("--qr-only", action="store_true")
    a = ap.parse_args()

    if a.restart:
        print("Restarting dashboard ...")
        kill_port()
        # kill_port() cannot stop a SYSTEM-owned child from a user token, and the
        # old code then fell through to "already running - reusing it", silently
        # leaving the STALE server up. After a password change that server still
        # holds the old hash, so the QR pairs against a credential it rejects.
        # Verify the port actually died; say so plainly when it did not.
        if status("127.0.0.1", timeout=3):
            print("  WARNING: could not stop the running dashboard (SYSTEM-owned).")
            print("  Run elevated:  powershell -File D:\\Temp\\pair-restart.ps1")
            print("  Continuing with the EXISTING server - if you just changed the")
            print("  password, pairing will fail until it is restarted.")
        else:
            print("  stopped.")

    st = status("127.0.0.1")
    if st and not a.qr_only:
        print(f"Dashboard already running (v{st.get('version')}) - reusing it.")
    elif not st:
        if a.qr_only:
            print("Dashboard is not running. Start it first (drop --qr-only).")
            return 1
        print("Starting dashboard on 0.0.0.0:%d ..." % PORT)
        if not start():
            print("FAILED to start. See %TEMP%\\hermes-dashboard.out.log")
            return 1
        st = status("127.0.0.1")
        print("Dashboard up.")

    if not st:
        print("Dashboard not answering on localhost.")
        return 1

    show_qr(st)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
