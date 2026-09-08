# When 项目总结与后续规划（第 54 节跟课）

日期：2026-09-07  
范围：第 39—53 跟课交付物回顾（对照现码 + 本机证据，非官方 `SECOND PHASE COMPLETE`）  
备份：`github.com/173787247/when` `main` @ HA PR #2/#3；工作分支 `backup/lesson53-followalong`

## 1. When 实际具备的能力

| 能力 | 落地位置 | 跟课结论 |
|------|----------|----------|
| 领域模型与契约 | `when-common`、OpenAPI/gRPC | 第一阶段 PASS |
| Submit/Query/Cancel | HTTP + gRPC | 单节点实探 PASS（FILE） |
| Redis 消息事实源 | `when-storage-redis` | 模块 PASS；管理索引 `zadd` 契约误报记 FAIL |
| ETCD 元数据 / 选举 | `when-cluster` | PASS |
| 时间轮调度 | `when-timewheel` | PASS |
| 接入与跨节点路由 | `when-ingress-router` / `when-app` | FirstStageAcceptance PASS |
| Master/Slave 副本 | `when-cluster` replica | 模块测 PASS；三节点 kill Master 后 **DELIVERED PASS**（接管 ~30s） |
| Controller / rebalance | `when-cluster` controller | 模块测 PASS；选主接线 + assignment-watch promote 已落地 |
| HTTP / Kafka / FILE Sink | `when-sink-*` / `when-delivery` | 模块 + FILE/Kafka/HTTP 单节点烟雾 PASS |
| 可观测 | `/health` `/ready` `/metrics` + OTLP | 本机 Prometheus/Grafana 栈 PASS |
| 管理台 | `when-admin-api` + Vue | GET/构建 PASS；建轮需多节点 |
| 制品 | TGZ / OCI / K8s base | 第 52 基本 PASS（MSYS tar 检查例外） |
| 手册 | `DEPLOY.md`、`USER_GUIDE.md` | 第 53 交付 PASS |

边界（课程明确不做）：更多 Sink、鉴权限流、死信、优先级、跨地域、Operator、MCP。

## 2. 两次 Loop 运行（本机实际）

| 阶段 | 官方路径 | 跟课实际 |
|------|----------|----------|
| First（39—45） | `./loop run --phase first` → `FIRST PHASE COMPLETE` | 未依赖全量 Runner；契约 + acceptance **等价 PASS** |
| Second（47—52→53） | `./loop run --phase second` → `SECOND PHASE COMPLETE` | **未跑** Runner；逐节对照 + 实探；**未**输出 SECOND PHASE COMPLETE |

原因（客观）：缺 `harness/release/*`、默认分支 `main`≠`master`；本机 Windows HA 烟雾已 PASS，但未跑官方 Runner，不伪造 `SECOND PHASE COMPLETE`。

## 3. 全流程测试证据索引

对外请先看 **[`reports/fork-verification-report.md`](fork-verification-report.md)**（含 message_id 与 `reports/evidence/` 摘录）。本机 `.loop/` 仅作复盘，不进 git。

- 第一阶段：`reports/first-stage-e2e/`
- HTTP / Kafka 单节点：`reports/evidence/http-kafka-smoke-excerpt.txt`
- 批量 HA 100/100：`reports/evidence/ha-batch-summary.txt`
- 杀主 + HTTP/Kafka：`reports/evidence/ha-3node-sinks-summary.txt`
- TTL=10 失败：`reports/evidence/ha-3node-ttl10-summary.txt`
- HA 叙事：`reports/ha-3node-practical.md`
- 观测 / 管理台 / 打包：lesson50–52 + `reports/release-report.md`

## 4. 发布制品

| 形态 | 标识 | 状态 |
|------|------|------|
| Server TGZ | `when-server-1.0.0-lesson52.tgz` | 本机构建可解压 |
| Admin-web TGZ | `when-admin-web-1.0.0-lesson52.tgz` | 本机构建 |
| OCI | `whenproject/when:1.0.0-lesson52`（UID 10001） | 官方 Dockerfile PASS |
| K8s | `deploy/k8s/base` kustomize | 校验 PASS；未做集群 live 部署 |

外部依赖一律外置（Redis/ETCD/Kafka/观测），符合第 52 节边界。

## 5. Harness / AI 编程方法（真正带走的）

1. **公共契约先于编码**：OpenAPI/proto/领域 SPI 约束模块边界。  
2. **可执行任务切片**：每节独立分支意图、独立 judge（本机用 checklist 代替 Runner 状态机）。  
3. **自动验收 + 人的检查点**：模块测 / 契约 / 实探分层；失败保留证据，不改 protected 阈值过关。  
4. **Composition Root**：`when-app` 组装；第一阶段 acceptance 验证「拼得起来」。  
5. **制品与手册同源**：能跑通的命令才写进 `DEPLOY.md` / `USER_GUIDE.md`。  
6. **环境诚实**：Windows CRLF、代理、端口占用、缺 harness 都记为客观限制，不假装 COMPLETE。

## 6. 未闭合缺口（诚实清单）

1. `harness/release/` 缺失 → 官方发布候选/最终 gate 无法跑。  
2. 官方 6s/2s + 流式 keepAlive：杀 Master 接管 **4.38s**、Controller 重选 **4.32s**（[`evidence/ha-3node-keepalive-ttl6-summary.txt`](evidence/ha-3node-keepalive-ttl6-summary.txt)）。旧 TTL=30 / 周期心跳路径仍约 30–49s。  
3. 单节点 `POST /admin/v1/timewheels` → 503（需 ≥2 节点）。  
4. 业务 Submit 无 OpenAPI 级 Idempotency-Key。  
5. K8s 三副本 live + 删 Pod 未做。  
6. `check-release.sh` 在 MSYS tar 上误报重复条目。  
7. 不向 `oryx-labs/when` 推送或提 PR（fork 独立落地）。

详见 [`reports/second-phase-gap.md`](second-phase-gap.md)。

## 7. 后续路线建议

| 优先级 | 方向 | 说明 |
|--------|------|------|
| P0 | 补齐或移植 `harness/release` | 才能诚实宣称第二阶段完成 |
| P0 | 分支命名与 Runner（`master` vs `main`）对齐 | 否则 `loop validate` 不过 |
| P1 | 业务幂等与 OpenAPI 对齐 | 避免调用方误用 |
| P2 | K8s live + 社区演进项 | 鉴权、死信、更多 Sink 等 |

## 8. 版本备份

独立 fork（不影响老师 upstream）：

- https://github.com/173787247/when （`main` 已含 HA 修复）
- 工作分支：`backup/lesson53-followalong`

## 9. 一句话收口

When 在本机已证明「延时投递主路径 + 官方 6s/2s 三节点 kill Master/Controller 后投递恢复 + 制品/手册」可跑通；官方 `SECOND PHASE COMPLETE` 仍差 `harness/release`，按工程缺口继续，不口头完成。
