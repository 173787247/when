# WHEN FILE Sink full E2E on Windows + Docker Desktop
# Current main no longer emits event=when_due; official PASS marker is FILE_SINK_FULL_E2E=PASS
# (see 全流程测试报告.md). Old harness/core-tests/run-single-node-flow.sh is incompatible.
$ErrorActionPreference = "Stop"
$repo = "c:\Users\rchua\Desktop\AIFullStackDevelopment\when"
Set-Location $repo

$env:JAVA_HOME = "C:\Users\rchua\tools\jdk-21"
$env:PATH = "C:\Users\rchua\tools\jdk-21\bin;C:\Program Files\Docker\Docker\resources\bin;C:\Users\rchua\tools\apache-maven\bin;$env:PATH"

$appJar = Join-Path $repo "when-app\target\when-app-1.0.0-SNAPSHOT-runner.jar"
$clientJar = Join-Path $repo "when-e2e-test\target\when-e2e-test-1.0.0-SNAPSHOT-runner.jar"
if (-not (Test-Path $appJar) -or -not (Test-Path $clientJar)) {
  throw "missing runner jars; run mvn package first"
}

function Get-UniqueFreePorts([int]$count) {
  $listeners = @()
  try {
    while ($listeners.Count -lt $count) {
      $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
      $listener.Start()
      $listeners += $listener
    }
    return @($listeners | ForEach-Object { ([System.Net.IPEndPoint]$_.LocalEndpoint).Port })
  } finally {
    foreach ($listener in $listeners) { $listener.Stop() }
  }
}

function Get-FreePort {
  return (Get-UniqueFreePorts 1)[0]
}

function Get-PublishedPort([string]$container, [int]$containerPort) {
  $line = (cmd /c "docker port $container $containerPort 2>nul" | Select-Object -First 1)
  if (-not $line) { return $null }
  $m = [regex]::Match("$line", ':(\d+)\s*$')
  if ($m.Success) { return [int]$m.Groups[1].Value }
  return $null
}

function Start-E2eDeps {
  docker rm -f when-e2e-redis when-e2e-etcd 2>$null | Out-Null
  docker run -d --name when-e2e-redis -p 6379 redis:7-alpine | Out-Null
  # Retry etcd publish — Docker Desktop occasionally starts etcd with no host port mapping.
  $etcdOk = $false
  for ($i = 0; $i -lt 5; $i++) {
    docker rm -f when-e2e-etcd 2>$null | Out-Null
    docker run -d --name when-e2e-etcd -p 2379 quay.io/coreos/etcd:v3.5.16 `
      /usr/local/bin/etcd --name when-e2e --data-dir /tmp/etcd-data `
      --listen-client-urls http://0.0.0.0:2379 `
      --advertise-client-urls http://127.0.0.1:2379 | Out-Null
    Start-Sleep -Seconds 2
    if (Get-PublishedPort when-e2e-etcd 2379) { $etcdOk = $true; break }
    Write-Host "etcd publish retry=$i"
  }
  if (-not $etcdOk) { throw "etcd host port not published" }
  $script:redisPort = Get-PublishedPort when-e2e-redis 6379
  $script:etcdPort = Get-PublishedPort when-e2e-etcd 2379
  if (-not $redisPort -or -not $etcdPort) { throw "deps ports missing redis=$redisPort etcd=$etcdPort" }

  $deadline = (Get-Date).AddSeconds(90)
  while ((Get-Date) -lt $deadline) {
    $pong = cmd /c "docker exec when-e2e-redis redis-cli ping 2>nul"
    try {
      $h = Invoke-RestMethod "http://127.0.0.1:$etcdPort/health" -TimeoutSec 2
      if ($pong -eq "PONG" -and $h) {
        cmd /c "docker exec -e ETCDCTL_API=3 when-e2e-etcd etcdctl --endpoints=http://127.0.0.1:2379 put /when/e2e-probe ok >nul 2>nul"
        $got = cmd /c "docker exec -e ETCDCTL_API=3 when-e2e-etcd etcdctl --endpoints=http://127.0.0.1:2379 get /when/e2e-probe --print-value-only 2>nul"
        if (("$got").Trim() -eq "ok") {
          Write-Host "deps ready redis=$redisPort etcd=$etcdPort"
          return
        }
      }
    } catch {}
    Start-Sleep -Seconds 1
  }
  throw "deps not ready redis=$redisPort etcd=$etcdPort"
}

