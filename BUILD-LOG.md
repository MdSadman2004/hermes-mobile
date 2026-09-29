# Build Log — Live Conversation Continuity

## Implemented Android continuity changes

- `SessionRepository.resumeSession` now requires the gateway's live handle and preserves the authoritative stored session key; it never treats a stored ID as a live handle.
- `CockpitViewModel` starts the transcript listener before non-destructive `session.activate`, cancels stale binding/reconnect jobs, rejects activation failures before retaining the binding, and resolves stored sessions again after reconnect.
- `TranscriptRepository` now buffers session events during authoritative history hydration and replays them in order, preventing the first live deltas from being lost when a phone joins a running desktop turn. The cockpit starts that listener before `session.activate` and tears it down on activation failure or stale reconnect.
- Live-session adoption selects by `last_active`/`started_at` and carries the stored session key.
- Backend continuity work is opt-in and isolated: `D:\\.hermes\\hermes-agent\\tui_gateway\\cross_process_bridge.py` provides an HMAC-scoped SQLite-WAL event mailbox and atomic duplicate-submission claims; `tui_gateway/server.py` publishes/polls event frames when explicitly configured. The normal gateway remains unchanged unless both bridge environment variables are set.

## Two-process cross-process live continuity proof (no live gateway restart)

Added `D:\.hermes\hermes-agent\tests\tui_gateway\test_cross_process_live_events.py` — a true two-process subprocess harness. Each subprocess initializes its own `CrossProcessBridge` instance and shares only the authenticated SQLite-WAL mailbox.

- `test_two_processes_forward_live_turn_events`: Process A publishes 4 live turn event frames (`message.start`, `message.delta`, `message.complete`, `status.update`); Process B polls the mailbox and receives all 4 in order with correct payload. Proves cross-process event forwarding.
- `test_cross_process_submit_claim_exactly_once`: A submission claim is accepted once, duplicate rejected. Proves cross-process dedup.
- `test_bridge_fails_closed_on_profile_and_secret_mismatch`: Wrong profile polls empty; wrong secret → PermissionError. Proves auth boundaries.
- `test_bridge_session_keys_lists_active_sessions`: Returns only keys with traffic.
- `test_concurrent_cross_process_publish_is_monotonic`: 8 concurrent subprocess publishes yield strictly increasing ids 1-8. Proves concurrency safety.

Exact isolated command (exit 0):
```
D:/.hermes/hermes-agent/venv/Scripts/python.exe -m pytest -q tests/tui_gateway/test_cross_process_live_events.py tests/tui_gateway/test_cross_process_bridge.py tests/tui_gateway/test_cross_process_busy_continuity.py tests/tui_gateway/test_multi_client_fanout.py tests/test_tui_gateway_event_replay.py tests/tui_gateway/test_resume_live_profile_scope.py
# => 25 passed, 8 skipped in 4.66s
```

Isolated new-file test run (exit 0):
```
D:/.hermes/hermes-agent/venv/Scripts/python.exe -m pytest -v tests/tui_gateway/test_cross_process_live_events.py
# => 5 passed in 2.22s
```

Honest architecture limit: a separate process still cannot attach to another process's in-memory live runtime (`FanoutTransport`). The bridge forwards event frames and serializes submissions across processes, which is the smallest safe IPC path without changing the gateway's runtime ownership. Physical Android E2E remains unverifiable (no ADB device, live gateway/chat must not be restarted).

## Fresh run (verification without touching live services)

- Android: `export JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.8-hotspot' && ./gradlew testDebugUnitTest assembleDebug` → exit 0, BUILD SUCCESSFUL.
- Parsed 13 XML files under `app/build/test-results/testDebugUnitTest`: 83 tests, 0 failures, 0 errors, 0 skipped.
- APK: `D:\HermesMobile\app\build\outputs\apk\debug\app-debug.apk`, 46,583,146 bytes.
- `python -m compileall -q tui_gateway/server.py tui_gateway/cross_process_bridge.py`: exit 0.
- `D:/Studio/platform-tools/adb.exe devices -l`: only `List of devices attached`.
- Active gateway PID 17488 on port 9119: not restarted.

## Changed files

Android:
- `app/src/main/java/com/hermes/mobile/data/repo/SessionRepository.kt`
- `app/src/main/java/com/hermes/mobile/data/repo/TranscriptRepository.kt`
- `app/src/main/java/com/hermes/mobile/ui/cockpit/CockpitViewModel.kt`
- `app/src/main/java/com/hermes/mobile/ui/cockpit/CockpitScreen.kt`
- `app/build.gradle.kts`
- `pc/hermes-watchdog.ps1`
- `pc/install-always-on.ps1`
- `BUILD-LOG.md`

Backend:
- `tui_gateway/cross_process_bridge.py` (new)
- `tui_gateway/server.py` (opt-in bridge publish/poll)
- `tests/tui_gateway/test_cross_process_bridge.py` (new)
- `tests/tui_gateway/test_cross_process_busy_continuity.py` (new)
- `tests/tui_gateway/test_cross_process_live_events.py` (new)
