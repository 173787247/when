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

- [ ] Takeover within **10s** on this Windows/Docker etcd setup
- [ ] Official `harness/release/*` / `./loop run --phase second` → `SECOND PHASE COMPLETE`
- [ ] Push to `oryx-labs/when` (never; fork only)

## Next

1. Keep lease TTL at **30s** on this Windows/Docker setup.
2. Official ≤10s budget remains an open gap vs lesson 53 gate.
3. Evidence is on fork `main` via PR #2.

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