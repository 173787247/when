# 3-node HA: kill Master, assert HTTP + Kafka sinks still DELIVERED.
# Requires: Docker when-local-redis / when-local-etcd / when-local-kafka, python, packaged runner jar.
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

$runDir = Join-Path $repo ".loop\ha-3node-sinks"
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

$hitFile = Join-Path $runDir "http-hit.txt"
$topic = "when-ha-http-kafka"
$recv = $null

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
  $env:NO_PROXY = "127.0.0.1,localhost"
  $env:no_proxy = "127.0.0.1,localhost"
  foreach ($p in @("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy")) {
    Remove-Item "Env:$p" -ErrorAction SilentlyContinue
  }
  Remove-Item Env:OTEL_EXPORTER_OTLP_ENDPOINT -ErrorAction SilentlyContinue
  $env:WHEN_REDIS_HOST = "127.0.0.1"
  $env:WHEN_REDIS_PORT = "6379"
  $env:WHEN_ETCD_ENDPOINTS = "http://127.0.0.1:2379"
  $env:WHEN_KAFKA_BOOTSTRAP_SERVERS = "127.0.0.1:9092"
  $env:WHEN_HTTP_SINK_ALLOW_LOOPBACK = "true"
  $env:WHEN_NODE_ID = $n.Id
  $env:WHEN_WORKER_ID = "$($n.Worker)"
  $env:WHEN_HTTP_PORT = "$($n.Http)"
  $env:WHEN_MANAGEMENT_PORT = "$($n.Mgmt)"
  $env:WHEN_MANAGEMENT_HOST = "127.0.0.1"
  $env:WHEN_GRPC_PORT = "$($n.Grpc)"
  $env:WHEN_NODE_HOST = "127.0.0.1"
  $env:WHEN_TIMEWHEEL_COUNT = "1"
  $env:WHEN_FILE_SINK_BASE_DIR = $fileSinkDir
  $env:WHEN_ETCD_LEASE_TTL_SECONDS = "30"
  $env:WHEN_ETCD_HEARTBEAT_INTERVAL_MS = "5000"
  $env:WHEN_LOG_FORMAT = "json"
  $p = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" `
    -ArgumentList @("-Djava.net.useSystemProxies=false", "-jar", $appJar) `
    -WorkingDirectory $repo `
    -RedirectStandardOutput $log `
    -RedirectStandardError $err `
    -PassThru -WindowStyle Hidden
  $n.Pid = $p.Id
  Log "started $($n.Id) pid=$($p.Id) http=$($n.Http)"
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

function Wait-Delivered([string]$httpPort, [string]$msgId, [int]$timeoutSec = 60) {
  for ($i = 0; $i -lt $timeoutSec; $i++) {
    Start-Sleep -Seconds 1
    $q = curl.exe -s "http://127.0.0.1:$httpPort/api/v1/messages/$msgId"
    if ($q -match '"status"\s*:\s*"DELIVERED"') {
      Log "delivered msg=$msgId query=$q"
      return $true
    }
    if ($q -match '"status"\s*:\s*"(CANCELLED|FAILED)"') {
      Log "terminal_fail msg=$msgId query=$q"
      return $false
    }
    if (($i % 5) -eq 0) { Log "wait_deliver_$i msg=$msgId $q" }
  }
  return $false
}

