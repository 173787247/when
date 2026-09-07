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
  foreach ($port in @($n.Http, $n.Mgmt, $n.Grpc)) {
    Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
      ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
  }
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
  # Avoid auto tw-0 bootstrap racing admin create; wheels come from admin API.
  $env:WHEN_TIMEWHEEL_COUNT = "1"
  $env:WHEN_FILE_SINK_BASE_DIR = $fileSinkDir
  $env:OTEL_EXPORTER_OTLP_ENDPOINT = "http://127.0.0.1:4317"
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

Log "=== HA 3-node smoke begin ==="
Stop-AllWhen
Clear-WhenEtcd

foreach ($n in $nodes) { Start-Node $n; Start-Sleep -Seconds 3 }
foreach ($n in $nodes) { Wait-Ready $n }
Log "settle 8s for leases/controller"
Start-Sleep -Seconds 8

$primaryHttp = $nodes[0].Http
$nodesJson = curl.exe -s "http://127.0.0.1:$primaryHttp/admin/v1/cluster/nodes"
Log "nodes=$nodesJson"
$nodeCount = ([regex]::Matches($nodesJson, '"node_id"')).Count
if ($nodeCount -lt 3) { throw "expected 3 nodes, got $nodeCount" }

Set-Content -Encoding ascii (Join-Path $runDir "create-tw.json") '{"count":1}'
$create = $null
for ($attempt = 1; $attempt -le 5; $attempt++) {
  $key = "ha-create-tw-$attempt-$(Get-Random -Maximum 99999)"
  $create = curl.exe -s -w "`nHTTP %{http_code}" -X POST "http://127.0.0.1:$primaryHttp/admin/v1/timewheels" `
    -H "Content-Type: application/json" -H "Idempotency-Key: $key" `
    --data-binary "@$runDir\create-tw.json"
  Log "create_tw_attempt=$attempt $create"
  if ($create -match 'HTTP 201') { break }
  # Already created by a prior partial attempt
  $existing = curl.exe -s "http://127.0.0.1:$primaryHttp/admin/v1/timewheels"
  if ($existing -match '"master"') { Log "using existing timewheels=$existing"; $create = "HTTP 201"; break }
  Start-Sleep -Seconds 2
}
if ($create -notmatch 'HTTP 201') { throw "create timewheel failed" }

$tw = curl.exe -s "http://127.0.0.1:$primaryHttp/admin/v1/timewheels"
Log "timewheels=$tw"
$twObj = $tw | ConvertFrom-Json
$assignment = $twObj.data.items[0]
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
for ($i = 0; $i -lt 40; $i++) {
  Start-Sleep -Milliseconds 500
  try {
    $tw2 = curl.exe -s -m 2 "http://127.0.0.1:$survivorHttp/admin/v1/timewheels" | ConvertFrom-Json
    $item = $tw2.data.items | Where-Object { $_.tw_id -eq $assignment.tw_id } | Select-Object -First 1
    if ($item -and $item.master -and $item.master -ne $masterId) {
      $takeoverSec = ((Get-Date) - $killAt).TotalSeconds
      Log "takeover new_master=$($item.master) after ${takeoverSec}s status=$($item.status) sync=$($item.sync_state)"
      $promoted = $true
      break
    }
  } catch {}
}
if (-not $promoted) { throw "master takeover not observed within 20s" }
if ($takeoverSec -gt 10) { Log "WARN takeover ${takeoverSec}s exceeds 10s budget" } else { Log "PASS takeover within 10s (${takeoverSec}s)" }

# wait for delivery on survivor
$delivered = $false
for ($i = 0; $i -lt 40; $i++) {
  Start-Sleep -Seconds 1
  $q = curl.exe -s "http://127.0.0.1:$survivorHttp/api/v1/messages/$msgId"
  Log "query=$q"
  if ($q -match '"DELIVERED"') { $delivered = $true; break }
  if ($q -match '"CANCELLED"|"FAILED"') { break }
}
if (-not $delivered) { throw "message $msgId did not reach DELIVERED after failover" }
Log "PASS message delivered after failover msg=$msgId"

# leave survivors running for manual inspection; rewrite pids file
$state = @{ nodes = @($nodes | ForEach-Object { @{ id = $_.Id; pid = $_.Pid; http = $_.Http; mgmt = $_.Mgmt; grpc = $_.Grpc } }); killed_master = $masterId; new_summary = $summary }
$state | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $runDir "state.json")
Log "=== HA 3-node smoke PASS ==="
Write-Host "SUMMARY=$summary"
