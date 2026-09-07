# Batch HA: submit N FILE messages, kill Master before due, require all DELIVERED.
# Requires Docker when-local-redis/etcd and packaged when-app runner jar.
$ErrorActionPreference = "Stop"
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not (Test-Path (Join-Path $repo "when-app"))) {
  $repo = "c:\Users\rchua\Desktop\AIFullStackDevelopment\when"
}
Set-Location $repo

$BatchCount = 100
if ($env:WHEN_HA_BATCH_COUNT) { $BatchCount = [int]$env:WHEN_HA_BATCH_COUNT }
$DelaySeconds = 90
if ($env:WHEN_HA_BATCH_DELAY) { $DelaySeconds = [int]$env:WHEN_HA_BATCH_DELAY }

$env:JAVA_HOME = "C:\Users\rchua\tools\jdk-21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$appJar = Join-Path $repo "when-app\target\when-app-1.0.0-SNAPSHOT-runner.jar"
if (-not (Test-Path $appJar)) { throw "missing $appJar" }

$runDir = Join-Path $repo ".loop\ha-batch"
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
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 2
}

function Clear-WhenEtcd {
  docker exec -e ETCDCTL_API=3 when-local-etcd etcdctl del --prefix /when/nodes 2>$null | Out-Null
  docker exec -e ETCDCTL_API=3 when-local-etcd etcdctl del --prefix /when/controller 2>$null | Out-Null
  docker exec -e ETCDCTL_API=3 when-local-etcd etcdctl del --prefix /when/workers 2>$null | Out-Null
  docker exec -e ETCDCTL_API=3 when-local-etcd etcdctl del --prefix /when/timewheels 2>$null | Out-Null
  docker exec -e ETCDCTL_API=3 when-local-etcd etcdctl del --prefix /when/operations 2>$null | Out-Null
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
    $pids = @(cmd /c "netstat -ano | findstr :$port" 2>$null |
      ForEach-Object { if ($_ -match '\sLISTENING\s+(\d+)\s*$') { $Matches[1] } } |
      Select-Object -Unique)
    foreach ($pid in $pids) {
      if ($pid -and $pid -ne '0') { Stop-Process -Id ([int]$pid) -Force -ErrorAction SilentlyContinue }
    }
  }
  Start-Sleep -Milliseconds 300
  $env:NO_PROXY = "127.0.0.1,localhost"; $env:no_proxy = $env:NO_PROXY
  foreach ($p in @("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy")) {
    Remove-Item "Env:$p" -ErrorAction SilentlyContinue
  }
  Remove-Item Env:OTEL_EXPORTER_OTLP_ENDPOINT -ErrorAction SilentlyContinue
  $env:WHEN_REDIS_HOST = "127.0.0.1"; $env:WHEN_REDIS_PORT = "6379"
  $env:WHEN_ETCD_ENDPOINTS = "http://127.0.0.1:2379"
  $env:WHEN_NODE_ID = $n.Id; $env:WHEN_WORKER_ID = "$($n.Worker)"
  $env:WHEN_HTTP_PORT = "$($n.Http)"; $env:WHEN_MANAGEMENT_PORT = "$($n.Mgmt)"
  $env:WHEN_MANAGEMENT_HOST = "127.0.0.1"; $env:WHEN_GRPC_PORT = "$($n.Grpc)"
  $env:WHEN_NODE_HOST = "127.0.0.1"; $env:WHEN_TIMEWHEEL_COUNT = "1"
  $env:WHEN_FILE_SINK_BASE_DIR = $fileSinkDir
  $env:WHEN_ETCD_LEASE_TTL_SECONDS = "30"; $env:WHEN_ETCD_HEARTBEAT_INTERVAL_MS = "5000"
  $env:WHEN_LOG_FORMAT = "json"
  $p = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" `
    -ArgumentList @("-Djava.net.useSystemProxies=false", "-jar", $appJar) `
    -WorkingDirectory $repo -RedirectStandardOutput $log -RedirectStandardError $err `
    -PassThru -WindowStyle Hidden
  $n.Pid = $p.Id
  Log "started $($n.Id) pid=$($p.Id)"
}

function Wait-Ready($n, [int]$timeoutSec = 90) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $r = curl.exe -s -m 2 "http://127.0.0.1:$($n.Mgmt)/ready"
    if ($r -match '"status"\s*:\s*"UP"') { Log "$($n.Id) ready"; return }
    Start-Sleep -Milliseconds 500
  }
  throw "$($n.Id) not ready"
}

Log "=== HA batch failover begin count=$BatchCount delay=${DelaySeconds}s ==="
Stop-AllWhen
Clear-WhenEtcd
foreach ($n in $nodes) { Start-Node $n; Wait-Ready $n; Start-Sleep -Seconds 3 }
Start-Sleep -Seconds 15

