# Local 3-node HA smoke on Windows (student fork follow-along).
# Requires: Docker when-local-redis / when-local-etcd, packaged when-app runner jar.
$ErrorActionPreference = "Stop"
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not (Test-Path (Join-Path $repo "when-app"))) {
  $repo = "c:\Users\rchua\Desktop\AIFullStackDevelopment\when"
}
Set-Location $repo

$env:JAVA_HOME = "C:\Users\rchua\tools\jdk-21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$appJar = Join-Path $repo "when-app\target\when-app-1.0.0-SNAPSHOT-runner.jar"
if (-not (Test-Path $appJar)) { throw "missing $appJar" }

$runDir = Join-Path $repo ".loop\ha-3node"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$summary = Join-Path $runDir "summary.txt"
$fileSinkDir = Join-Path $runDir "file-sink"
New-Item -ItemType Directory -Force -Path $fileSinkDir | Out-Null
"" | Set-Content -Encoding utf8 $summary
function Log([string]$m) {
  $line = "$(Get-Date -Format o) $m"
  Add-Content -Encoding utf8 $summary $line
  Write-Host $line
}

function Stop-AllWhen {
  Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match 'when-app-1.0.0-SNAPSHOT-runner' } |
    ForEach-Object {
      Log "stop pid=$($_.ProcessId)"
      Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
  Start-Sleep -Seconds 2
}

function Clear-WhenEtcd {
  docker exec -e ETCDCTL_API=3 when-local-etcd etcdctl del --prefix /when/nodes 2>$null | Out-Null
  docker exec -e ETCDCTL_API=3 when-local-etcd etcdctl del --prefix /when/controller 2>$null | Out-Null
  docker exec -e ETCDCTL_API=3 when-local-etcd etcdctl del --prefix /when/workers 2>$null | Out-Null
  docker exec -e ETCDCTL_API=3 when-local-etcd etcdctl del --prefix /when/timewheels 2>$null | Out-Null
  docker exec -e ETCDCTL_API=3 when-local-etcd etcdctl del --prefix /when/operations 2>$null | Out-Null
  Log "etcd when cluster keys cleared"
}

$nodes = @(
  @{ Id = "when-1"; Worker = 1; Http = 28080; Mgmt = 18081; Grpc = 29090 },
  @{ Id = "when-2"; Worker = 2; Http = 28081; Mgmt = 18082; Grpc = 29091 },
  @{ Id = "when-3"; Worker = 3; Http = 28082; Mgmt = 18083; Grpc = 29092 }
)

function Start-Node($n) {
  $log = Join-Path $runDir "$($n.Id).out.log"
  $err = Join-Path $runDir "$($n.Id).err.log"
  # Avoid Get-NetTCPConnection 鈥?it can stall for minutes on Windows.
  foreach ($port in @($n.Http, $n.Mgmt, $n.Grpc)) {
    $pids = @(cmd /c "netstat -ano | findstr :$port" 2>$null |
      ForEach-Object { if ($_ -match '\sLISTENING\s+(\d+)\s*$') { $Matches[1] } } |
      Select-Object -Unique)
    foreach ($pid in $pids) {
      if ($pid -and $pid -ne '0') {
        Stop-Process -Id ([int]$pid) -Force -ErrorAction SilentlyContinue
      }
    }
  }
  Start-Sleep -Milliseconds 300
  # Avoid system SOCKS/HTTP proxy hijacking jetcd/Redis loopback traffic.
  $env:NO_PROXY = "127.0.0.1,localhost"
  $env:no_proxy = "127.0.0.1,localhost"
  foreach ($p in @("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy")) {
    Remove-Item "Env:$p" -ErrorAction SilentlyContinue
  }
  $env:WHEN_REDIS_HOST = "127.0.0.1"
  $env:WHEN_REDIS_PORT = "6379"
  $env:WHEN_ETCD_ENDPOINTS = "http://127.0.0.1:2379"
  $env:WHEN_NODE_ID = $n.Id
  $env:WHEN_WORKER_ID = "$($n.Worker)"
  $env:WHEN_HTTP_PORT = "$($n.Http)"
  $env:WHEN_MANAGEMENT_PORT = "$($n.Mgmt)"
  $env:WHEN_MANAGEMENT_HOST = "127.0.0.1"
  $env:WHEN_GRPC_PORT = "$($n.Grpc)"
  $env:WHEN_NODE_HOST = "127.0.0.1"
  # Local wheel bootstrap (Controller creates tw-0 when 鈮? nodes); keep OTEL off for slim HA.
  $env:WHEN_TIMEWHEEL_COUNT = "1"
  $env:WHEN_FILE_SINK_BASE_DIR = $fileSinkDir
  Remove-Item Env:OTEL_EXPORTER_OTLP_ENDPOINT -ErrorAction SilentlyContinue
  # Default 30s for Docker Desktop etcd; override with WHEN_HA_LEASE_TTL_SECONDS / WHEN_HA_HEARTBEAT_MS.
  $leaseTtl = if ($env:WHEN_HA_LEASE_TTL_SECONDS) { $env:WHEN_HA_LEASE_TTL_SECONDS } else { "30" }
  $hbMs = if ($env:WHEN_HA_HEARTBEAT_MS) { $env:WHEN_HA_HEARTBEAT_MS } else { "5000" }
  $env:WHEN_ETCD_LEASE_TTL_SECONDS = "$leaseTtl"
  $env:WHEN_ETCD_HEARTBEAT_INTERVAL_MS = "$hbMs"
  $env:WHEN_LOG_FORMAT = "json"
  $p = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" `
    -ArgumentList @("-Djava.net.useSystemProxies=false", "-jar", $appJar) `
    -WorkingDirectory $repo `
    -RedirectStandardOutput $log `
    -RedirectStandardError $err `
    -PassThru -WindowStyle Hidden
  $n.Pid = $p.Id
  Log "started $($n.Id) pid=$($p.Id) http=$($n.Http) mgmt=$($n.Mgmt) grpc=$($n.Grpc)"
}

