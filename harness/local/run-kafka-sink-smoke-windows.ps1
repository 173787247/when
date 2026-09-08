# Single-node Kafka Sink smoke against Docker when-local-kafka :9092.
$ErrorActionPreference = "Stop"
$repo = "c:\Users\rchua\Desktop\AIFullStackDevelopment\when"
Set-Location $repo
$env:JAVA_HOME = "C:\Users\rchua\tools\jdk-21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$appJar = "when-app\target\when-app-1.0.0-SNAPSHOT-runner.jar"
if (-not (Test-Path $appJar)) { throw "missing jar" }

$runDir = ".loop\kafka-sink-smoke"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$topic = "when-http-kafka-smoke"
docker start when-local-kafka when-local-redis when-local-etcd 2>$null | Out-Null
Start-Sleep -Seconds 3
# create topic (ignore exists)
docker exec when-local-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic $topic --partitions 1 --replication-factor 1 2>$null | Out-Null

Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -match 'when-app-1.0.0-SNAPSHOT-runner' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep 2
docker exec -e ETCDCTL_API=3 when-local-etcd etcdctl del --prefix /when 2>$null | Out-Null

$env:NO_PROXY = "127.0.0.1,localhost"; $env:no_proxy = $env:NO_PROXY
foreach ($p in @("HTTP_PROXY","HTTPS_PROXY","ALL_PROXY","http_proxy","https_proxy","all_proxy","OTEL_EXPORTER_OTLP_ENDPOINT")) {
  Remove-Item "Env:$p" -ErrorAction SilentlyContinue
}
$env:WHEN_KAFKA_BOOTSTRAP_SERVERS = "127.0.0.1:9092"
$env:WHEN_REDIS_HOST="127.0.0.1"; $env:WHEN_REDIS_PORT="6379"
$env:WHEN_ETCD_ENDPOINTS="http://127.0.0.1:2379"
$env:WHEN_NODE_ID="kafka-smoke"; $env:WHEN_WORKER_ID="8"
$env:WHEN_HTTP_PORT="28080"; $env:WHEN_MANAGEMENT_PORT="18081"; $env:WHEN_MANAGEMENT_HOST="127.0.0.1"
$env:WHEN_GRPC_PORT="29090"; $env:WHEN_NODE_HOST="127.0.0.1"; $env:WHEN_TIMEWHEEL_COUNT="1"
$env:WHEN_FILE_SINK_BASE_DIR=(New-Item -ItemType Directory -Force -Path "$runDir\file-sink").FullName
$env:WHEN_ETCD_LEASE_TTL_SECONDS="30"; $env:WHEN_LOG_FORMAT="json"
$proc = Start-Process "$env:JAVA_HOME\bin\java.exe" -ArgumentList @("-Djava.net.useSystemProxies=false","-jar",$appJar) `
  -WorkingDirectory $repo -RedirectStandardOutput "$runDir\out.log" -RedirectStandardError "$runDir\err.log" `
  -PassThru -WindowStyle Hidden

$ok=$false
for ($i=0; $i -lt 60; $i++) {
  try { if ((Invoke-WebRequest http://127.0.0.1:18081/ready -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200) { $ok=$true; break } } catch {}
  Start-Sleep 1
}
if (-not $ok) { throw "when not ready" }

$payload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("kafka-smoke-hello"))
$body = "{`"delay_seconds`":5,`"sink_type`":`"KAFKA`",`"sink_config`":{`"kafka`":{`"bootstrap_servers`":`"127.0.0.1:9092`",`"topic`":`"$topic`",`"key`":`"k1`"}},`"payload`":`"$payload`",`"business_tag`":`"kafka-smoke`"}"
Set-Content -Encoding ascii "$runDir\submit.json" $body
$sub = curl.exe -s -X POST http://127.0.0.1:28080/api/v1/messages -H "Content-Type: application/json" -H "X-Request-Id: kafka-1" --data-binary "@$runDir/submit.json"
Write-Host "submit=$sub"
$msgId = ($sub | ConvertFrom-Json).data.message_id
$delivered=$false
for ($i=0; $i -lt 40; $i++) {
  Start-Sleep 1
  $q = curl.exe -s "http://127.0.0.1:28080/api/v1/messages/$msgId"
  if ($q -match '"status"\s*:\s*"DELIVERED"') { $delivered=$true; Write-Host "query=$q"; break }
  if (($i % 5) -eq 0) { Write-Host "wait$i $q" }
}
Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
if (-not $delivered) { throw "Kafka sink not DELIVERED" }

# consume one record as extra proof (kafka tools write progress to stderr)
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$consume = docker exec when-local-kafka /opt/kafka/bin/kafka-console-consumer.sh `
  --bootstrap-server localhost:9092 --topic $topic --from-beginning --max-messages 1 --timeout-ms 10000 2>&1 |
  Out-String
$ErrorActionPreference = $prevEap
Write-Host "consume=$($consume.Trim())"
if ($consume -notmatch "kafka-smoke-hello|Processed a total of 1 messages") {
  throw "Kafka DELIVERED but console-consumer found no payload proof"
}
Write-Host "KAFKA_SINK_SMOKE_PASS msg=$msgId"
