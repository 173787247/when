# Integration Test Report (Lesson 53 follow-along)

- Date: 2026-09-07
- Git branch: `main` @ `8fac3fd` (working tree dirty; not a clean Loop Runner run)
- Run style: manual study/judge of existing code + live single-node smoke
- Environment: Windows host; Docker Redis/ETCD/Kafka/Prometheus/Grafana/OTEL/Tempo/Loki;
  When process HTTP `28080`, management `18081`, gRPC `29090`

## Scope vs official gate

Official `harness/release/run-full-integration.sh` and `run-ha-failover.sh` are **absent**
from this checkout (`harness/release/` missing). This report records commands that were
actually executed and prior lesson evidence. It does **not** claim `SECOND PHASE COMPLETE`.

## Results

| Area | Result | Evidence |
|---|---|---|
| Submit FILE Sink | PASS | `POST /api/v1/messages` → `201`, id `90119327742693376` |
| Query → DELIVERED | PASS | Query after due → `DELIVERED`; admin details include SUCCESS attempt |
| Cancel PENDING | PASS | Submit then `DELETE` → `CANCELLED` |
| Business Idempotency-Key | N/A / gap | OpenAPI submit has no Idempotency-Key; duplicate header produced two ids |
| Admin list nodes/messages/timewheels | PASS | `GET /admin/v1/cluster/nodes` etc. on port 28080 |
| Create timewheel | FAIL 503 | `CONTROLLER_UNAVAILABLE` on single-node (Master/Slave co-location rule) |
| `/health` `/ready` | PASS | management `18081` |
| Metrics `when_*` | PASS | includes `when_delivery_lag_seconds_*`, controller elections |
| Kafka real delivery | PASS (prior) | lesson49 Kafka E2E |
| Observability stack | PASS (prior) | lesson50 Prometheus + Grafana/OTEL |
| Three-node HA failover <10s | FAIL | 3 nodes + create TW OK; kill Master → assignment stuck (see ha-3node-practical.md) |
| Module HA unit/IT | PASS (prior) | lesson47/48 cluster verify; this session recompile hit empty protobuf outputs |

## Live message summary

- Delivered: at least 1 (`90119327742693376`, FILE, retry_count=0)
- Cancelled: at least 1
- Lost after successful persist: 0 observed in this smoke
- Cluster nodes registered: 1 (`local-node`, ready=true, controller=true)

## Logs

- `.loop/lesson53-smoke-summary.txt`
- `.loop/lesson53-integration-smoke.log` (partial; avoid UTF-16 Tee corruption)
- Prior: `.loop/lesson49-kafka-e2e.log`, lesson50/51 checklists

## Objective limits

1. No `harness/release/*` scripts → cannot run official release-candidate judges.
2. Single-node only → cannot demonstrate Master stop / Slave promote timeline.
3. Loop Runner `validate` expects `master` branch; repo default is `main`.
4. Not an `./loop run --phase second` Codex orchestration; lessons 47–52 were judged manually earlier.
