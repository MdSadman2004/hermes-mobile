#requires -Version 5.1
<#
.SYNOPSIS
  Make Hermes always-on: register the watchdog so the dashboard survives
  crashes, logouts, and reboots.

.DESCRIPTION
  Registers ONE scheduled task, "Hermes Always On", that runs
  `hermes-watchdog.ps1` at boot, at logon, and then every 2 minutes forever.

  Why a repeating check instead of a long-lived service:
  a supervisor process that loops can itself die, and then nothing is watching
  the watcher. A stateless check re-run every 2 minutes has no such failure
  mode - if a run crashes, the next one still fixes things. The check exits
  immediately when the dashboard is healthy, so the steady-state cost is one
  HTTP request to localhost every 2 minutes.

  Run as SYSTEM with -AtStartup so the dashboard is up before you log in, and
  keeps running after you log out.

.EXAMPLE
  # elevated PowerShell
  powershell -ExecutionPolicy Bypass -File D:\HermesMobile\pc\install-always-on.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File D:\HermesMobile\pc\install-always-on.ps1 -Remove
#>
param(
    [switch]$Remove,
    [switch]$RunAsUser,
    [int]$Port = 9119
)

$ErrorActionPreference = 'Stop'
$TaskName = 'Hermes Always On'
$Watchdog = Join-Path (Split-Path -Parent $PSCommandPath) 'hermes-watchdog.ps1'

function Write-Ok($m)   { Write-Host "  $m" -ForegroundColor Green }
function Write-Bad($m)  { Write-Host "  $m" -ForegroundColor Red }
function Write-Step($m) { Write-Host "  $m" -ForegroundColor Cyan }

$id = [Security.Principal.WindowsIdentity]::GetCurrent()
if (-not (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Bad "This needs administrator rights."
    Write-Host "  Right-click PowerShell, 'Run as administrator', then run this again."
    exit 1
}

Write-Host ""
Write-Host "Hermes - always-on setup" -ForegroundColor White
Write-Host ""

if ($Remove) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Write-Ok "Removed scheduled task '$TaskName'. The dashboard will no longer restart itself."
    exit 0
}

if (-not (Test-Path $Watchdog)) { throw "Missing watchdog script: $Watchdog" }

$QuietWatchdog = Join-Path (Split-Path -Parent $PSCommandPath) 'hermes-watchdog-hidden.vbs'
$QuietHost = Join-Path $env:WINDIR 'System32\wscript.exe'
if (-not (Test-Path $QuietWatchdog)) { throw "Missing quiet watchdog: $QuietWatchdog" }
if (-not (Test-Path $QuietHost)) { throw "Missing Windows Script Host: $QuietHost" }
$action = New-ScheduledTaskAction -Execute $QuietHost `
    -Argument ('//B //Nologo "{0}" {1}' -f $QuietWatchdog, $Port) `
    -WorkingDirectory (Split-Path -Parent $PSCommandPath)

# Boot covers "PC restarted"; logon covers "task was somehow not running";
# the repetition covers "dashboard died at 3am".
$triggers = @()
$atStartup = New-ScheduledTaskTrigger -AtStartup
$triggers += $atStartup

$atLogon = New-ScheduledTaskTrigger -AtLogOn
$triggers += $atLogon

# A repeating trigger needs a start time; begin one minute out.
$repeat = New-ScheduledTaskTrigger -Once -At ((Get-Date).AddMinutes(1)) `
    -RepetitionInterval (New-TimeSpan -Minutes 2)
$triggers += $repeat

$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 10) `
    -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)

if ($RunAsUser) {
    # The dashboard runs as you: it can read your profile, your keys, your
    # mapped drives. Choose this if SYSTEM cannot see something it needs.
    $principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Highest
    Write-Step "Principal: $env:USERNAME (interactive) - runs only while you are logged in"
} else {
    # SYSTEM starts before login and survives logout - but the dashboard shares
    # HERMES_HOME (D:\.hermes) with the desktop app, which runs as YOU. A SYSTEM
    # dashboard can therefore take ownership of state.db / session files the
    # desktop client then fails to write. If you hit that, re-run with -RunAsUser
    # and add a boot trigger under an S4U principal (runs as you, no stored
    # password) instead of falling back to an interactive-only task.
    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    Write-Step "Principal: SYSTEM - starts before login and survives logout"
    Write-Step "NOTE: shares D:\.hermes with your desktop Hermes; see the comment here if file ownership bites."
}

Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue

# The deployed task has historically been registered under a DIFFERENT name
# ('Hermes Always On (user)'), issued by an earlier revision. Leaving it in place
# while adding this one puts TWO watchdogs against port 9119, and they can both
# decide the port is free and spawn a dashboard - the "half-dead dashboard holding
# the port" failure. Remove any legacy name in the same run.
foreach ($legacy in @('Hermes Always On (user)')) {
    $old = Get-ScheduledTask -TaskName $legacy -ErrorAction SilentlyContinue
    if ($old) {
        Unregister-ScheduledTask -TaskName $legacy -Confirm:$false -ErrorAction SilentlyContinue
        Write-Step "Removed legacy task '$legacy' (would have raced this one for port $Port)."
    }
}

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $triggers `
    -Settings $settings -Principal $principal `
    -Description 'Keeps the Hermes dashboard answering on the LAN so the phone app can always reach it.' | Out-Null

Write-Ok "Registered '$TaskName' (at boot, at logon, then every 2 minutes)."

Start-ScheduledTask -TaskName $TaskName
Write-Step "Kicked off the first run..."

# Poll the LAN address, not just loopback: the dashboard binds ONLY to the LAN
# IP, so a 127.0.0.1 check reports failure while the server is perfectly up.
$lanIp = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -match '^(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.)' } |
    Where-Object {
        (Get-NetAdapter -InterfaceIndex $_.InterfaceIndex -ErrorAction SilentlyContinue).InterfaceDescription `
            -notmatch 'Hyper-V|Virtual|VMware|Tailscale|Loopback'
    } | Select-Object -First 1).IPAddress

$deadline = (Get-Date).AddSeconds(45)
$up = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    foreach ($candidate in @($lanIp, '127.0.0.1')) {
        if (-not $candidate) { continue }
        try {
            $r = Invoke-RestMethod -Uri "http://${candidate}:$Port/api/status" -TimeoutSec 3
            if ($r.version) { $up = $true; break }
        } catch { }
    }
    if ($up) { break }
}

Write-Host ""
if ($up) {
    Write-Ok "Dashboard is answering. Hermes will now stay up on its own."
} else {
    Write-Bad "Dashboard did not answer within 45s. Check pc\watchdog.log."
}
Write-Host ""
Write-Host "  Status:  Get-ScheduledTask -TaskName '$TaskName'"
Write-Host "  Log:     $(Join-Path (Split-Path -Parent $PSCommandPath) 'watchdog.log')"
Write-Host "  Remove:  powershell -File `"$PSCommandPath`" -Remove"
Write-Host ""
