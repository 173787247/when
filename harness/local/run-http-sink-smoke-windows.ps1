# Single-node HTTP Sink smoke. Requires WHEN_HTTP_SINK_ALLOW_LOOPBACK=true for 127.0.0.1.
$ErrorActionPreference = "Stop"
$repo = "c:\Users\rchua\Desktop\AIFullStackDevelopment\when"
Set-Location $repo
$env:JAVA_HOME = "C:\Users\rchua\tools\jdk-21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$appJar = "when-app\target\when-app-1.0.0-SNAPSHOT-runner.jar"
if (-not (Test-Path $appJar)) { throw "missing jar" }

$runDir = ".loop\http-sink-smoke"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$hitFile = Join-Path $runDir "hit.txt"
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
$pyFile = Join-Path (Resolve-Path $runDir).Path "receiver.py"
Set-Content -Encoding utf8 $pyFile $py
$recv = Start-Process -FilePath "python" -ArgumentList @("`"$pyFile`"") `
  -RedirectStandardOutput ((Join-Path $runDir "recv.out")) -RedirectStandardError ((Join-Path $runDir "recv.err")) `
  -PassThru -WindowStyle Hidden
Start-Sleep -Seconds 1
# sanity: receiver up
try { Invoke-WebRequest -Uri "http://127.0.0.1:18099/" -Method POST -Body "ping" -TimeoutSec 2 -UseBasicParsing | Out-Null } catch {}
if (-not (Test-Path $hitFile)) {
  # GET may 501; POST to /when
  try { Invoke-WebRequest -Uri "http://127.0.0.1:18099/when" -Method POST -Body "ping" -TimeoutSec 2 -UseBasicParsing | Out-Null } catch {}
}
if (-not (Test-Path $hitFile)) { throw "python HTTP receiver not accepting POST on :18099; see recv.err" }
Remove-Item $hitFile -Force

Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -match 'when-app-1.0.0-SNAPSHOT-runner' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep 2
docker start when-local-redis when-local-etcd 2>$null | Out-Null
docker exec -e ETCDCTL_API=3 when-local-etcd etcdctl del --prefix /when 2>$null | Out-Null

$env:NO_PROXY = "127.0.0.1,localhost"; $env:no_proxy = $env:NO_PROXY
foreach ($p in @("HTTP_PROXY","HTTPS_PROXY","ALL_PROXY","http_proxy","https_proxy","all_proxy","OTEL_EXPORTER_OTLP_ENDPOINT")) {
  Remove-Item "Env:$p" -ErrorAction SilentlyContinue
}
$env:WHEN_HTTP_SINK_ALLOW_LOOPBACK = "true"
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
$sub = curl.exe -s -X POST http://127.0.0.1:28080/api/v1/messages -H "Content-Type: application/json" -H "X-Request-Id: http-2" --data-binary "@$runDir/submit.json"
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
Stop-Process -Id $recv.Id -Force -ErrorAction SilentlyContinue
if (-not $delivered) { throw "HTTP sink not DELIVERED" }
if (-not (Test-Path $hitFile)) { throw "HTTP sink DELIVERED but receiver got no POST" }
Write-Host "HTTP_SINK_SMOKE_PASS msg=$msgId"
