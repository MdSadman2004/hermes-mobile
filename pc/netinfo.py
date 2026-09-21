#!/usr/bin/env python3
r"""netinfo.py - one source of truth for "which address should the phone dial".

WHY THIS EXISTS
  Three scripts each carried their own copy of this logic, and two of them tested
  `ip.startswith("100.")` to decide what a Tailscale address looks like. That is
  wrong: the tailnet is 100.64.0.0/10, so 100.0.0.5 and 100.200.0.5 also match a
  bare "100." prefix. Emitting one of those as the pairing host produces a QR that
  can never work, and the phone reports it exactly like a dead server.

  One implementation, one place to be wrong.

PAIRING TARGET
  Prefer the TAILNET address. It answers on home Wi-Fi AND on mobile data and
  never changes; a LAN IP is a DHCP lease that moves. This PC has been observed on
  10.103.133.152, 10.55.187.243 and 192.168.1.108 within a single day, so a
  LAN-paired profile works at home and fails everywhere else.
"""
from __future__ import annotations

import os
import socket
import subprocess
from pathlib import Path

__all__ = ["is_tailnet", "tailscale_ip", "lan_ip", "pair_host", "tailscale_cli"]


def is_tailnet(ip: str) -> bool:
    """True only for the tailnet range 100.64.0.0/10 (second octet 64..127)."""
    try:
        o = [int(p) for p in str(ip).strip().split(".")]
    except Exception:
        return False
    if len(o) != 4 or any(x < 0 or x > 255 for x in o):
        return False
    return o[0] == 100 and 64 <= o[1] <= 127


def tailscale_cli() -> Path:
    return (Path(os.environ.get("ProgramFiles", r"C:\Program Files"))
            / "Tailscale" / "tailscale.exe")


def tailscale_ip() -> str:
    """This machine's tailnet IPv4, or "" when Tailscale is absent or not up.

    The CLI is authoritative - it answers once the service is running even when
    the adapter is not in the hostname's address list, which is the case that
    made a getaddrinfo-only resolver silently return "" and fall back to a LAN IP.
    """
    exe = tailscale_cli()
    if exe.exists():
        try:
            out = subprocess.run([str(exe), "ip", "-4"], capture_output=True,
                                 text=True, timeout=10).stdout
            for tok in out.split():
                if is_tailnet(tok):
                    return tok
        except Exception:
            pass
    try:
        for *_, sa in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            if is_tailnet(sa[0]):
                return sa[0]
    except Exception:
        pass
    return ""


def lan_ip() -> str:
    """The LAN address the phone could dial. Never 127.0.0.1 (unreachable off-box).

    A UDP connect to 8.8.8.8 sends nothing - it only asks the routing table which
    local address would be used, which avoids picking a Hyper-V/WSL/VPN adapter
    the way gethostname() does.
    """
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if not ip.startswith("127."):
            return ip
    except Exception:
        pass
    try:
        for *_, sa in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            if not sa[0].startswith("127."):
                return sa[0]
    except Exception:
        pass
    return "127.0.0.1"


def pair_host() -> tuple[str, bool]:
    """(host, works_away_from_home). Tailnet first, LAN only as a last resort."""
    ts = tailscale_ip()
    if ts:
        return ts, True
    return lan_ip(), False
