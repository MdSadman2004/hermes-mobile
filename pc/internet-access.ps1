<#
  internet-access.ps1 — make the phone reach this PC from ANY network, and prove it.

  WHY
    A LAN-paired phone works at home and fails everywhere else, and a LAN IP is a
    DHCP lease: this PC has been seen on 10.103.133.152, 10.55.187.243 and
    192.168.1.108 within one day. The tailnet address (100.64.0.0/10) is assigned
    per machine and answers on home Wi-Fi, mobile data and foreign networks alike.
    Pair once, to the tailnet, and it never needs touching again.

  IDEMPOTENT
    Run it whenever. It changes only what is actually wrong and then re-proves the
    link. Safe against a live dashboard: it reuses a healthy server instead of
    restarting it (a blind restart would drop the conversation you are in).

  WHAT "WORKS FROM ANYWHERE" REQUIRES (all six)
    1. Tailscale service Running / Automatic        (comes back after reboot)
    2. Tailscale ForceDaemon = true                 (comes back after logout/reboot)
    3. Tailscale adapter network category = Private (a Public profile makes Windows
       drop inbound BEFORE the firewall rule is even consulted)
    4. Firewall 'Hermes Remote dashboard': TCP 9119, Domain+Private, RemoteAddress
       includes LocalSubnet AND 100.64.0.0/10      (the tailnet is NOT the local
       subnet; this is the silent drop)
    5. Dashboard listening on 0.0.0.0:9119          (a 127.0.0.1 bind is unreachable)
    6. The GATED HANDSHAKE succeeds over the tailnet address — status -> login ->
       ws-ticket. A ping or a TCP connect is not proof; only an authenticated
       replay is.

  USAGE
    powershell -ExecutionPolicy Bypass -File D:\HermesMobile\pc\internet-access.ps1
#>
[CmdletBinding()]
param(
    [switch]$Quiet
)

$ErrorActionPreference = 'Continue'
$pc      = 'D:\HermesMobile\pc'
$py      = 'D:\.hermes\hermes-agent\venv\Scripts\python.exe'
$tsExe   = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
$port    = 9119
$rule    = 'Hermes Remote dashboard'
$log     = 'D:\Temp\internet-access.log'

function L($m) {
    if (-not $Quiet) { Write-Host $m }
    Add-Content -LiteralPath $log -Value $m -ErrorAction SilentlyContinue
}
function Head($m) { L ''; L "== $m ==" }

$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()
           ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
$fail = 0
$needsAdmin = 0

Set-Content -LiteralPath $log -Value "=== internet-access $(Get-Date) (admin=$isAdmin) ===" -ErrorAction SilentlyContinue

# --------------------------------------------------------------------------
# The tailnet address. "100." alone is NOT the tailnet: it is 100.64.0.0/10,
# i.e. second octet 64..127. Matching a bare prefix can hand out 100.200.x.x,
# which no tailnet can ever route - and it looks exactly like a dead PC.
# --------------------------------------------------------------------------
function Get-TailnetIp {
    if (-not (Test-Path $tsExe)) { return $null }
    foreach ($line in (& $tsExe ip -4 2>$null)) {
        $ip = $line.Trim()
        $o = $ip.Split('.')
        if ($o.Count -eq 4 -and $o[0] -eq '100' -and [int]$o[1] -ge 64 -and [int]$o[1] -le 127) {
            return $ip
        }
    }
    return $null
}

# ==========================================================================
# 1-2. Tailscale installed, running, auto-start, and daemon-mode
# ==========================================================================
Head '1. Tailscale service'
$svc = Get-Service Tailscale -ErrorAction SilentlyContinue
if (-not $svc) {
    L 'FAIL  Tailscale is not installed. Install it, sign in, then re-run.'
    $fail++
} else {
    if ($svc.Status -ne 'Running') {
        if ($isAdmin) {
            try { Start-Service Tailscale; Start-Sleep 3; L 'FIXED  service started' }
            catch { L "FAIL  could not start service: $($_.Exception.Message)"; $fail++ }
        } else { L 'FAIL  service not Running (needs admin)'; $fail++; $needsAdmin++ }
    } else { L 'OK    service Running' }

    if ($svc.StartType -ne 'Automatic') {
        if ($isAdmin) {
            try { Set-Service Tailscale -StartupType Automatic; L 'FIXED  start type -> Automatic' }
            catch { L "FAIL  could not set start type: $($_.Exception.Message)"; $fail++ }
        } else { L 'FAIL  start type is not Automatic (needs admin)'; $fail++; $needsAdmin++ }
    } else { L 'OK    start type Automatic' }
}

