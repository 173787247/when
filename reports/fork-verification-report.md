# When fork 验证报告（`173787247/when`）

> 仓库：https://github.com/173787247/when  
> 日期：2026-09-08  
> 范围：本机可测项。不向 `oryx-labs/when` 推送或提 PR。  
> **不宣称**官方 `SECOND PHASE COMPLETE`（缺 `harness/release`、接管未达 ≤10s）。

对照：[`SECOND_PHASE_PLAN.md`](SECOND_PHASE_PLAN.md)、[`second-phase-gap.md`](second-phase-gap.md)、[`ha-3node-practical.md`](ha-3node-practical.md)。  
本机 JVM/脚本全文在 `.loop/`（gitignored）；下面把**可对外出示的结论和关键字段**写进本报告，并附 [`evidence/`](evidence/) 摘录。

## 环境

- Windows + Docker：`when-local-redis` / `when-local-etcd` / `when-local-kafka`
- JDK 21、`when-app/target/when-app-1.0.0-SNAPSHOT-runner.jar`
- 默认 HA：`WHEN_ETCD_LEASE_TTL_SECONDS=30`，心跳 5s，不开 OTEL
- 三节点：when-1 `:28080/:18081/:29090`，when-2 `+1`，when-3 `+2`

## 总表

| ID | 项 | 结果 | 可复现入口 / 证据 |
|----|----|------|-------------------|
| T1 | Submit / Query / Cancel + FILE | **PASS** | 单节点；示例 id `90119327742693376` |
| T2 | Admin API + Vue | **PASS** | lesson51；建轮需 ≥2 节点 |
| T3 / T12 | Kafka 真投递 | **PASS** | `run-kafka-sink-smoke-windows.ps1`；id `90450158768128000`；consume=`kafka-smoke-hello` |
| T4 | `/health` `/ready` `/metrics` | **PASS** | mgmt `:18081` |
| T5 | TGZ / Dockerfile / kustomize | **PARTIAL** | 制品可解压；`check-release` Git Bash FAIL（P8） |
| T7 | 3 节点杀 Master → DELIVERED | **PASS** | `run-ha-3node-windows.ps1`；接管 ~30–45s |
| T8 | 杀 Controller → 重选 + 再投递 | **PASS** | 同上；重选 ~28s |
| T9 | HA 稳定性复测 | **PASS** | TTL=30，仍 DELIVERED |
| T10 | 批量 100 条到期前杀 Master | **PASS 100/100** | [`evidence/ha-batch-summary.txt`](evidence/ha-batch-summary.txt) |
| T11 | HTTP Sink 本机 listener | **PASS** | `WHEN_HTTP_SINK_ALLOW_LOOPBACK=true`；id `90450092263247872` |
| T13 | 杀主后 HTTP+Kafka | **PASS** | HTTP `90451296410144768`；Kafka `90451296686968832`；接管 ~49s WARN |
| P3 | 接管 ≤10s（TTL=10） | **FAIL** | [`evidence/ha-3node-ttl10-summary.txt`](evidence/ha-3node-ttl10-summary.txt) |
| P7 | K8s 删 Pod | **未测** | 仅有 Docker kubectl，无活集群 |
| P8 | `check-release.sh` | **FAIL** | Git Bash：`tar -tz` 28 行 / unique 12 |

## 关键跑分摘录

### HTTP 单节点（2026-09-07）

- `message_id=90450092263247872` → `DELIVERED`，`sink_type=HTTP`，`retry_count=0`
- 脚本：`harness/local/run-http-sink-smoke-windows.ps1`

### Kafka 单节点（2026-09-07）

- `message_id=90450158768128000` → `DELIVERED`，`sink_type=KAFKA`
- console-consumer：`kafka-smoke-hello`
- 脚本：`harness/local/run-kafka-sink-smoke-windows.ps1`

### 批量 HA（2026-09-07 21:04）

- 提交 100 / 到期前杀 `when-1` / 新 Master `when-2`
- 终态：`delivered=100 failed=0 pending=0`
- 摘录：[`evidence/ha-batch-summary.txt`](evidence/ha-batch-summary.txt)

### 三节点杀主 + HTTP/Kafka（2026-09-07 22:20）

- Master `when-1` → 接管 `when-2`，约 **49.3s**（超过 10s 预算，记 WARN）
- HTTP `90451296410144768` DELIVERED；receiver 有 POST
- Kafka `90451296686968832` DELIVERED；consume=`ha-kafka-sink`
- 摘录：[`evidence/ha-3node-sinks-summary.txt`](evidence/ha-3node-sinks-summary.txt)

### TTL=10 复测（2026-09-08）

- 三节点 `/ready` 后，**未杀主**即出现 `INTERNAL_ERROR`，轮子 `recovering` / `out_of_sync`
- 杀 `when-2` 后剧本挂死，无 ≤10s 接管日志
- 本机默认仍用 TTL=30
- 摘录：[`evidence/ha-3node-ttl10-summary.txt`](evidence/ha-3node-ttl10-summary.txt)

## 明确不做 / 未闭合

1. 官方 `harness/release/*` 与 `./loop run --phase second`（上游公开树也没有该目录）。
2. 接管 / Controller 重选未达课程秒级预算（受 Docker etcd + TTL=30 约束）。
3. K8s 三副本 live 删 Pod。
4. 业务 Submit 的 OpenAPI `Idempotency-Key`。

## 复跑命令

```powershell
# 单节点 HTTP / Kafka
.\harness\local\run-http-sink-smoke-windows.ps1
.\harness\local\run-kafka-sink-smoke-windows.ps1

# 三节点 FILE + 杀 Master / Controller
.\harness\local\run-ha-3node-windows.ps1

# 三节点杀主 + HTTP/Kafka
.\harness\local\run-ha-3node-http-kafka-windows.ps1

# 批量 100
.\harness\local\run-ha-batch-failover-windows.ps1
```