$primaryHttp = $nodes[0].Http
$nodeCount = 0
for ($attempt = 1; $attempt -le 60; $attempt++) {
  foreach ($probe in $nodes) {
    $nodesJson = curl.exe -s -m 3 "http://127.0.0.1:$($probe.Http)/admin/v1/cluster/nodes"
    if ($nodesJson -match '"code"\s*:\s*"OK"') {
      $nodeCount = ([regex]::Matches($nodesJson, '"node_id"')).Count
      if ($nodeCount -ge 3) { $primaryHttp = $probe.Http; Log "nodes_ok via $($probe.Id)"; break }
    }
  }
  if ($nodeCount -ge 3) { break }
  Start-Sleep -Seconds 1
}
if ($nodeCount -lt 3) { throw "expected 3 nodes, got $nodeCount" }

$tw = $null
for ($attempt = 1; $attempt -le 30; $attempt++) {
  $tw = curl.exe -s "http://127.0.0.1:$primaryHttp/admin/v1/timewheels"
  if ($tw -match '"master"') { break }
  Start-Sleep -Seconds 1
}
if ($tw -notmatch '"master"') { throw "no timewheel assignment" }
$assignment = ($tw | ConvertFrom-Json).data.items | Where-Object { $_.tw_id -eq 'tw-0' } | Select-Object -First 1
if (-not $assignment) { $assignment = ($tw | ConvertFrom-Json).data.items[0] }
$masterId = $assignment.master
Log "master=$masterId slave=$($assignment.slave) tw=$($assignment.tw_id)"

$ids = New-Object System.Collections.Generic.List[string]
$payloadB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("ha-batch"))
for ($i = 1; $i -le $BatchCount; $i++) {
  $body = "{`"delay_seconds`":$DelaySeconds,`"sink_type`":`"FILE`",`"sink_config`":{`"file`":{`"path`":`"batch-$i.txt`"}},`"payload`":`"$payloadB64`",`"business_tag`":`"ha-batch`"}"
  $f = Join-Path $runDir "submit-$i.json"
  Set-Content -Encoding ascii $f $body
  $sub = curl.exe -s -X POST "http://127.0.0.1:$primaryHttp/api/v1/messages" `
    -H "Content-Type: application/json" -H "X-Request-Id: ha-batch-$i" `
    --data-binary "@$f"
  $msgId = ($sub | ConvertFrom-Json).data.message_id
  if (-not $msgId) { throw "submit $i failed: $sub" }
  $ids.Add($msgId) | Out-Null
  if (($i % 20) -eq 0) { Log "submitted $i/$BatchCount" }
}
Log "submitted_all count=$($ids.Count)"

$masterNode = $nodes | Where-Object { $_.Id -eq $masterId } | Select-Object -First 1
Log "killing master $($masterNode.Id) pid=$($masterNode.Pid) before due"
Stop-Process -Id $masterNode.Pid -Force -ErrorAction SilentlyContinue

$survivorHttp = ($nodes | Where-Object { $_.Id -ne $masterId } | Select-Object -First 1).Http
$promoted = $false
for ($i = 0; $i -lt 120; $i++) {
  Start-Sleep -Milliseconds 500
  try {
    $tw2 = curl.exe -s -m 2 "http://127.0.0.1:$survivorHttp/admin/v1/timewheels" | ConvertFrom-Json
    $item = $tw2.data.items | Where-Object { $_.tw_id -eq $assignment.tw_id } | Select-Object -First 1
    if ($item -and $item.master -and $item.master -ne $masterId) {
      Log "takeover new_master=$($item.master)"
      $promoted = $true
      break
    }
  } catch {}
}
if (-not $promoted) { throw "master takeover not observed" }

$deadline = (Get-Date).AddSeconds($DelaySeconds + 120)
$delivered = 0; $failed = 0; $pending = $ids.Count
while ((Get-Date) -lt $deadline) {
  $delivered = 0; $failed = 0; $pending = 0
  foreach ($id in $ids) {
    $q = curl.exe -s -m 2 "http://127.0.0.1:$survivorHttp/api/v1/messages/$id"
    if ($q -match '"status"\s*:\s*"DELIVERED"') { $delivered++ }
    elseif ($q -match '"status"\s*:\s*"(CANCELLED|FAILED)"') { $failed++ }
    else { $pending++ }
  }
  Log "progress delivered=$delivered failed=$failed pending=$pending"
  if (($delivered + $failed) -ge $ids.Count) { break }
  Start-Sleep -Seconds 5
}

Log "final delivered=$delivered failed=$failed pending=$pending total=$($ids.Count)"
if ($delivered -lt $ids.Count) {
  throw "batch incomplete delivered=$delivered/$($ids.Count) failed=$failed pending=$pending"
}
Log "=== HA batch failover PASS count=$delivered ==="