Head '2. Tailscale daemon mode (ForceDaemon)'
# Without ForceDaemon the tunnel dies at logout/reboot and needs an interactive
# login to come back - "it worked yesterday" then becomes "it is dead today".
$forceDaemon = $false
if (Test-Path $tsExe) {
    try {
        $prefs = (& $tsExe debug prefs 2>$null | Out-String)
        $forceDaemon = ($prefs -match '"ForceDaemon":\s*true')
    } catch { }
}
if ($forceDaemon) {
    L 'OK    ForceDaemon = true (tunnel survives logout and reboot)'
} else {
    if ($isAdmin) {
        L 'FIX   running: tailscale up --unattended'
        & $tsExe up --unattended 2>&1 | ForEach-Object { L "      $_" }
        $prefs = (& $tsExe debug prefs 2>$null | Out-String)
        if ($prefs -match '"ForceDaemon":\s*true') { L 'FIXED  ForceDaemon = true' }
        else { L 'FAIL  ForceDaemon still false'; $fail++ }
    } else { L 'FAIL  ForceDaemon = false (needs admin: tailscale up --unattended)'; $fail++; $needsAdmin++ }
}

$ts = Get-TailnetIp
if ($ts) { L "OK    tailnet address: $ts" }
else { L 'FAIL  no tailnet address - Tailscale is not logged in / not up'; $fail++ }

# ==========================================================================
# 3. Network category of the Tailscale adapter
# ==========================================================================
Head '3. Tailscale adapter network category'
# The firewall rule is scoped to Domain,Private. If Windows classifies the
# Tailscale adapter as Public, inbound is dropped before the rule is consulted,
# and every prompt looks like "the server is down".
$prof = Get-NetConnectionProfile -InterfaceAlias 'Tailscale' -ErrorAction SilentlyContinue
if (-not $prof) {
    L 'WARN  no connection profile for the Tailscale adapter (adapter may be down)'
} elseif ($prof.NetworkCategory -eq 'Private') {
    L 'OK    NetworkCategory = Private'
} else {
    if ($isAdmin) {
        try {
            Set-NetConnectionProfile -InterfaceAlias 'Tailscale' -NetworkCategory Private
            L 'FIXED NetworkCategory -> Private'
        } catch { L "FAIL  could not set category: $($_.Exception.Message)"; $fail++ }
    } else { L "FAIL  NetworkCategory = $($prof.NetworkCategory) (needs admin)"; $fail++; $needsAdmin++ }
}

