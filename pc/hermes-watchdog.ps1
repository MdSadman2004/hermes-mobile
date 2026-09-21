#requires -Version 5.1
<#
.SYNOPSIS
  Keep the Hermes dashboard alive: check, and start it if it is not answering.

.DESCRIPTION
  Designed to be run repeatedly by Task Scheduler (every 2 minutes) rather than
  to loop forever itself. A supervisor that loops is a supervisor that can die
  silently; a scheduled check is stateless, so if THIS script crashes the next
  run two minutes later simply fixes things. Nothing to restart, nothing to
  monitor, no PID files to go stale.

  Idempotent by construction: if `/api/status` answers, it exits immediately and
  touches nothing. Only a dead endpoint causes a start.

  It re-resolves the LAN IP on every run, so a DHCP lease change moves the bind
  automatically instead of leaving the dashboard listening on an address the PC
  no longer holds.

  Writes one line per state change to pc\watchdog.log (a line every 2 minutes
  would be noise, so a healthy check is silent).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File D:\HermesMobile\pc\hermes-watchdog.ps1
#>
param(
    [int]$Port = 9119,
    [switch]$NoReclaim,
    [switch]$Verbose
)

$ErrorActionPreference = 'Stop'

$Root       = Split-Path -Parent $PSCommandPath
$HermesHome = 'D:\.hermes'
$Python     = Join-Path $HermesHome 'hermes-agent\venv\Scripts\python.exe'
$AgentDir   = Join-Path $HermesHome 'hermes-agent'
$LogFile    = Join-Path $Root 'watchdog.log'
$StateFile  = Join-Path $Root '.watchdog-state'

function Write-Log([string]$msg) {
    $line = "{0}  {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
    Add-Content -LiteralPath $LogFile -Value $line -ErrorAction SilentlyContinue
    if ($Verbose) { Write-Host $line }
    # Keep the log from growing without bound (checked cheaply, rarely trimmed).
    if ((Test-Path $LogFile) -and (Get-Item $LogFile).Length -gt 512KB) {
        $keep = Get-Content -LiteralPath $LogFile -Tail 500
        Set-Content -LiteralPath $LogFile -Value $keep
    }
}

# Only log a transition, not every healthy poll.
function Set-State([string]$state) {
    $previous = if (Test-Path $StateFile) { (Get-Content -LiteralPath $StateFile -Raw).Trim() } else { '' }
    if ($previous -ne $state) {
        Set-Content -LiteralPath $StateFile -Value $state
        return $true
    }
    return $false
}

function Get-LanIp {
    $candidates = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -match '^(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.)' -and
            $_.IPAddress -notmatch '^169\.254\.'
        }
    $best = $null; $bestMetric = [int]::MaxValue
    foreach ($addr in $candidates) {
        $iface = Get-NetIPInterface -InterfaceIndex $addr.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue
        if (-not $iface -or $iface.ConnectionState -ne 'Connected') { continue }
        $adapter = Get-NetAdapter -InterfaceIndex $addr.InterfaceIndex -ErrorAction SilentlyContinue
        if (-not $adapter -or $adapter.Status -ne 'Up') { continue }
        if ($adapter.InterfaceDescription -match 'Hyper-V|Virtual|VMware|VirtualBox|Loopback|TAP-|Tailscale|WireGuard') { continue }
        if ([int]$iface.InterfaceMetric -lt $bestMetric) {
            $bestMetric = [int]$iface.InterfaceMetric; $best = $addr.IPAddress
        }
    }
    return $best
}

function Get-TailscaleIp {
    # The phone reaches this PC over Tailscale when it is not on the home LAN.
    # Get-LanIp deliberately skips the Tailscale adapter (it must not be chosen
    # as the LAN bind), so resolve it separately here.
    $ts = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -match '^100\.' } |
        Select-Object -First 1
    if ($ts) { return $ts.IPAddress }
    return $null
}

function Test-Dashboard([string]$bindIp) {
    # NB: not $host - that is a PowerShell automatic variable and assigning to
    # it in a foreach throws at runtime (a parse check will not catch it).
    # Probe every path the phone might use: LAN, Tailscale, loopback. A dashboard
    # bound to 0.0.0.0 answers on all three; one bound to a stale LAN IP answers
    # on none, which is the failure the phone sees as "paired but dead".
    foreach ($candidate in @($bindIp, (Get-TailscaleIp), '127.0.0.1')) {
        if (-not $candidate) { continue }
        try {
            $r = Invoke-RestMethod -Uri "http://${candidate}:$Port/api/status" -TimeoutSec 4
            if ($r.version) { return $candidate }
        } catch { }
    }
    return $null
}

# ---------------------------------------------------------------------- run

$lan = Get-LanIp
$tailscale = Get-TailscaleIp
if (-not $lan -and -not $tailscale) {
    # v1 exited here whenever Get-LanIp returned nothing, which is what silently
    # killed the phone link overnight on 2026-09-09 ("No LAN address; nothing to
    # bind"). Tailscale alone is a perfectly good path to the phone, and even
    # with no network at all the dashboard should still be up on loopback so the
    # link restores the instant a network returns.
    if (Set-State 'no-network') { Write-Log "No LAN and no Tailscale address. Will still serve loopback." }
}

# Bind ALL interfaces. Binding a single LAN IP means a DHCP lease change, a
# Wi-Fi/ethernet switch, or a Tailscale-only session leaves the dashboard
# listening on an address the phone can no longer reach, with no error anywhere.
$bind = '0.0.0.0'
$probe = if ($lan) { $lan } elseif ($tailscale) { $tailscale } else { '127.0.0.1' }

