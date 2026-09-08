# HA 3-node practical test (fork landing)

Date: 2026-09-07  
Script: `harness/local/run-ha-3node-windows.ps1`  
Evidence: [`fork-verification-report.md`](fork-verification-report.md)、[`evidence/`](evidence/)；本机 `.loop/` 仅复盘  
Commit: `2e3297a` (assignment-watch promote); docs on `main` after PR #2/#3

## Environment

- Redis/ETCD only: Docker `when-local-redis` / `when-local-etcd` (observability stack stopped)
- Nodes: when-1 `:28080/:18081/:29090`, when-2 `:28081/:18082/:29091`, when-3 `:28082/:18083/:29092`
- Runner jar: `when-app/target/when-app-1.0.0-SNAPSHOT-runner.jar`
- Local knobs: `WHEN_ETCD_LEASE_TTL_SECONDS=30`, `WHEN_ETCD_HEARTBEAT_INTERVAL_MS=5000`, no OTEL

## Results (latest clean run)

| Step | Result | Notes |
|------|--------|-------|
| Start 3 nodes `/ready` | **PASS** | Sequential start; netstat port cleanup (avoid `Get-NetTCPConnection` stalls) |
| `GET /admin/v1/cluster/nodes` | **PASS** | 3 ready nodes on first wait |
| Time wheel `tw-0` assigned | **PASS** | Controller bootstrap; Master≠Slave (`when-1`/`when-2`) |
| Submit FILE message | **PASS** | e.g. `90393903336787968` |
| Kill Master process | **PASS** | Kill when-1 |
| Master assignment takeover | **PASS*** | New master `when-2` after ~30s (lease TTL bound) |
| Message `DELIVERED` after kill | **PASS** | Delivered after promote rebuild from Redis |

\*Official lesson budget is ≤10s. This machine uses a 30s etcd lease TTL for membership stability under Docker Desktop; detection cannot beat lease expiry. Retest 2026-09-07 evening: takeover ~45s, still **DELIVERED PASS**. Master+Controller extension: controller re-elect ~28s + second message **DELIVERED PASS** (`.loop/ha-3node-ctrl.log`).

## Fixes that made deliver PASS

1. **Assignment watch promote**: when Controller ≠ new Master, the new Master applies `FailoverExecutor` locally after ETCD assignment PUT.
2. **Replica store read-through**: `current()` reads ETCD so promote does not race a lagging watch cache.
3. **Controller election wiring** + absent-Master reconcile (earlier commits).
4. **HA script hardening**: no OTEL to dead `:4317`, longer lease, sequential ready, exact `"status":"DELIVERED"` match.

## Still not claimed

- [x] Takeover within **10s** on native etcd 3.5.16 + streaming keepAlive (4.38s)
- [ ] Official `harness/release/*` / `./loop run --phase second` → `SECOND PHASE COMPLETE`
- [ ] Push to `oryx-labs/when` (never; fork only)

## Next

1. Prefer official 6s/2s on native etcd 3.5.16 after the streaming keepAlive change.
2. Docker etcd + periodic `keepAliveOnce` remains the old FAIL path; do not use it for the 10s gate.
3. Official `SECOND PHASE COMPLETE` still waits on `harness/release`.

## TTL=10 retest (2026-09-08)

Command: `WHEN_HA_LEASE_TTL_SECONDS=10 WHEN_HA_HEARTBEAT_MS=2000` + `run-ha-3node-windows.ps1`  
Evidence: [`evidence/ha-3node-ttl10-summary.txt`](evidence/ha-3node-ttl10-summary.txt)

| Step | Result |
|------|--------|
| 3 nodes `/ready` + 3 members | PASS |
| Time wheel before submit | **UNSTABLE**：多次 `INTERNAL_ERROR`，随后 `recovering` / `out_of_sync`（尚未杀主） |
| Kill Master | 已杀 when-2 |
| Takeover ≤10s | **FAIL / 剧本挂死**（杀主后无 takeover 日志，需手动停进程） |

结论：本机 Docker Desktop etcd **不能**用官方 6s/10s TTL 做 10 秒门；继续 TTL=30。

## Official 6s/2s after slim Docker (2026-09-08)

Stopped 22 unrelated containers; only `when-local-redis` / `when-local-etcd` / `when-local-kafka` left.  
Command: `WHEN_HA_LEASE_TTL_SECONDS=6 WHEN_HA_HEARTBEAT_MS=2000`  
Evidence: [`evidence/ha-3node-ttl6-summary.txt`](evidence/ha-3node-ttl6-summary.txt)

Same failure as TTL=10: `INTERNAL_ERROR` then `recovering/out_of_sync` **before** kill; harness hung after killing `when-2`. Slimming Docker did **not** unlock the 10s gate.

## Native etcd 3.5.16 (2026-09-08)

Host binary `C:\Users\rchua\tools\etcd-v3.5.16` on `127.0.0.1:2379` (Docker etcd stopped).  
Official 6s/2s still FAIL: jetcd `etcd_heartbeat` / `EtcdClientException` before kill.  
Evidence: [`evidence/ha-3node-native-etcd-ttl6-summary.txt`](evidence/ha-3node-native-etcd-ttl6-summary.txt)

## Streaming keepAlive + official 6s/2s (2026-09-08)

Membership now uses jetcd streaming `keepAlive` instead of periodic `keepAliveOnce`. Watch callbacks are dispatched off the jetcd Vert.x loop so Controller lock + blocking etcd cannot starve the lease stream.

Command: `WHEN_HA_LEASE_TTL_SECONDS=6 WHEN_HA_HEARTBEAT_MS=2000` + native etcd 3.5.16  
Evidence: [`evidence/ha-3node-keepalive-ttl6-summary.txt`](evidence/ha-3node-keepalive-ttl6-summary.txt)

| Step | Result |
|------|--------|
| 3 nodes ready, members stay registered | **PASS** |
| `tw-0` running/in_sync before kill | **PASS** (when-1 / when-2) |
| Kill Master when-1 | takeover when-2 **4.38s** |
| FILE after failover | **DELIVERED** `90633011313250304` |
| Kill Controller when-3 | re-elect when-2 **4.32s** |
| FILE after re-elect | **DELIVERED** `90633096361156608` |

Local official ≤10s gate: **PASS**.