Start-E2eDeps


$runDir = Join-Path $repo ".loop\core-flow-win"
$fileSinkDir = Join-Path $runDir "file-sink"
if (Test-Path $runDir) { Remove-Item $runDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $runDir, $fileSinkDir | Out-Null
$serverLog = Join-Path $runDir "server.log"
$results = Join-Path $runDir "e2e-results.log"
"" | Set-Content $results

$grpcPort = Get-FreePort
$httpPort = Get-FreePort
$mgmtPort = Get-FreePort
$nodeId = "e2e-node"
$workerId = "777"
$payloadText = "when-file-e2e-hello"
$payloadB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($payloadText))

$env:WHEN_REDIS_HOST = "127.0.0.1"
$env:WHEN_REDIS_PORT = "$redisPort"
$env:WHEN_ETCD_ENDPOINTS = "http://127.0.0.1:$etcdPort"
$env:WHEN_NODE_ID = $nodeId
$env:WHEN_WORKER_ID = $workerId
$env:WHEN_GRPC_PORT = "$grpcPort"
$env:WHEN_HTTP_PORT = "$httpPort"
$env:WHEN_MANAGEMENT_PORT = "$mgmtPort"
$env:WHEN_TIMEWHEEL_COUNT = "1"
$env:WHEN_NODE_HOST = "127.0.0.1"
$env:WHEN_FILE_SINK_BASE_DIR = $fileSinkDir
Write-Host "grpc=$grpcPort http=$httpPort management=$mgmtPort file_sink=$fileSinkDir"

$javaOpts = @("-Djava.net.useSystemProxies=false")
$apiBase = "http://127.0.0.1:$httpPort"

function Stop-WhenServer {
  if ($serverPid) {
    Stop-Process -Id $serverPid -Force -ErrorAction SilentlyContinue
  }
  Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match 'when-app-1.0.0-SNAPSHOT-runner' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
  $ports = @($grpcPort, $httpPort, $mgmtPort)
  $deadline = (Get-Date).AddSeconds(20)
  while ((Get-Date) -lt $deadline) {
    $busy = $false
    foreach ($port in $ports) {
      $listeners = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
      if ($listeners) { $busy = $true; break }
    }
    if (-not $busy) { break }
    Start-Sleep -Milliseconds 250
  }
  $script:serverPid = $null
}

function Start-WhenServer {
  $errLog = Join-Path $runDir "server.err.log"
  if (Test-Path $serverLog) { Remove-Item $serverLog -Force }
  if (Test-Path $errLog) { Remove-Item $errLog -Force }
  foreach ($port in @($grpcPort, $httpPort, $mgmtPort)) {
    Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
      ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
  }
  Start-Sleep -Milliseconds 300
  $env:WHEN_HTTP_PORT = "$httpPort"
  $env:WHEN_MANAGEMENT_PORT = "$mgmtPort"
  $env:WHEN_GRPC_PORT = "$grpcPort"
  $env:WHEN_E2E_SERVER_PORT = "$grpcPort"
  $p = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" `
    -ArgumentList ($javaOpts + @("-jar", $appJar)) `
    -WorkingDirectory $repo `
    -RedirectStandardOutput $serverLog `
    -RedirectStandardError $errLog `
    -PassThru -WindowStyle Hidden
  $script:serverPid = $p.Id
  $script:serverErrLog = $errLog
  Write-Host "server pid=$($p.Id) grpc=$grpcPort http=$httpPort management=$mgmtPort"
}

function Client-Text([string[]]$ClientArgs) {
  $raw = & java.exe @javaOpts -jar $clientJar @ClientArgs 2>&1
  return (($raw | ForEach-Object { "$_" }) -join "`n")
}

