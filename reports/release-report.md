# Release Report (Lesson 53 follow-along)

- Date: 2026-09-07
- Completion status: **NOT** `SECOND PHASE COMPLETE` (objective blockers below)

## Phase progress (manual judge, not Loop Runner state.json)

| Stage | Result | Notes |
|---|---|---|
| lesson47 | PASS | Replica/failover module tests; HA E2E scripts missing |
| lesson48 | PASS | Controller/rebalance module tests |
| lesson49 | PASS | HTTP/Kafka Sink + real Kafka E2E |
| lesson50 | PASS | Metrics/logs/OTLP + local Prometheus/Grafana stack |
| lesson51 | PARTIAL | Admin GET/UI PASS; create timewheel 503 single-node |
| lesson52 | PARTIAL | TGZ/kustomize/official Docker PASS; check-release MSYS FAIL |
| lesson53 delivery | PARTIAL | `USER_GUIDE.md` + reports written; official gates blocked |

Evidence index: `.loop/judge-results.txt`, `.loop/lesson47-52-checklist.md`, this folder.

## Loop Runner

- `loop.yaml` defines `second` phase, release_candidate_judges, `lesson53`, final judges — OK
- `./loop plan --phase second` — OK (after fixing `loop` CRLF)
- `./loop validate` — FAIL (`requires master`; repo on `main`)
- `./loop run --phase second` — **not executed** (matches prior follow-along choice; also blocked by missing `harness/release/*` and dirty tree / branch naming)

## Artifacts

- Server/Web TGZ under `dist/` (lesson52 version suffix)
- Image `whenproject/when:1.0.0-lesson52`
- K8s base under `deploy/k8s/base/`
- Docs: `DEPLOY.md`, `USER_GUIDE.md`
- Reports: `reports/integration-test-report.md`, `reports/deployment-verification-report.md`, this file

## Known limits / residual risk

1. **Missing `harness/release/`** — cannot satisfy official release-candidate or final judges.
2. **No reliable live Master failover** — 3-node join + create TW PASS; after killing Master, assignment stayed on dead node and message remained PENDING (see `reports/ha-3node-practical.md`).
3. **Single-node Controller create-timewheel 503** — resolved once ≥2 ready nodes are up; HTTP 500 flakiness remains under etcd stress.
4. **Business HTTP idempotency** — not in OpenAPI submit contract; do not document as implemented.
5. **Windows tooling** — MSYS tar duplicate listings; WSL `bash` ≠ Git Bash for `mvnw`; `JAVA_HOME` must be set explicitly.
6. **Dirty working tree** — Dockerfile/mvnw/openapi/vite and local `.loop`/`dist` changes not merged as lesson branches.

## Stop-condition checklist (official §12)

- [x] Lessons 47–52 code present and mostly judged PASS (with noted FAILs)
- [x] Basic FILE submit/query/cancel on live node
- [ ] Full HTTP+Kafka release integration via harness
- [ ] Three-node Master/Slave + failover timeline
- [x] Metrics/health on live node; prior obs stack
- [~] Admin reads PASS; create TW FAIL single-node
- [x] TGZ + image + kustomize verified locally
- [ ] K8s live deploy/Pod kill
- [x] `DEPLOY.md` + `USER_GUIDE.md` present and aligned to verified ops
- [x] Three reports present (this follow-along, not Runner-generated)
- [ ] Clean `master` + `SECOND PHASE COMPLETE`
