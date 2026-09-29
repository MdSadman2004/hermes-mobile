#!/usr/bin/env python3
r"""artifact_server.py — compile TSX/JSX artifacts on the PC, serve them to the phone.

WHY PC-SIDE COMPILATION
  The agent writes a React/TSX component; the phone renders it. Doing the
  TypeScript->JS transform on the phone would mean shipping Babel-standalone
  (~3 MB) into a WebView and paying that parse cost on every render. The PC
  already has Node, so esbuild does it in ~20 ms and the phone receives plain
  ES5-ish JS it can execute immediately.

  Fallback: if esbuild is unavailable, the server says so explicitly and the
  client shows the source with a reason. It never silently ships broken JS.

SECURITY
  * Binds 127.0.0.1 by default. The dashboard proxies it; nothing here is
    directly exposed to the network.
  * Compiled artifacts are sandboxed in the client WebView (no file access,
    no same-origin access to the dashboard).
  * Source is capped at 512 KB; esbuild runs with a hard timeout and is
    tree-killed on expiry so a pathological input cannot wedge the service.

ENDPOINTS
  POST /compile        {source, lang}       -> {ok, js, css, warnings, ms}
  GET  /artifact/<id>                       -> full standalone HTML page
  GET  /health                              -> {ok, esbuild, node}

USAGE
  python artifact_server.py [--port 9121] [--host 127.0.0.1]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MAX_SOURCE = 512 * 1024
COMPILE_TIMEOUT = 30
STORE: dict[str, dict] = {}

ROOT = Path(os.environ.get("HERMES_HOME", r"D:\.hermes"))
CACHE = ROOT / "artifacts"
CACHE.mkdir(parents=True, exist_ok=True)


def _which(name: str) -> str | None:
    p = shutil.which(name)
    if p:
        return p
    if os.name == "nt":
        for ext in (".cmd", ".exe", ".bat"):
            p = shutil.which(name + ext)
            if p:
                return p
    return None


def esbuild_available() -> tuple[bool, str]:
    exe = _which("esbuild")
    if exe:
        try:
            out = subprocess.run([exe, "--version"], capture_output=True,
                                 text=True, timeout=20)
            if out.returncode == 0:
                return True, out.stdout.strip()
        except Exception:
            pass
    npx = _which("npx")
    if npx:
        try:
            out = subprocess.run([npx, "--yes", "esbuild", "--version"],
                                 capture_output=True, text=True, timeout=90)
            if out.returncode == 0:
                return True, out.stdout.strip()
        except Exception:
            pass
    return False, ""


_ESBUILD_CMD: list[str] | None = None


def _resolve_esbuild() -> list[str] | None:
    """Resolve the fastest esbuild invocation ONCE and cache it.

    `npx esbuild` re-resolves the package on every call: measured 3046 ms per
    compile versus ~20 ms for a direct binary. npx also prints "npm notice ..."
    to stderr, which was being surfaced to the user as a compiler warning.
    Prefer a real binary; fall back to npx only when there is none.
    """
    global _ESBUILD_CMD
    if _ESBUILD_CMD is not None:
        return _ESBUILD_CMD or None

    exe = _which("esbuild")
    if exe:
        _ESBUILD_CMD = [exe]
        return _ESBUILD_CMD

    # npx caches the package under node_modules/.bin after first use; look there.
    for base in (Path.cwd(), Path(__file__).resolve().parent):
        cand = base / "node_modules" / ".bin" / ("esbuild.cmd" if os.name == "nt" else "esbuild")
        if cand.exists():
            _ESBUILD_CMD = [str(cand)]
            return _ESBUILD_CMD

    npx = _which("npx")
    _ESBUILD_CMD = [npx, "--yes", "esbuild"] if npx else []
    return _ESBUILD_CMD or None


def compile_tsx(source: str, lang: str = "tsx") -> dict:
    """Transform TSX/JSX to browser-ready JS. Never raises; returns ok=False."""
    if len(source) > MAX_SOURCE:
        return {"ok": False, "error": f"source exceeds {MAX_SOURCE // 1024} KB"}

    loader = {"tsx": "tsx", "jsx": "jsx", "ts": "ts", "js": "js"}.get(lang, "tsx")
    started = time.time()

    with tempfile.TemporaryDirectory(prefix="hermes-artifact-") as td:
        src = Path(td) / f"input.{loader}"
        src.write_text(source, encoding="utf-8")

        base = _resolve_esbuild()
        if not base:
            return {"ok": False, "error": "neither esbuild nor npx found on PATH"}

        cmd = base + [
            str(src),
            f"--loader:.{loader}={loader}",
            "--bundle",
            "--format=iife",
            "--global-name=__HermesArtifact",
            "--jsx=transform",
            "--jsx-factory=React.createElement",
            "--jsx-fragment=React.Fragment",
            "--target=es2018",
            # React/ReactDOM are provided by the page, never bundled.
            "--external:react",
            "--external:react-dom",
            "--external:react-dom/client",
        ]

        try:
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, text=True)
            out, err = proc.communicate(timeout=COMPILE_TIMEOUT)
        except subprocess.TimeoutExpired:
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"],
                               capture_output=True, timeout=20)
            else:
                proc.kill()
            return {"ok": False, "error": f"compile timed out after {COMPILE_TIMEOUT}s"}
        except Exception as e:  # noqa: BLE001
            return {"ok": False, "error": f"{type(e).__name__}: {e}"}

        if proc.returncode != 0:
            return {"ok": False, "error": (err or "esbuild failed").strip()[:4000]}

        # npm/npx chatter is not a compiler diagnostic - drop it so the user
        # never sees "npm notice run npx" presented as a warning about their code.
        warn = "\n".join(
            ln for ln in (err or "").splitlines()
            if ln.strip() and not ln.lstrip().startswith(("npm notice", "npm warn", "npm WARN"))
        ).strip()

        return {"ok": True, "js": out, "warnings": warn[:2000],
                "ms": int((time.time() - started) * 1000)}


PAGE = """<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<script src="/vendor/react.js"></script>
<script src="/vendor/react-dom.js"></script>
<style>
 :root {{
   --bg:#070A09; --panel:#0E1512; --line:#1E2A25;
   --fg:#D8E0DA; --muted:#7C8B84; --accent:#5FD3A0;
 }}
 *{{box-sizing:border-box}}
 html,body{{margin:0;padding:0;background:var(--bg);color:var(--fg);
   font:15px/1.55 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;}}
 #root{{padding:14px;min-height:100vh}}
 .hermes-error{{background:#1A0F0F;border:1px solid #4A2020;color:#FF9A8F;
   padding:14px;border-radius:10px;font:13px/1.5 ui-monospace,Menlo,monospace;
   white-space:pre-wrap;word-break:break-word}}
 .hermes-error b{{color:#FFC9C0;display:block;margin-bottom:6px}}
</style></head>
<body><div id="root"></div>
<script>
(function () {{
  var root = document.getElementById('root');
  function fail(title, detail) {{
    root.innerHTML = '';
    var d = document.createElement('div');
    d.className = 'hermes-error';
    var b = document.createElement('b'); b.textContent = title;
    d.appendChild(b); d.appendChild(document.createTextNode(detail || ''));
    root.appendChild(d);
    if (window.HermesBridge && HermesBridge.onError) HermesBridge.onError(title + ': ' + detail);
  }}
  window.onerror = function (m, s, l, c, e) {{ fail('Runtime error', (e && e.stack) || m); return true; }};
  try {{
{js}
  }} catch (e) {{ fail('Failed to evaluate component', (e && e.stack) || String(e)); return; }}

  try {{
    var mod = window.__HermesArtifact || {{}};
    var Comp = mod.default || mod.App || mod.Main ||
      (function () {{ for (var k in mod) if (typeof mod[k] === 'function') return mod[k]; }})();
    if (!Comp) return fail('No component exported',
      'Export a component as `export default function App() {{ ... }}`.');
    var el = React.createElement(Comp);
    if (ReactDOM.createRoot) ReactDOM.createRoot(root).render(el);
    else ReactDOM.render(el, root);
    if (window.HermesBridge && HermesBridge.onReady) HermesBridge.onReady();
  }} catch (e) {{ fail('Failed to render', (e && e.stack) || String(e)); }}
}})();
</script></body></html>
"""


class Handler(BaseHTTPRequestHandler):
    server_version = "HermesArtifact/1.0"

    def log_message(self, *a):
        pass

    def _send(self, code: int, body: bytes, ctype: str):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code: int, obj: dict):
        self._send(code, json.dumps(obj).encode(), "application/json")

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Methods", "POST,GET,OPTIONS")
        self.end_headers()

    def do_GET(self):
        if self.path == "/health":
            ok, ver = esbuild_available()
            return self._json(200, {"ok": True, "esbuild": ok, "esbuild_version": ver,
                                    "artifacts": len(STORE)})
        if self.path.startswith("/vendor/"):
            name = self.path.rsplit("/", 1)[-1]
            f = Path(__file__).resolve().parent / "artifact_vendor" / name
            if f.exists():
                return self._send(200, f.read_bytes(), "application/javascript")
            return self._send(404, b"// vendor asset missing", "application/javascript")
        if self.path.startswith("/artifact/"):
            aid = self.path.rsplit("/", 1)[-1].split("?")[0]
            rec = STORE.get(aid)
            if not rec:
                return self._send(404, b"<h3>artifact not found</h3>", "text/html")
            return self._send(200, rec["html"].encode(), "text/html; charset=utf-8")
        self._send(404, b"not found", "text/plain")

    def do_POST(self):
        if self.path != "/compile":
            return self._send(404, b"not found", "text/plain")
        try:
            n = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(n).decode() or "{}")
        except Exception as e:  # noqa: BLE001
            return self._json(400, {"ok": False, "error": f"bad request: {e}"})

        source = body.get("source") or ""
        lang = (body.get("lang") or "tsx").lower()
        if not source.strip():
            return self._json(400, {"ok": False, "error": "empty source"})

        res = compile_tsx(source, lang)
        if not res.get("ok"):
            return self._json(200, res)

        aid = hashlib.sha256(source.encode()).hexdigest()[:16]
        html = PAGE.format(js=res["js"])
        STORE[aid] = {"html": html, "ts": time.time()}
        try:
            (CACHE / f"{aid}.html").write_text(html, encoding="utf-8")
        except Exception:
            pass

        res["id"] = aid
        res["url"] = f"/artifact/{aid}"
        res.pop("js", None)  # the phone loads the page, not raw JS
        return self._json(200, res)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9121)
    ap.add_argument("--host", default="127.0.0.1")
    a = ap.parse_args()

    ok, ver = esbuild_available()
    print(f"esbuild: {'v' + ver if ok else 'NOT FOUND (compiles will fail)'}")
    srv = ThreadingHTTPServer((a.host, a.port), Handler)
    print(f"Artifact compiler on http://{a.host}:{a.port}  (POST /compile)")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
