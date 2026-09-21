# Verify EVERY piece of the phone link is permanent (survives reboot/crash/logout).
# A link that works today but not after a reboot is not "bound permanently".
#
# Two bugs this script used to have, both of which made it lie:
#   * It looked for a task named 'Hermes Always On'. The real task is
#     'Hermes Always On (user)', so a perfectly registered watchdog was reported
#     as FAIL and a healthy link looked broken.
#   * It pinged a hardcoded LAN IP (192.168.1.102). This PC's LAN address moves
#     (10.103.133.152 -> 10.55.187.243 -> 192.168.1.108 in one day), so the check
#     failed for reasons that had nothing to do with the link.
# Both are now resolved dynamically; a stale literal is indistinguishable from
# a real outage, which is exactly the failure this script exists to catch.

$log = 'D:\Temp\permanence.log'
function L($m) { Add-Content -LiteralPath $log -Value $m; Write-Host $m }
Set-Content -LiteralPath $log -Value "=== permanence audit $(Get-Date) ==="

$fail = 0
$tsExe = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
$port  = 9119

function Get-TailnetIp {
    if (-not (Test-Path $tsExe)) { return $null }
    foreach ($line in (& $tsExe ip -4 2>$null)) {
        $ip = $line.Trim(); $o = $ip.Split('.')
        if ($o.Count -eq 4 -and $o[0] -eq '100' -and [int]$o[1] -ge 64 -and [int]$o[1] -le 127) { return $ip }
    }
    return $null
}

# 1. Tailscale service must auto-start (comes back on a cold boot)
$svc = Get-Service Tailscale -ErrorAction SilentlyContinue
if ($svc -and $svc.StartType -eq 'Automatic' -and $svc.Status -eq 'Running') {
    L "PASS  Tailscale service: Running / Automatic"
} else { L "FAIL  Tailscale service: $($svc.Status) / $($svc.StartType)"; $fail++ }

# 2. ForceDaemon - without it the tunnel needs an interactive login after a
#    logout/reboot, and the link is dead until someone walks over to the PC.
$fd = $false
if (Test-Path $tsExe) { try { $fd = ((& $tsExe debug prefs 2>$null | Out-String) -match '"ForceDaemon":\s*true') } catch {} }
if ($fd) { L "PASS  ForceDaemon = true (tunnel survives logout/reboot)" }
else { L "FAIL  ForceDaemon = false - fix: tailscale up --unattended"; $fail++ }

# 3. The adapter must be Private or the firewall's Domain,Private rule never applies
$prof = Get-NetConnectionProfile -InterfaceAlias 'Tailscale' -ErrorAction SilentlyContinue
if ($prof -and $prof.NetworkCategory -eq 'Private') { L "PASS  Tailscale adapter NetworkCategory = Private" }
else { L "FAIL  Tailscale adapter NetworkCategory = $($prof.NetworkCategory)"; $fail++ }

# 4. Dashboard watchdog task (revives the server after a crash or cold boot).
#    Accept the legacy name too, but report which one was found.
$t = Get-ScheduledTask -TaskName 'Hermes Always On (user)' -ErrorAction SilentlyContinue
if (-not $t) { $t = Get-ScheduledTask -TaskName 'Hermes Always On' -ErrorAction SilentlyContinue }
if ($t) {
    $i = Get-ScheduledTaskInfo -TaskName $t.TaskName
    $trig = ($t.Triggers | ForEach-Object { $_.CimClass.CimClassName }) -join ','
    L "PASS  '$($t.TaskName)': $($t.State), lastResult=$($i.LastTaskResult), triggers=$trig"
    L "      principal=$($t.Principal.UserId) runlevel=$($t.Principal.RunLevel)"
    L "      lastRun=$($i.LastRunTime) nextRun=$($i.NextRunTime)"
} else { L "FAIL  no 'Hermes Always On (user)' task - dashboard will not revive"; $fail++ }

# 5. Pairing-QR page task
$q = Get-ScheduledTask -TaskName 'Hermes Pairing Page' -ErrorAction SilentlyContinue
if ($q -and $q.State -eq 'Running') { L "PASS  'Hermes Pairing Page': Running" }
elseif ($q) { L "WARN  'Hermes Pairing Page': $($q.State) - page on 9120 may not answer" }
else { L "WARN  pairing-page task missing (QR page will not auto-start)" }

# 6. Firewall must allow BOTH the LAN and the tailnet
$r = Get-NetFirewallRule -DisplayName 'Hermes Remote dashboard' -ErrorAction SilentlyContinue
if ($r) {
    $addr = ($r | Get-NetFirewallAddressFilter).RemoteAddress -join ','
    $rulePort = ($r | Get-NetFirewallPortFilter).LocalPort -join ','
    if ($addr -match '100\.64\.0\.0') { L "PASS  firewall: port=$rulePort remote=$addr" }
    else { L "FAIL  firewall missing tailnet range: $addr"; $fail++ }
} else { L "FAIL  firewall rule missing"; $fail++ }

# 7. Dashboard must bind 0.0.0.0, not a single interface
$listen = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
if ($listen) {
    $addrs = ($listen | ForEach-Object { $_.LocalAddress }) -join ','
    if ($addrs -match '0\.0\.0\.0') { L "PASS  dashboard bound $addrs (all interfaces)" }
    else { L "FAIL  dashboard bound only $addrs"; $fail++ }
} else { L "FAIL  nothing listening on $port"; $fail++ }

# 8. Live reachability on every path the phone may use (addresses resolved now,
#    never hardcoded)
$ts = Get-TailnetIp
$lan = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and
                       $_.InterfaceAlias -notmatch 'Tailscale|Loopback|Bluetooth|Hyper-V|Virtual|VMware|VirtualBox' } |
        Select-Object -First 1).IPAddress

$targets = @('127.0.0.1')
if ($lan) { $targets += $lan }
if ($ts)  { $targets += $ts }
foreach ($ip in $targets) {
    try {
        $x = Invoke-RestMethod -Uri "http://${ip}:${port}/api/status" -TimeoutSec 6
        $tag = if ($ip -eq $ts) { '  <- the address the phone must be paired to' } else { '' }
        L "PASS  reachable $ip (v$($x.version))$tag"
    } catch { L "FAIL  unreachable $ip"; $fail++ }
}

# 9. The QR must actually carry the tailnet host, or the next pairing re-creates
#    the LAN-only problem this whole audit exists to prevent.
if ($ts) {
    try {
        $p = Invoke-RestMethod -Uri 'http://localhost:9120/payload' -TimeoutSec 6
        if ($p.host -eq $ts) { L "PASS  pairing QR carries the tailnet host ($ts)" }
        else { L "FAIL  pairing QR carries $($p.host), not the tailnet $ts"; $fail++ }
    } catch { L "WARN  pairing page (9120) not answering - cannot confirm the QR host" }
}

L ""
L $(if ($fail -eq 0) { "ALL CHECKS PASSED - link is permanent" } else { "$fail CHECK(S) FAILED" })
exit $fail