# ==========================================================================
# 4. Firewall: existence is not enough, SCOPE is what blocks
# ==========================================================================
Head '4. Firewall rule scope'
$r = Get-NetFirewallRule -DisplayName $rule -ErrorAction SilentlyContinue
if (-not $r) {
    if ($isAdmin) {
        try {
            New-NetFirewallRule -DisplayName $rule -Direction Inbound -Action Allow `
                -Protocol TCP -LocalPort $port -Profile Domain,Private `
                -RemoteAddress @('LocalSubnet', '100.64.0.0/10') | Out-Null
            L 'FIXED rule created (LocalSubnet + 100.64.0.0/10)'
        } catch { L "FAIL  could not create rule: $($_.Exception.Message)"; $fail++ }
    } else { L 'FAIL  rule missing (needs admin)'; $fail++; $needsAdmin++ }
} else {
    $addr = ($r | Get-NetFirewallAddressFilter).RemoteAddress -join ','
    $pf   = $r | Get-NetFirewallPortFilter
    $profiles = $r.Profile
    L "      rule: enabled=$($r.Enabled) profile=$profiles port=$($pf.LocalPort) remote=$addr"

    $okTail = ($addr -match '100\.64\.0\.0' -or $addr -match '100\.64\.0\.0/10')
    $okSub  = ($addr -match 'LocalSubnet')
    $okProf = ("$profiles" -match 'Private')

    if ($okTail -and $okSub -and $okProf -and $r.Enabled) {
        L 'OK    inbound allowed from LAN and tailnet on the right profiles'
    } elseif ($isAdmin) {
        try {
            Set-NetFirewallRule -DisplayName $rule -Enabled True -Profile Domain,Private `
                -RemoteAddress @('LocalSubnet', '100.64.0.0/10')
            L 'FIXED scope -> LocalSubnet + 100.64.0.0/10 on Domain,Private'
        } catch { L "FAIL  could not rescope rule: $($_.Exception.Message)"; $fail++ }
    } else {
        L 'FAIL  scope does not cover the tailnet (needs admin)'; $fail++; $needsAdmin++
    }
}

# ==========================================================================
# 5. Dashboard must bind 0.0.0.0
# ==========================================================================
Head '5. Dashboard listen address'
$listen = netstat -ano | Select-String ":$port\s" | Select-String 'LISTENING'
if ($listen) {
    $bind = ($listen | ForEach-Object { ($_ -split '\s+')[2] } | Select-Object -Unique) -join ','
    if ($bind -match '0\.0\.0\.0') { L "OK    bound $bind (all interfaces)" }
    else { L "WARN  bound $bind only - the tailnet interface may not answer"; $fail++ }
} else {
    L "     nothing listening on $port - starting via hermes-dashboard.py"
    & $py (Join-Path $pc 'hermes-dashboard.py') --qr-only 2>&1 | ForEach-Object { L "      $_" }
    Start-Sleep 4
    if (netstat -ano | Select-String ":$port\s" | Select-String 'LISTENING') { L 'FIXED dashboard listening' }
    else { L 'FAIL  dashboard did not come up'; $fail++ }
}

# ==========================================================================
# 6. The only honest proof: the gated handshake over the TAILNET address
# ==========================================================================
Head '6. Gated handshake over the tailnet (status -> login -> ws-ticket)'
$verify = @('127.0.0.1')
if ($ts) { $verify += $ts }
foreach ($h in $verify) {
    $out = & $py (Join-Path $pc 'phone_sim.py') $h 2>&1 | Out-String
    $bad = ([regex]::Matches($out, 'FAIL')).Count
    if ($bad -eq 0 -and $out -match 'OK\s+3') {
        L "PASS  $h - full authenticated chain (incl. ws-ticket)"
    } else {
        L "FAIL  $h - $bad failed step(s)"
        ($out -split "`n" | Where-Object { $_ -match 'FAIL' } | Select-Object -First 3) |
            ForEach-Object { L "        $($_.Trim())" }
        $fail++
    }
}

# ==========================================================================
# 7. The pairing QR must carry the tailnet host
# ==========================================================================
Head '7. Pairing payload'
try {
    $p = Invoke-RestMethod -Uri 'http://localhost:9120/payload' -TimeoutSec 6
    if ($p.host -eq $ts) { L "OK    QR pairs to $($p.host) (tailnet - works anywhere)" }
    else { L "FAIL  QR pairs to $($p.host), not the tailnet $ts"; $fail++ }
} catch {
    L 'WARN  pairing page not answering on 9120.'
    L '      Start it:  python D:\HermesMobile\pc\qr_server.py'
    L '      Or print the QR in this terminal instead:'
    L '                 python D:\HermesMobile\pc\hermes-dashboard.py --qr-only'
}

# ==========================================================================
L ''
if ($fail -eq 0) {
    L 'ALL CHECKS PASSED - the phone reaches this PC from any network.'
    L "  Tailnet URL : http://${ts}:${port}"
    L '  Still to do ON THE PHONE (once):'
    L '    1. Tailscale app: signed in to the same account, VPN connected'
    L '    2. Settings > Network > VPN > Tailscale > Always-on VPN ON'
    L '       (and "Block connections without VPN" ON)'
    L '    3. Battery: set Tailscale to Unrestricted - Android otherwise'
    L '       kills the tunnel in the background and the PC "disappears"'
    L '    4. Hermes Remote: re-pair by scanning a fresh QR, so the saved'
    L '       profile points at the tailnet, not a LAN IP'
} else {
    L "$fail CHECK(S) FAILED" $(if ($needsAdmin -gt 0) { "($needsAdmin need an elevated shell)" })
    if ($needsAdmin -gt 0) {
        L 'Re-run this script from an Administrator PowerShell to apply those fixes.'
    }
}
exit $fail
