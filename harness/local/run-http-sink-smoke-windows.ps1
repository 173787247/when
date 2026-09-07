# Single-node HTTP Sink smoke (local HttpListener). Redis/ETCD + when-app jar required.
$ErrorActionPreference = "Stop"
$repo = "c:\Users\rchua\Desktop\AIFullStackDevelopment\when"
Set-Location $repo
$env:JAVA_HOME = "C:\Users\rchua\tools\jdk-21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$appJar = "when-app\target\when-app-1.0.0-SNAPSHOT-runner.jar"
if (-not (Test-Path $appJar)) { throw "missing jar" }

$runDir = ".loop\http-sink-smoke"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$script:receivedCount = 0
$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add("http://127.0.0.1:18099/")
$listener.Start()
$null = $listener.BeginGetContext({
  param($ar)
  $l = $ar.AsyncState
  try {
    $ctx = $l.EndGetContext($ar)
    $reader = New-Object IO.StreamReader($ctx.Request.InputStream)
    [void]$reader.ReadToEnd()
    $script:receivedCount++
    $ctx.Response.StatusCode = 200
    $bytes = [Text.Encoding]::UTF8.GetBytes("ok")
    $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
    $ctx.Response.Close()
  } catch {}
}, $listener)

Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -match 'when-app-1.0.0-SNAPSHOT-runner' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep 2
docker exec -e ETCDCTL_API=3 when-local-etcd etcdctl del --prefix /when 2>$null | Out-Null

$env:NO_PROXY = "127.0.0.1,localhost"; $env:no_proxy = $env:NO_PROXY
foreach ($p in @("HTTP_PROXY","HTTPS_PROXY","ALL_PROXY","http_proxy","https_proxy","all_proxy","OTEL_EXPORTER_OTLP_ENDPOINT")) {
  Remove-Item "Env:$p" -ErrorAction SilentlyContinue
}
$env:WHEN_REDIS_HOST="127.0.0.1"; $env:WHEN_REDIS_PORT="6379"
$env:WHEN_ETCD_ENDPOINTS="http://127.0.0.1:2379"
$env:WHEN_NODE_ID="http-smoke"; $env:WHEN_WORKER_ID="9"
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

Set-Content -Encoding ascii "$runDir\submit.json" '{"delay_seconds":5,"sink_type":"HTTP","sink_config":{"http":{"url":"http://127.0.0.1:18099/when","method":"POST","timeout_ms":5000}},"payload":"aGVsbG8=","business_tag":"http-smoke"}'
$sub = curl.exe -s -X POST http://127.0.0.1:28080/api/v1/messages -H "Content-Type: application/json" -H "X-Request-Id: http-1" --data-binary "@$runDir/submit.json"
Write-Host "submit=$sub"
$msgId = ($sub | ConvertFrom-Json).data.message_id
$delivered=$false
for ($i=0; $i -lt 30; $i++) {
  Start-Sleep 1
  $q = curl.exe -s "http://127.0.0.1:28080/api/v1/messages/$msgId"
  if ($q -match '"status"\s*:\s*"DELIVERED"') { $delivered=$true; Write-Host "query=$q"; break }
}
Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
try { $listener.Stop(); $listener.Close() } catch {}
if (-not $delivered) { throw "HTTP sink not DELIVERED" }
Write-Host "HTTP_SINK_SMOKE_PASS msg=$msgId received=$script:receivedCount"