function Wait-Ready($n, [int]$timeoutSec = 90) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    try {
      $r = curl.exe -s -m 2 "http://127.0.0.1:$($n.Mgmt)/ready"
      if ($r -match '"status"\s*:\s*"UP"') { Log "$($n.Id) ready"; return }
    } catch {}
    Start-Sleep -Milliseconds 500
  }
  throw "$($n.Id) not ready; see $($n.Id).err.log"
}

$leaseTtlLog = if ($env:WHEN_HA_LEASE_TTL_SECONDS) { $env:WHEN_HA_LEASE_TTL_SECONDS } else { "30" }
$hbLog = if ($env:WHEN_HA_HEARTBEAT_MS) { $env:WHEN_HA_HEARTBEAT_MS } else { "5000" }
Log "=== HA 3-node smoke begin lease_ttl=${leaseTtlLog}s heartbeat=${hbLog}ms ==="
Stop-AllWhen
Clear-WhenEtcd

foreach ($n in $nodes) {
  Start-Node $n
  Wait-Ready $n
  Start-Sleep -Seconds 3
}
Log "settle 15s for leases/controller"
Start-Sleep -Seconds 15

$primaryHttp = $nodes[0].Http
$nodesJson = $null
$nodeCount = 0
for ($attempt = 1; $attempt -le 60; $attempt++) {
  foreach ($probe in $nodes) {
    try {
      $nodesJson = curl.exe -s -m 3 "http://127.0.0.1:$($probe.Http)/admin/v1/cluster/nodes"
      if ($nodesJson -match '"code"\s*:\s*"OK"') {
        $nodeCount = ([regex]::Matches($nodesJson, '"node_id"')).Count
        if ($nodeCount -ge 3) {
          $primaryHttp = $probe.Http
          Log "nodes_ok via $($probe.Id) attempt=$attempt $nodesJson"
          break
        }
      }
    } catch {}
  }
  if ($nodeCount -ge 3) { break }
  Log "nodes_wait_$attempt count=$nodeCount raw=$nodesJson"
  Start-Sleep -Seconds 1
}
if ($nodeCount -lt 3) { throw "expected 3 nodes, got $nodeCount" }

