"""Quiet scheduler entry: cheap loopback probe; hidden recovery only if needed.
No keys are read. No live process is killed. Existing dashboard is never restarted.
Use pythonw.exe from Task Scheduler; stdout is not a health signal.
"""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parent


def healthy(port: int, timeout: float = 3.0) -> bool:
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(f'http://127.0.0.1:{port}/api/status', timeout=timeout) as response:
            data = json.loads(response.read(262144))
        return isinstance(data, dict) and bool(data.get('version'))
    except (OSError, ValueError):
        return False


def run(port: int = 9119, probe=healthy, runner=subprocess.run) -> dict:
    started = time.perf_counter()
    if probe(port):
        return {'ok': True, 'mode': 'healthy-fast-path', 'elapsed_s': time.perf_counter()-started, 'recovery_spawned': False}
    env = os.environ.copy()
    env.update(HERMES_HOME='D:/.hermes', TEMP='D:/Temp', TMP='D:/Temp')
    # CREATE_NO_WINDOW, not -WindowStyle Hidden (which hides only after creation).
    command = ['powershell.exe', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', str(ROOT/'hermes-watchdog.ps1'), '-Port', str(port), '-NoReclaim']
    try:
        result = runner(command, cwd=str(ROOT), env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=0x08000000 if os.name == 'nt' else 0, timeout=150)
        ok = result.returncode == 0 and probe(port)
        return {'ok': ok, 'mode': 'recovery', 'recovery_spawned': True, 'elapsed_s': time.perf_counter()-started}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {'ok': False, 'mode': 'recovery-error', 'error_type': type(error).__name__, 'recovery_spawned': True, 'elapsed_s': time.perf_counter()-started}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=9119)
    parser.add_argument('--json', action='store_true')
    args = parser.parse_args()
    if not 1 <= args.port <= 65535: parser.error('port out of range')
    result = run(args.port)
    # Fixed, bounded, non-secret diagnostic; retain errors even under pythonw.
    target = ROOT / 'headless-watchdog-status.json'
    tmp = target.with_suffix(f'.{os.getpid()}.tmp')
    tmp.write_text(json.dumps(result), encoding='utf-8'); tmp.replace(target)
    if args.json and __import__('sys').stdout is not None: print(json.dumps(result))
    return 0 if result['ok'] else 1

if __name__ == '__main__':
    raise SystemExit(main())
