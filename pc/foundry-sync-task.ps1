# Scheduled entry point for Windows -> foundry chat replication.
# Registered as task "Hermes-FoundryChatSync" (every 30 minutes).
Set-Location D:\HermesMobile
& "D:\.hermes\hermes-agent\venv\Scripts\python.exe" "D:\HermesMobile\pc\foundry_session_sync.py" --limit 40 2>&1 | Out-File -FilePath "D:\HermesMobile\pc\foundry-sync.log" -Encoding utf8 -Append
exit $LASTEXITCODE