# Prefer Controller-bootstrapped tw-0 (matches local WHEN_TIMEWHEEL_COUNT wheel).
$tw = $null
for ($attempt = 1; $attempt -le 20; $attempt++) {
  $tw = curl.exe -s "http://127.0.0.1:$primaryHttp/admin/v1/timewheels"
  Log "timewheels_wait_$attempt=$tw"
  if ($tw -match '"master"') { break }
  Start-Sleep -Seconds 1
}
if ($tw -notmatch '"master"') {
  Set-Content -Encoding ascii (Join-Path $runDir "create-tw.json") '{"count":1}'
  $create = curl.exe -s -w "`nHTTP %{http_code}" -X POST "http://127.0.0.1:$primaryHttp/admin/v1/timewheels" `
    -H "Content-Type: application/json" -H "Idempotency-Key: ha-create-tw-fallback" `
    --data-binary "@$runDir\create-tw.json"
  Log "create_tw_fallback=$create"
  $tw = curl.exe -s "http://127.0.0.1:$primaryHttp/admin/v1/timewheels"
}
Log "timewheels=$tw"
$twObj = $tw | ConvertFrom-Json
$assignment = $twObj.data.items | Where-Object { $_.tw_id -eq 'tw-0' } | Select-Object -First 1
if (-not $assignment) { $assignment = $twObj.data.items[0] }
$masterId = $assignment.master
$slaveId = $assignment.slave
Log "master=$masterId slave=$slaveId tw=$($assignment.tw_id)"
if ($masterId -eq $slaveId) { throw "master and slave must differ" }

$payloadB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("ha-failover-probe"))
$submitBody = "{`"delay_seconds`":15,`"sink_type`":`"FILE`",`"sink_config`":{`"file`":{`"path`":`"ha-failover.txt`"}},`"payload`":`"$payloadB64`",`"business_tag`":`"ha`"}"
Set-Content -Encoding ascii (Join-Path $runDir "submit.json") $submitBody
$sub = curl.exe -s -X POST "http://127.0.0.1:$primaryHttp/api/v1/messages" `
  -H "Content-Type: application/json" -H "X-Request-Id: ha-submit-1" `
  --data-binary "@$runDir\submit.json"
Log "submit=$sub"
$msgId = ($sub | ConvertFrom-Json).data.message_id
if (-not $msgId) { throw "submit failed" }

$masterNode = $nodes | Where-Object { $_.Id -eq $masterId } | Select-Object -First 1
if (-not $masterNode) { throw "master node object missing" }
$killAt = Get-Date
Log "killing master $($masterNode.Id) pid=$($masterNode.Pid) at $killAt"
Stop-Process -Id $masterNode.Pid -Force -ErrorAction SilentlyContinue

$survivorHttp = ($nodes | Where-Object { $_.Id -ne $masterId } | Select-Object -First 1).Http
$promoted = $false
$takeoverSec = -1
for ($i = 0; $i -lt 90; $i++) {
  Start-Sleep -Milliseconds 500
  try {
      $tw2 = curl.exe -s --connect-timeout 2 -m 2 "http://127.0.0.1:$survivorHttp/admin/v1/timewheels" | ConvertFrom-Json
    $item = $tw2.data.items | Where-Object { $_.tw_id -eq $assignment.tw_id } | Select-Object -First 1
    if ($item -and $item.master -and $item.master -ne $masterId) {
      $takeoverSec = ((Get-Date) - $killAt).TotalSeconds
      Log "takeover new_master=$($item.master) after ${takeoverSec}s status=$($item.status) sync=$($item.sync_state)"
      $promoted = $true
      break
    }
  } catch {}
}
if (-not $promoted) { throw "master takeover not observed within 45s" }
if ($takeoverSec -gt 10) { Log "WARN takeover ${takeoverSec}s exceeds 10s budget" } else { Log "PASS takeover within 10s (${takeoverSec}s)" }

# wait for delivery on survivor
$delivered = $false
for ($i = 0; $i -lt 40; $i++) {
  Start-Sleep -Seconds 1
  $q = curl.exe -s --connect-timeout 2 -m 3 "http://127.0.0.1:$survivorHttp/api/v1/messages/$msgId"
  Log "query=$q"
  if ($q -match '"status"\s*:\s*"DELIVERED"') { $delivered = $true; break }
  if ($q -match '"status"\s*:\s*"(CANCELLED|FAILED)"') { break }
}
if (-not $delivered) { throw "message $msgId did not reach DELIVERED after failover" }
Log "PASS message delivered after failover msg=$msgId"