function Wait-Ready {
  for ($i = 0; $i -lt 240; $i++) {
    if ($serverPid -and -not (Get-Process -Id $serverPid -ErrorAction SilentlyContinue)) {
      Get-Content $serverLog,$serverErrLog -Tail 80 -ErrorAction SilentlyContinue
      throw "FAIL server_exited"
    }
    try {
      $out = Client-Text @("health")
      $httpOk = $false
      try {
        Invoke-WebRequest -Uri "$apiBase/api/v1/messages/0" -Method Get -TimeoutSec 1 -Headers @{ "X-Request-Id" = "ready-probe" } | Out-Null
        $httpOk = $true
      } catch {
        # 404/400 means HTTP is up; connection refused means not ready
        if ($_.Exception.Response -or ($_.ErrorDetails -and $_.ErrorDetails.Message)) { $httpOk = $true }
        elseif ("$($_.Exception.Message)" -match "404|400|Not Found|Bad Request") { $httpOk = $true }
      }
      if ($out -match "health=SERVING" -and $httpOk) {
        $n = docker exec -e ETCDCTL_API=3 when-e2e-etcd etcdctl --endpoints=http://127.0.0.1:2379 get "/when/nodes/$nodeId"
        $w = docker exec -e ETCDCTL_API=3 when-e2e-etcd etcdctl --endpoints=http://127.0.0.1:2379 get "/when/workers/$workerId"
        $nText = ($n | Out-String)
        $wText = ($w | Out-String)
        if ($nText -match [regex]::Escape($nodeId) -and $wText -match [regex]::Escape($nodeId)) {
          Add-Content $results $out
          Write-Host "server_ready"
          return
        }
      }
    } catch {}
    Start-Sleep -Milliseconds 250
  }
  Get-Content $serverLog,$serverErrLog -Tail 80 -ErrorAction SilentlyContinue
  throw "FAIL server_not_ready"
}

function Submit-File([int]$delaySeconds, [string]$relPath, [string]$requestId) {
  $body = @{
    delay_seconds = $delaySeconds
    sink_type = "FILE"
    sink_config = @{ file = @{ path = $relPath } }
    payload = $payloadB64
    business_tag = "e2e-file"
  } | ConvertTo-Json -Compress -Depth 5
  $resp = Invoke-RestMethod -Uri "$apiBase/api/v1/messages" -Method Post `
    -Headers @{ "Content-Type" = "application/json"; "X-Request-Id" = $requestId } `
    -Body $body
  $json = $resp | ConvertTo-Json -Compress -Depth 5
  Add-Content $results $json
  if ($resp.code -ne "OK") { throw "FAIL submit code=$($resp.code) $json" }
  $id = $resp.data.message_id
  $status = "$($resp.data.status)"
  if (-not $id -or $status -ne "PENDING") { throw "FAIL submit id/status id=$id status=$status" }
  return $id
}

function Query-Status([string]$messageId) {
  $resp = Invoke-RestMethod -Uri "$apiBase/api/v1/messages/$messageId" -Method Get `
    -Headers @{ "X-Request-Id" = "query-$messageId" }
  return "$($resp.data.status)"
}

function Cancel-Message([string]$messageId) {
  $resp = Invoke-RestMethod -Uri "$apiBase/api/v1/messages/$messageId" -Method Delete `
    -Headers @{ "X-Request-Id" = "cancel-$messageId" }
  return "$($resp.data.status)"
}

function Redis-HasStatus([string]$messageId, [string]$expected) {
  $value = docker exec when-e2e-redis redis-cli --raw GET "when:msg:$messageId"
  $needle = '"status":"' + $expected + '"'
  if ($value -notlike "*$needle*") { throw "FAIL redis_status expected=$expected value=$value" }
  Write-Host "redis_status=$expected message_id=$messageId"
}

function Wait-Status([string]$messageId, [string]$expected, [int]$attempts = 80) {
  for ($i = 0; $i -lt $attempts; $i++) {
    try {
      $status = Query-Status $messageId
      if ($status -eq $expected) {
        Add-Content $results "message_id=$messageId status=$status"
        return
      }
    } catch {}
    Start-Sleep -Milliseconds 250
  }
  throw "FAIL wait_status message_id=$messageId expected=$expected"
}

function Assert-File([string]$relPath, [bool]$shouldExist) {
  $full = Join-Path $fileSinkDir $relPath
  if ($shouldExist) {
    if (-not (Test-Path $full)) { throw "FAIL file_missing $full" }
    $got = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($full))
    if ($got -ne $payloadText) { throw "FAIL file_payload expected=$payloadText got=$got" }
    Write-Host "file_ok path=$relPath"
  } else {
    if (Test-Path $full) { throw "FAIL file_should_absent $full" }
    Write-Host "file_absent path=$relPath"
  }
}

function Delivery-Log-Count([string]$messageId) {
  $count = 0
  foreach ($log in @($serverLog, $serverErrLog)) {
    if ($log -and (Test-Path $log)) {
      $count += @(Select-String -Path $log -Pattern $messageId -SimpleMatch -ErrorAction SilentlyContinue |
        Where-Object { $_.Line -match '"event":"message_delivered"' }).Count
    }
  }
  return $count
}

