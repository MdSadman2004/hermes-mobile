#!/usr/bin/env python3
r"""foundry_session_sync.py - replicate this PC's chat records to foundry.

WHY
  The phone pairs to foundry (the always-on node). For "all my chats are
  readable from the phone even when the PC is off", foundry must hold a
  replica of this PC's conversations. This script pushes TOP-LEVEL sessions
  Windows -> foundry using Hermes's own export/import surfaces (no DB surgery
  on either side):

    1. `hermes sessions export --format jsonl --session-id <id> -`
    2. POST /api/sessions/import on foundry
       (gated login first: /auth/password-login -> session cookie)

  Rows the session lists hide by design (delegate sub-agent runs, tool rows,
  compression continuations) are never exported: replicating them adds weight
  no list will ever show.

LEDGER + REFRESH
  The ledger (JSON) records session_id -> exported message_count. Re-runs skip
  unchanged sessions; a session that GREW since the last sync is refreshed by
  bulk-delete on foundry + re-import.

USAGE
  python foundry_session_sync.py --dry-run            # plan only
  python foundry_session_sync.py --limit 10           # push 10 newest
  python foundry_session_sync.py                      # default budget

SCHEDULING (Windows): wire into Task Scheduler or a Hermes cron once the
manual runs look right; the script is idempotent and safe to re-run.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.request
from http.cookiejar import CookieJar
from pathlib import Path

HERMES_HOME = os.environ.get("HERMES_SYNC_HOME", r"D:\.hermes")
STATE_DB = Path(HERMES_HOME) / "state.db"
LEDGER = Path(os.environ.get("FOUNDRY_SYNC_LEDGER", r"D:\.hermes\foundry\session-sync-ledger.json"))
FOUNDRY = os.environ.get("FOUNDRY_SYNC_HOST", "100.121.66.11")
PORT = int(os.environ.get("FOUNDRY_SYNC_PORT", "9119"))
USER = os.environ.get("FOUNDRY_SYNC_USER", "sadman")
SSH_ALIAS = os.environ.get("FOUNDRY_SYNC_SSH", "foundry")

# Sources worth replicating: the user-facing ones. cron/tool rows are autonomy
# noise (and foundry runs its own jarvis anyway); delegate children are hidden
# by design. Alerts/oneshots pass through; 1-2 message sessions are filtered by
# --min-messages.
SKIP_SOURCES = ("tool", "cron")


def log(msg: str) -> None:
    print(msg, flush=True)


CACHED_PW = Path(os.environ.get("FOUNDRY_SYNC_PW_FILE", r"D:\.hermes\foundry\dashboard-password.txt"))


def foundry_password(password_file: str | None) -> str:
    """Local cache first — scheduled-task contexts can't rely on interactive
    ssh (a prompt there hangs until timeout). ssh stays as a non-interactive
    fallback so it fails fast instead of parking on a prompt."""
    if password_file:
        return Path(password_file).read_text(encoding="utf-8").strip()
    if CACHED_PW.exists():
        pw = CACHED_PW.read_text(encoding="utf-8").strip()
        if pw:
            return pw
    r = subprocess.run(
        ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", SSH_ALIAS,
         "cat ~/foundry-secrets/dashboard-password.txt"],
        capture_output=True, text=True, timeout=30,
    )
    if r.returncode != 0:
        raise SystemExit(f"ssh {SSH_ALIAS} failed: {r.stderr.strip()}")
    pw = r.stdout.strip()
    if not pw:
        raise SystemExit("empty password from foundry")
    return pw


class Foundry:
    """Cookie-authenticated client for foundry's dashboard (the gated flow)."""

    def __init__(self, base: str, password: str) -> None:
        self.base = base
        self.jar = CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar))
        self._login(password)

    def _post_json(self, path: str, payload, timeout: int = 120) -> dict:
        req = urllib.request.Request(
            self.base + path,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with self.opener.open(req, timeout=timeout) as resp:
                return json.loads(resp.read().decode("utf-8") or "{}")
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "replace")[:400]
            raise SystemExit(f"HTTP {e.code} on {path}: {body}") from e

    def _login(self, password: str) -> None:
        out = self._post_json("/auth/password-login", {
            "provider": "basic", "username": USER, "password": password, "next": "/",
        }, timeout=30)
        if not out.get("ok"):
            raise SystemExit(f"login rejected: {out}")

    def import_sessions(self, sessions: list) -> dict:
        return self._post_json("/api/sessions/import", {"sessions": sessions})

    def bulk_delete(self, ids: list) -> dict:
        return self._post_json("/api/sessions/bulk-delete", {"ids": ids})