# --- Controller kill: survivors must re-elect and still accept/deliver work ---
$alive = @($nodes | Where-Object { $_.Id -ne $masterId })
function Get-ControllerId([int]$httpPort) {
  $raw = curl.exe -s -m 3 "http://127.0.0.1:$httpPort/admin/v1/cluster/nodes"
  if ($raw -notmatch '"code"\s*:\s*"OK"') { return $null }
  $obj = $raw | ConvertFrom-Json
  $c = $obj.data.items | Where-Object { $_.controller -eq $true } | Select-Object -First 1
  if ($c) { return $c.node_id }
  return $null
}
$ctrlHttp = $alive[0].Http
$controllerId = $null
for ($i = 0; $i -lt 30; $i++) {
  foreach ($n in $alive) {
    $controllerId = Get-ControllerId $n.Http
    if ($controllerId) { $ctrlHttp = $n.Http; break }
  }
  if ($controllerId) { break }
  Start-Sleep -Seconds 1
}
if (-not $controllerId) { throw "no controller among survivors after master failover" }
Log "controller_after_master_kill=$controllerId"

$ctrlNode = $alive | Where-Object { $_.Id -eq $controllerId } | Select-Object -First 1
if (-not $ctrlNode) { throw "controller node object missing: $controllerId" }
$ctrlKillAt = Get-Date
Log "killing controller $($ctrlNode.Id) pid=$($ctrlNode.Pid) at $ctrlKillAt"
Stop-Process -Id $ctrlNode.Pid -Force -ErrorAction SilentlyContinue

$remaining = @($alive | Where-Object { $_.Id -ne $controllerId })
if ($remaining.Count -lt 1) { throw "no remaining node after controller kill" }
$remainHttp = $remaining[0].Http
$newCtrl = $null
$ctrlTakeoverSec = -1
for ($i = 0; $i -lt 90; $i++) {
  Start-Sleep -Milliseconds 500
  $cand = Get-ControllerId $remainHttp
  if ($cand -and $cand -ne $controllerId) {
    $ctrlTakeoverSec = ((Get-Date) - $ctrlKillAt).TotalSeconds
    $newCtrl = $cand
    Log "controller_reelect new=$newCtrl after ${ctrlTakeoverSec}s"
    break
  }
}
if (-not $newCtrl) { throw "controller re-election not observed within 45s" }
if ($ctrlTakeoverSec -gt 15) {
  Log "WARN controller re-elect ${ctrlTakeoverSec}s exceeds 15s soft budget"
} else {
  Log "PASS controller re-elect within 15s (${ctrlTakeoverSec}s)"
}

# Decision still works: list wheels + submit another FILE message
$twAfter = curl.exe -s "http://127.0.0.1:$remainHttp/admin/v1/timewheels"
Log "timewheels_after_controller_kill=$twAfter"
if ($twAfter -notmatch '"master"') { throw "timewheels unavailable after controller re-elect" }

$payload2 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("ha-controller-probe"))
$submit2 = "{`"delay_seconds`":8,`"sink_type`":`"FILE`",`"sink_config`":{`"file`":{`"path`":`"ha-controller.txt`"}},`"payload`":`"$payload2`",`"business_tag`":`"ha-ctrl`"}"
Set-Content -Encoding ascii (Join-Path $runDir "submit-ctrl.json") $submit2
$sub2 = curl.exe -s -X POST "http://127.0.0.1:$remainHttp/api/v1/messages" `
  -H "Content-Type: application/json" -H "X-Request-Id: ha-submit-ctrl" `
  --data-binary "@$runDir\submit-ctrl.json"
Log "submit_after_controller=$sub2"
$msgId2 = ($sub2 | ConvertFrom-Json).data.message_id
if (-not $msgId2) { throw "submit after controller kill failed" }

$delivered2 = $false
for ($i = 0; $i -lt 30; $i++) {
  Start-Sleep -Seconds 1
  $q2 = curl.exe -s --connect-timeout 2 -m 3 "http://127.0.0.1:$remainHttp/api/v1/messages/$msgId2"
  Log "query_ctrl=$q2"
  if ($q2 -match '"status"\s*:\s*"DELIVERED"') { $delivered2 = $true; break }
  if ($q2 -match '"status"\s*:\s*"(CANCELLED|FAILED)"') { break }
}
if (-not $delivered2) { throw "message $msgId2 did not DELIVER after controller re-elect" }
Log "PASS message delivered after controller re-elect msg=$msgId2"

# leave last survivor running for manual inspection
$state = @{
  nodes = @($nodes | ForEach-Object { @{ id = $_.Id; pid = $_.Pid; http = $_.Http; mgmt = $_.Mgmt; grpc = $_.Grpc } })
  killed_master = $masterId
  killed_controller = $controllerId
  new_controller = $newCtrl
  summary = $summary
}
$state | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $runDir "state.json")
Log "=== HA 3-node smoke PASS (master + controller) ==="
Write-Host "SUMMARY=$summary"