$alive = Test-Dashboard $probe
if ($alive) {
    if (Set-State "up:$alive") { Write-Log "Dashboard healthy on ${alive}:$Port (lan=$lan ts=$tailscale)" }
    exit 0
}

# Not answering. A process may be holding the port without serving — a hung or
# half-dead dashboard. The v1 policy was to report and leave it alone, which is
# safe but means a zombie holds the port FOREVER: the phone keeps showing
# "paired" and never reconnects, because nothing ever clears the squatter. That
# is the single most common way this system fails in practice.
#
# So: leave a FOREIGN process alone (we do not know what it is), but reclaim the
# port when the squatter is one of OUR OWN dashboards that has stopped
# answering. Identified by matching the process image against the venv python
# that this script launches, so an unrelated python service is never touched.
$busy = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($busy) {
    if ($NoReclaim) {
        Write-Log "Port $Port is occupied but health probe failed; non-disruptive mode leaves every live process untouched."
        Set-State 'needs-attention-port-busy' | Out-Null
        exit 1
    }
    $squatterPid = [int]$busy[0].OwningProcess
    $squatter = Get-Process -Id $squatterPid -ErrorAction SilentlyContinue
    $isOurs = $false
    if ($squatter) {
        try {
            $cmd = (Get-CimInstance Win32_Process -Filter "ProcessId=$squatterPid" -ErrorAction Stop).CommandLine
            # Ours if it is the dashboard module, or the interpreter we start it with.
            if ($cmd -and ($cmd -match 'hermes_cli' -or $cmd -match [regex]::Escape($Python))) { $isOurs = $true }
        } catch {
            # No CIM access (e.g. a SYSTEM-owned process seen from a user token):
            # fall back to the image path, which is still specific to our venv.
            try { if ($squatter.Path -and $squatter.Path -eq $Python) { $isOurs = $true } } catch { }
        }
    }

    if (-not $isOurs) {
        # Last resort: we could not READ the process (a SYSTEM-owned process is
        # opaque to a user token — both Path and CommandLine come back empty).
        # Port $Port is Hermes's dedicated port by configuration, and this branch
        # is only reached when /api/status has already failed. A python process
        # squatting our own port while refusing to serve is, for our purposes,
        # a dead Hermes: reclaim it. A non-python squatter is still left alone.
        if ($squatter -and $squatter.ProcessName -like 'python*') {
            Write-Log "Port $Port held by opaque python PID $squatterPid that is not answering; treating as a dead dashboard."
            $isOurs = $true
        }
    }

    if (-not $isOurs) {
        Write-Log "Port $Port held by PID $squatterPid (not ours); leaving it alone."
        Set-State 'port-busy-foreign' | Out-Null
        exit 0
    }

    Write-Log "Port $Port held by our own unresponsive dashboard (PID $squatterPid). Reclaiming."
    try {
        Stop-Process -Id $squatterPid -Force -ErrorAction Stop
    } catch {
        # Running as a user against a SYSTEM-owned process: taskkill can still
        # do it when this script itself runs elevated/as SYSTEM, which is how
        # the scheduled task runs.
        & taskkill.exe /F /PID $squatterPid 2>&1 | Out-Null
    }
    Start-Sleep -Seconds 2
    $still = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($still) {
        Write-Log "Could not reclaim port $Port from PID $squatterPid; will retry next cycle."
        Set-State 'port-stuck' | Out-Null
        exit 0
    }
    Write-Log "Reclaimed port $Port."
}

Set-State 'starting' | Out-Null
Write-Log "Dashboard not answering. Starting on ${bind}:$Port (lan=$lan ts=$tailscale) ..."

$password = $null
$pwFile = Join-Path $Root '.dashboard-password'
if (Test-Path $pwFile) { $password = (Get-Content -LiteralPath $pwFile -Raw).Trim() }

$stdout = Join-Path $env:TEMP 'hermes-dashboard.out.log'
$stderr = Join-Path $env:TEMP 'hermes-dashboard.err.log'

<#
  HERMES_HOME must be set EXPLICITLY, not inherited.

  It is defined as a per-USER environment variable, so a task running as SYSTEM
  does not see it. Hermes then resolves a different home, finds no
  dashboard.basic_auth there, and refuses to bind off-loopback with
  "Configure an auth provider before exposing the dashboard" - which reads like
  a config problem and is actually an environment problem. Setting it here
  makes the watchdog behave identically under SYSTEM, under your login, and
  from an interactive shell.
#>
$env:HERMES_HOME = $HermesHome

$proc = Start-Process -FilePath $Python -PassThru -WindowStyle Hidden `
    -ArgumentList @('-m', 'hermes_cli.main', 'dashboard',
                    '--host', $bind, '--port', "$Port", '--no-open', '--skip-build') `
    -WorkingDirectory $AgentDir `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr

for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Milliseconds 750
    if ($proc.HasExited) { break }
    if (Test-Dashboard $lan) {
        Write-Log "Dashboard back up on ${lan}:$Port (pid $($proc.Id))"
        Set-State "up:$lan" | Out-Null
        exit 0
    }
}

$tail = if (Test-Path $stderr) { (Get-Content -LiteralPath $stderr -Tail 5 -ErrorAction SilentlyContinue) -join ' | ' } else { '' }
Write-Log "Dashboard failed to come up. stderr: $tail"
Set-State 'failed' | Out-Null
exit 1