def pick_candidates(limit: int, min_messages: int) -> list[dict]:
    """Newest top-level user-facing sessions from the local store."""
    conn = sqlite3.connect(f"file:{STATE_DB.as_posix()}?mode=ro", uri=True)
    try:
        placeholders = ",".join("?" for _ in SKIP_SOURCES)
        rows = conn.execute(
            f"""
            SELECT id, title, message_count
            FROM sessions
            WHERE archived = 0
              AND json_extract(model_config, '$._delegate_from') IS NULL
              AND source NOT IN ({placeholders})
              AND message_count >= ?
            ORDER BY last_activity_at DESC
            LIMIT ?
            """,
            (*SKIP_SOURCES, min_messages, limit),
        ).fetchall()
    finally:
        conn.close()
    return [{"id": r[0], "title": r[1] or "", "messages": r[2]} for r in rows]


def hermes_cli() -> str:
    """Pin the CLI: scheduled-task contexts don't inherit the shell PATH."""
    override = os.environ.get("HERMES_SYNC_CLI")
    if override:
        return override
    found = shutil.which("hermes")
    if found:
        return found
    for cand in (r"D:\.hermes\hermes-agent\venv\Scripts\hermes.exe",):
        if Path(cand).exists():
            return cand
    raise SystemExit("hermes CLI not found; set HERMES_SYNC_CLI")


def export_session(sid: str) -> dict:
    """`hermes sessions export --format jsonl --session-id <id> -` -> dict.

    Bytes in, explicit utf-8 out: the CLI emits UTF-8 (em-dashes etc.), and a
    Windows text-mode pipe decodes it with the ANSI codepage and dies — exactly
    what a scheduled-task context hits where the shell locale differs.
    """
    env = dict(os.environ, HERMES_HOME=HERMES_HOME)
    r = subprocess.run(
        [hermes_cli(), "sessions", "export", "--format", "jsonl",
         "--session-id", sid, "-"],
        capture_output=True, env=env, timeout=300,
    )
    if r.returncode != 0:
        raise RuntimeError(
            f"export failed for {sid}: {r.stderr.decode('utf-8', 'replace').strip()[:200]}")
    lines = [ln for ln in r.stdout.decode("utf-8", "replace").strip().splitlines() if ln.strip()]
    if not lines:
        raise RuntimeError(f"export produced no JSONL for {sid}")
    return json.loads(lines[0])


def load_ledger() -> dict:
    try:
        return json.loads(LEDGER.read_text(encoding="utf-8"))
    except Exception:
        return {}


def save_ledger(ledger: dict) -> None:
    LEDGER.parent.mkdir(parents=True, exist_ok=True)
    LEDGER.write_text(json.dumps(ledger, indent=1, sort_keys=True), encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=25, help="newest candidates to consider")
    ap.add_argument("--min-messages", type=int, default=3)
    ap.add_argument("--bare", action="store_true",
                    help="sync bare sessions too (default: skip 1-message noise)")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--password-file", default=None,
                    help="local file with the foundry dashboard password (default: ssh)")
    a = ap.parse_args()

    base = f"http://{FOUNDRY}:{PORT}"
    ledger = load_ledger()
    cands = pick_candidates(a.limit, 0 if a.bare else a.min_messages)

    fresh = [c for c in cands if c["id"] not in ledger]
    grown = [c for c in cands if c["id"] in ledger and
             ledger[c["id"]].get("messages", 0) != c["messages"]]
    same = [c for c in cands if c["id"] in ledger and
            ledger[c["id"]].get("messages", 0) == c["messages"]]

    log(f"candidates: {len(cands)}  fresh: {len(fresh)}  grown: {len(grown)}  "
        f"already-uptodate: {len(same)}")
    for c in fresh:
        log(f"  + fresh  {c['id']} | {c['title'][:48]} | msgs={c['messages']}")
    for c in grown:
        log(f"  ~ grown  {c['id']} | {c['title'][:48]} | msgs={c['messages']} "
            f"(was {ledger[c['id']].get('messages')})")

    if a.dry_run or not (fresh or grown):
        return 0

    client = Foundry(base, foundry_password(a.password_file))
    ok = fail = 0
    for c in fresh + grown:
        sid = c["id"]
        try:
            payload = export_session(sid)
        except Exception as e:
            log(f"  ! export {sid}: {e}")
            fail += 1
            continue
        try:
            if c in grown:
                client.bulk_delete([sid])
            out = client.import_sessions([payload])
            if not out.get("ok"):
                log(f"  ! import {sid}: {out.get('errors')}")
                fail += 1
                continue
            ledger[sid] = {"messages": c["messages"], "synced_at": time.time(),
                           "title": c["title"][:120], "action": "refresh" if c in grown else "import"}
            ok += 1
            log(f"  . synced {sid} | {c['title'][:48]}")
        except SystemExit as e:
            log(f"  ! {sid}: {e}")
            fail += 1

    save_ledger(ledger)
    log(f"done: synced={ok} failed={fail} ledger={LEDGER}")
    return 0 if fail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