try {
  Log "=== HA 3-node HTTP+Kafka sink smoke begin ==="
  docker start when-local-redis when-local-etcd when-local-kafka 2>$null | Out-Null
  Start-Sleep -Seconds 3
  docker exec when-local-kafka /opt/kafka/bin/kafka-topics.sh `
    --bootstrap-server localhost:9092 --create --if-not-exists --topic $topic `
    --partitions 1 --replication-factor 1 2>$null | Out-Null

  # free :18099 if stale receiver
  $httpPids = @(cmd /c "netstat -ano | findstr :18099" 2>$null |
    ForEach-Object { if ($_ -match '\sLISTENING\s+(\d+)\s*$') { $Matches[1] } } |
    Select-Object -Unique)
  foreach ($pid in $httpPids) {
    if ($pid -and $pid -ne '0') { Stop-Process -Id ([int]$pid) -Force -ErrorAction SilentlyContinue }
  }
  Remove-Item $hitFile -Force -ErrorAction SilentlyContinue
  $py = @"
from http.server import BaseHTTPRequestHandler, HTTPServer
import pathlib
hit = pathlib.Path(r'$($hitFile.Replace('\','\\'))')
class H(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        self.rfile.read(n)
        hit.write_text('ok', encoding='utf-8')
        self.send_response(200); self.end_headers(); self.wfile.write(b'ok')
    def log_message(self, *a): pass
HTTPServer(('127.0.0.1', 18099), H).serve_forever()
"@
  $pyFile = Join-Path $runDir "receiver.py"
  Set-Content -Encoding utf8 $pyFile $py
  $recv = Start-Process -FilePath "python" -ArgumentList @("`"$pyFile`"") `
    -RedirectStandardOutput (Join-Path $runDir "recv.out") `
    -RedirectStandardError (Join-Path $runDir "recv.err") `
    -PassThru -WindowStyle Hidden
  Start-Sleep -Seconds 1
  try { Invoke-WebRequest -Uri "http://127.0.0.1:18099/when" -Method POST -Body "ping" -TimeoutSec 2 -UseBasicParsing | Out-Null } catch {}
  if (-not (Test-Path $hitFile)) { throw "HTTP receiver not up on :18099" }
  Remove-Item $hitFile -Force
  Log "http_receiver_ok pid=$($recv.Id)"

  Stop-AllWhen
  Clear-WhenEtcd
  foreach ($n in $nodes) {
    Start-Node $n
    Wait-Ready $n
    Start-Sleep -Seconds 3
  }
  Log "settle 15s"
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
            Log "nodes_ok via $($probe.Id) attempt=$attempt"
            break
          }
        }
      } catch {}
    }
    if ($nodeCount -ge 3) { break }
    Start-Sleep -Seconds 1
  }
  if ($nodeCount -lt 3) { throw "expected 3 nodes, got $nodeCount" }

  $tw = $null
  for ($attempt = 1; $attempt -le 20; $attempt++) {
    $tw = curl.exe -s "http://127.0.0.1:$primaryHttp/admin/v1/timewheels"
    if ($tw -match '"master"') { break }
    Start-Sleep -Seconds 1
  }
  if ($tw -notmatch '"master"') { throw "no timewheel master: $tw" }
  Log "timewheels=$tw"
  $twObj = $tw | ConvertFrom-Json
  $assignment = $twObj.data.items | Where-Object { $_.tw_id -eq 'tw-0' } | Select-Object -First 1
  if (-not $assignment) { $assignment = $twObj.data.items[0] }
  $masterId = $assignment.master
  Log "master=$masterId slave=$($assignment.slave)"

  $httpPayload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("ha-http-sink"))
  $kafkaPayload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("ha-kafka-sink"))
  $httpBody = "{`"delay_seconds`":18,`"sink_type`":`"HTTP`",`"sink_config`":{`"http`":{`"url`":`"http://127.0.0.1:18099/when`",`"method`":`"POST`",`"timeout_ms`":5000}},`"payload`":`"$httpPayload`",`"business_tag`":`"ha-http`"}"
  $kafkaBody = "{`"delay_seconds`":18,`"sink_type`":`"KAFKA`",`"sink_config`":{`"kafka`":{`"bootstrap_servers`":`"127.0.0.1:9092`",`"topic`":`"$topic`",`"key`":`"ha1`"}},`"payload`":`"$kafkaPayload`",`"business_tag`":`"ha-kafka`"}"
  Set-Content -Encoding ascii (Join-Path $runDir "submit-http.json") $httpBody
  Set-Content -Encoding ascii (Join-Path $runDir "submit-kafka.json") $kafkaBody

  $subH = curl.exe -s -X POST "http://127.0.0.1:$primaryHttp/api/v1/messages" `
    -H "Content-Type: application/json" -H "X-Request-Id: ha-http-1" `
    --data-binary "@$runDir\submit-http.json"
  Log "submit_http=$subH"
  $msgHttp = ($subH | ConvertFrom-Json).data.message_id
  if (-not $msgHttp) { throw "HTTP submit failed" }

  $subK = curl.exe -s -X POST "http://127.0.0.1:$primaryHttp/api/v1/messages" `
    -H "Content-Type: application/json" -H "X-Request-Id: ha-kafka-1" `
    --data-binary "@$runDir\submit-kafka.json"
  Log "submit_kafka=$subK"
  $msgKafka = ($subK | ConvertFrom-Json).data.message_id
  if (-not $msgKafka) { throw "Kafka submit failed" }

  $masterNode = $nodes | Where-Object { $_.Id -eq $masterId } | Select-Object -First 1
  if (-not $masterNode) { throw "master node missing" }
  $killAt = Get-Date
  Log "killing master $($masterNode.Id) pid=$($masterNode.Pid)"
  Stop-Process -Id $masterNode.Pid -Force -ErrorAction SilentlyContinue

  $survivorHttp = ($nodes | Where-Object { $_.Id -ne $masterId } | Select-Object -First 1).Http
  $promoted = $false
  $takeoverSec = -1
  for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Milliseconds 500
    try {
      $tw2 = curl.exe -s -m 2 "http://127.0.0.1:$survivorHttp/admin/v1/timewheels" | ConvertFrom-Json
      $item = $tw2.data.items | Where-Object { $_.tw_id -eq $assignment.tw_id } | Select-Object -First 1
      if ($item -and $item.master -and $item.master -ne $masterId) {
        $takeoverSec = ((Get-Date) - $killAt).TotalSeconds
        Log "takeover new_master=$($item.master) after ${takeoverSec}s"
        $promoted = $true
        break
      }
    } catch {}
  }
  if (-not $promoted) { throw "master takeover not observed" }
  if ($takeoverSec -gt 10) { Log "WARN takeover ${takeoverSec}s exceeds 10s budget" }

  if (-not (Wait-Delivered $survivorHttp $msgHttp 70)) { throw "HTTP message $msgHttp not DELIVERED after failover" }
  if (-not (Test-Path $hitFile)) { throw "HTTP DELIVERED but receiver got no POST" }
  Log "PASS HTTP sink after failover msg=$msgHttp"

  if (-not (Wait-Delivered $survivorHttp $msgKafka 70)) { throw "Kafka message $msgKafka not DELIVERED after failover" }

  # kafka-console-consumer prints progress on stderr; capture without NativeCommandError
  $consumeFile = Join-Path $runDir "kafka-consume.txt"
  cmd /c "docker exec when-local-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic $topic --from-beginning --max-messages 1 --timeout-ms 15000 > `"$consumeFile`" 2>&1"
  $consume = if (Test-Path $consumeFile) { Get-Content -Raw $consumeFile } else { "" }
  Log "kafka_consume=$($consume.Trim())"
  if ($consume -notmatch "ha-kafka-sink|Processed a total of 1 messages") {
    throw "Kafka DELIVERED but console-consumer found no proof"
  }
  Log "PASS Kafka sink after failover msg=$msgKafka"

  Log "=== HA 3-node HTTP+Kafka sink smoke PASS ==="
  Write-Host "HA_3NODE_HTTP_KAFKA_PASS"
  Write-Host "SUMMARY=$summary"
}
finally {
  if ($recv -and -not $recv.HasExited) {
    Stop-Process -Id $recv.Id -Force -ErrorAction SilentlyContinue
  }
}
