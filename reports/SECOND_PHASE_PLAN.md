# When 第二阶段 / 官方 Gate — PLAN（fork 落地）

> 范围：`173787247/when` 独立 fork。不向 `oryx-labs/when` 推送或提 PR。  
> 日期：2026-09-08  
> 对外验证总表：[`fork-verification-report.md`](fork-verification-report.md)

## 目标

对齐实施指引「第二次运行」与 `loop_runner` 的 `SECOND PHASE COMPLETE`，在 **不伪造官方 harness** 的前提下，把本机可验证项做完，官方树缺失项进本 PLAN。

## 本机已完成（可测则测）

| ID | 项 | 证据 |
|----|----|------|
| T1 | 单节点 Submit/Query/Cancel + FILE | `.loop/judge-results.txt` / e2e |
| T2 | Admin API + Vue 管理台 | lesson51 |
| T3 | Kafka 真投递 | lesson49_kafka_real_e2e |
| T4 | 观测栈 /health /ready /metrics | lesson50 |
| T5 | TGZ / Dockerfile / kustomize | lesson52（MSYS check-release 除外） |
| T6 | 手册与 reports | USER_GUIDE / DEPLOY / reports/* |
| T7 | 3 节点 kill Master → DELIVERED | `run-ha-3node-windows.ps1` |
| T8 | kill Controller → 重选 + 再投递 | 同上脚本扩展 |
| T9 | HA 稳定性复测 | `.loop/ha-3node-retest.log` |
| T10 | 批量消息到期前杀 Master | **PASS 100/100** [`evidence/ha-batch-summary.txt`](evidence/ha-batch-summary.txt) |
| T11 | HTTP Sink 本机 listener | **PASS** id `90450092263247872`（[`fork-verification-report.md`](fork-verification-report.md)） |
| T12 | Kafka Sink 单节点烟雾 | **PASS** id `90450158768128000` consume=`kafka-smoke-hello` |
| T13 | 三节点杀主 + HTTP/Kafka 投递 | **PASS** [`evidence/ha-3node-sinks-summary.txt`](evidence/ha-3node-sinks-summary.txt) |

## 官方缺失 / 环境限制 → PLAN（不做假 COMPLETE）

| ID | 项 | 原因 | 计划动作 | 依赖 | 优先级 |
|----|----|------|----------|------|--------|
| P1 | `harness/release/*.sh` | **上游 `main` 公开树也没有**；Runner 引用超前 | 上游发布后只读对照，再在 fork 移植或写 Windows 等价门；此前以本地烟雾+报告为准 | 上游补齐或课程下发 | P0 |
| P2 | `./loop run --phase second` | 缺 P1；`loop validate` 要求分支名 `master` | fork 可另开文档说明用 `main`；不改 upstream 规则 | P1 | P0 |
| P3 | 接管 ≤10s | 2026-09-08 再测 TTL=10/心跳 2s：入轮前已 INTERNAL_ERROR + recovering/out_of_sync，杀主后脚本挂死，**未观察到 ≤10s 接管**；默认仍 TTL=30 | 换本机/WSL 原生 etcd 后再测官方 6s/2s；本机默认保持 30s | 更干净 etcd | P1 |
| P4 | Controller 重选 ≤课程阈值 | 本机 ~28s | 同 P3 | P3 | P1 |
| P5 | 100 条批量 HA | **已关闭（T10 PASS）** | — | — | done |
| P6 | HA 剧本内嵌 HTTP+Kafka Sink | **已关闭（T13 PASS）** | — | — | done |
| P7 | K8s 三副本删 Pod | 本机无课内 K8s 集群 live | 有集群后再跑 kustomize 部署+删 Master Pod | 可用 K8s | P2 |
| P8 | `check-release.sh` Git Bash | **2026-09-08 复现 FAIL**：server tgz `tar -tz` 28 行 / unique 12（几乎每条重复）；`build-web.sh` 源码 hash 也已 stale | Linux/CI 再跑；本机不改官方脚本凑绿 | CI/Linux | P2 |
| P9 | OpenAPI Idempotency-Key（业务 Submit） | 规格缺口 | 单独开契约变更（人工 Review）后再实现 | 契约 Review | P2 |

## 执行原则

1. **能测尽测**：Windows 烟雾 + FILE 批量 + 已有 Kafka/制品证据。  
2. **缺则 PLAN**：不把 `SECOND PHASE COMPLETE` 写进 Runner 状态。  
3. **fork only**：所有提交只进 `173787247/when`。

## 完成定义（fork 落地）

- [x] T7–T9 HA 核心路径有可复现 PASS  
- [x] T10 批量 HA PASS（100/100 DELIVERED）  
- [x] T11–T12 HTTP/Kafka 单节点烟雾 PASS  
- [x] T13 三节点杀主后 HTTP+Kafka DELIVERED（P6）  
- [x] 缺口清单与本 PLAN 已入库  
- [ ] P1–P2 仍开放 → **不宣称官方第二阶段 COMPLETE**