try {
  $env:WHEN_E2E_SERVER_HOST = "127.0.0.1"
  $started = $false
  for ($attempt = 1; $attempt -le 3; $attempt++) {
    try {
      Start-WhenServer
      Wait-Ready
      $started = $true
      break
    } catch {
      Write-Host "start_attempt=$attempt failed: $_"
      Stop-WhenServer
      Start-Sleep -Seconds 2
    }
  }
  if (-not $started) { throw "FAIL server_start_retries_exhausted" }
  Start-Sleep -Seconds 2
  Wait-Ready

  $duePath = "deliveries/normal.bin"
  $dueId = Submit-File 2 $duePath "normal-submit"
  Wait-Status $dueId "PENDING" 20
  Redis-HasStatus $dueId "PENDING"
  Wait-Status $dueId "DELIVERED" 80
  Redis-HasStatus $dueId "DELIVERED"
  Assert-File $duePath $true
  $dueLogs = Delivery-Log-Count $dueId
  if ($dueLogs -ne 1) { throw "FAIL delivery_log_count expected=1 got=$dueLogs" }
  Write-Host "due_flow=PASS message_id=$dueId"
  Add-Content $results "normal_final_status=DELIVERED"

  $cancelPath = "deliveries/cancel.bin"
  $cancelId = Submit-File 5 $cancelPath "cancel-submit"
  Wait-Status $cancelId "PENDING" 20
  Redis-HasStatus $cancelId "PENDING"
  $cancelStatus = Cancel-Message $cancelId
  if ($cancelStatus -ne "CANCELLED") { throw "FAIL cancel_status=$cancelStatus" }
  Wait-Status $cancelId "CANCELLED" 20
  Redis-HasStatus $cancelId "CANCELLED"
  Start-Sleep -Seconds 8
  Assert-File $cancelPath $false
  Write-Host "cancel_flow=PASS message_id=$cancelId"
  Add-Content $results "cancel_status=CANCELLED"
  Add-Content $results "cancel_file_absent=PASS"

  $restartPath = "deliveries/restart.bin"
  # Keep deliver_at in the future across stop + lease wait + restart so delivery
  # happens after the new process is fully up (and structured logs are reliable).
  $restartId = Submit-File 90 $restartPath "restart-submit"
  Wait-Status $restartId "PENDING" 20
  Redis-HasStatus $restartId "PENDING"
  Stop-WhenServer
  Start-Sleep -Seconds 1
  Redis-HasStatus $restartId "PENDING"
  $leaseDeadline = (Get-Date).AddSeconds(15)
  while ((Get-Date) -lt $leaseDeadline) {
    $n = docker exec -e ETCDCTL_API=3 when-e2e-etcd etcdctl --endpoints=http://127.0.0.1:2379 get "/when/nodes/$nodeId" 2>$null
    if (-not $n) { break }
    Start-Sleep -Milliseconds 500
  }
  Redis-HasStatus $restartId "PENDING"
  $restartStarted = $false
  for ($attempt = 1; $attempt -le 3; $attempt++) {
    try {
      Start-WhenServer
      Wait-Ready
      $restartStarted = $true
      break
    } catch {
      Write-Host "restart_attempt=$attempt failed: $_"
      Stop-WhenServer
      Start-Sleep -Seconds 2
    }
  }
  if (-not $restartStarted) { throw "FAIL restart_server_retries_exhausted" }
  Redis-HasStatus $restartId "PENDING"
  Wait-Status $restartId "DELIVERED" 480
  Redis-HasStatus $restartId "DELIVERED"
  Assert-File $restartPath $true
  Start-Sleep -Seconds 1
  $restartLogs = Delivery-Log-Count $restartId
  if ($restartLogs -lt 1) { throw "FAIL restart_delivery_log_count expected>=1 got=$restartLogs" }
  Write-Host "restart_flow=PASS message_id=$restartId"
  Add-Content $results "restart_final_status=DELIVERED"

  "FILE_SINK_FULL_E2E=PASS" | Tee-Object -FilePath $results -Append
  "LOCAL_CLEANUP=PASS" | Tee-Object -FilePath $results -Append
  Write-Host "results_file=$results"
}
finally {
  Stop-WhenServer
  docker rm -f when-e2e-redis when-e2e-etcd 2>$null | Out-Null
}
