# HA 3-node practical test (student fork)

Date: 2026-09-07  
Script: `harness/local/run-ha-3node-windows.ps1`  
Log: `.loop/ha-3node/summary.txt`

## Environment

- Redis/ETCD: Docker `when-local-redis` / `when-local-etcd`
- Nodes: when-1 `:28080/:18081/:29090`, when-2 `:28081/:18082/:29091`, when-3 `:28082/:18083/:29092`
- Runner jar: `when-app/target/when-app-1.0.0-SNAPSHOT-runner.jar`

## Results

| Step | Result | Notes |
|------|--------|-------|
| Start 3 nodes `/ready` | **PASS** | All three UP; when-1 elected Controller |
| `GET /admin/v1/cluster/nodes` | **PASS** | 3 ready nodes |
| `POST /admin/v1/timewheels` | **PASS*** | HTTP often **500** after ~5s (etcd block / jetcd), but assignment is committed: Master≠Slave |
| Submit FILE message | **PASS** | e.g. `90246593697026048` PENDING |
| Kill Master process | **PASS** | when-1 gone from membership; when-2 becomes Controller |
| Master assignment takeover ≤10s | **FAIL** | TW still `master=when-1` after >60s; no `NODE_LEFT` handling on new Controller |
| Message reaches DELIVERED after kill | **FAIL** | Stays `PENDING` past `deliver_at` (Master dead, no promote) |

\*Treat create as PASS only because list API shows a valid M/S assignment immediately after the 500.

## Observed failure mode

1. Create/path stresses etcd (blocked event-loop, `EtcdClientException` heartbeats).
2. Killing Master+Controller leaves assignment pointing at a dead Master.
3. New Controller (`when-2`) does not apply `NODE_LEFT` failover for the existing wheel (no failover log lines).

This is an environment/runtime gap relative to lesson 53’s HA gate — not claimed as `SECOND PHASE COMPLETE`.

## Pass criteria still open

- [ ] Takeover with `master` flipped away from killed node within 10s
- [ ] Persisted message reaches a terminal delivered state after failover
- [ ] Official `harness/release/run-ha-failover.sh` (still missing upstream)

## Next retries

1. Stabilize etcd (dedicated container, `NO_PROXY`, no VPN) before create.
2. Prefer Master ≠ Controller before kill (hard with current placer).
3. Capture Controller logs around lease expiry / `NODE_LEFT`.
