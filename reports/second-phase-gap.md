# 第二阶段缺口（fork 落地对照）

对照上游 [AI 编程实施指引](https://github.com/oryx-labs/when/blob/main/docs/When%20%E9%A1%B9%E7%9B%AE%20AI%20%E7%BC%96%E7%A8%8B%E5%AE%9E%E6%96%BD%E6%8C%87%E5%BC%95.md) 第 9.2 / 阶段 C，以及 `./loop run --phase second` 期望。

日期：2026-09-08 · 仓库：`173787247/when` only（不动 `oryx-labs/when`）  
对外总表：[`fork-verification-report.md`](fork-verification-report.md)

## 已落地（本机证据）

| 项 | 状态 |
|----|------|
| 第 47–52 模块/制品对照 | 大体 PASS（见 `.loop/judge-results.txt`） |
| 单节点 Submit/Query/Cancel + FILE | PASS |
| Admin GET + Vue 管理台 | PASS |
| TGZ / Dockerfile / kustomize | 基本 PASS |
| `USER_GUIDE` / `DEPLOY` / reports | PASS |
| 3-node join + kill Master + **DELIVERED** | PASS（`reports/ha-3node-practical.md`） |
| HA 稳定性复测（TTL=30） | PASS（接管偏慢，仍 DELIVERED） |
| kill Controller → 重选 + 再投递 | PASS |
| 批量 100 条到期前杀 Master | **PASS 100/100**（[`evidence/ha-batch-summary.txt`](evidence/ha-batch-summary.txt)） |
| HTTP Sink 单节点烟雾 | **PASS**（id `90450092263247872`） |
| Kafka Sink 单节点烟雾 | **PASS**（id `90450158768128000`） |
| 三节点杀主 + HTTP/Kafka | **PASS**（[`evidence/ha-3node-sinks-summary.txt`](evidence/ha-3node-sinks-summary.txt)） |

## 仍缺（不能宣称 SECOND PHASE COMPLETE）

| 项 | 缺口 |
|----|------|
| `harness/release/*` | **上游 `oryx-labs/when@main` 也没有该目录**；`loop_runner` 仍引用 `run-ha-failover.sh` 等，属文档/Runner 超前于公开树 |
| `./loop run --phase second` | 未跑；`loop validate` 要 `master` 分支名 |
| 接管 ≤10s | TTL=30 约 30–49s；原生 etcd 3.5.16 直连 + 官方 6s/2s **仍失败**（[`evidence/ha-3node-native-etcd-ttl6-summary.txt`](evidence/ha-3node-native-etcd-ttl6-summary.txt)） |
| 杀 Controller 再决策 | **PASS**（重选 ~28s + 再投递 DELIVERED） |
| 100 条消息到期前杀 Master | **PASS 100/100** |
| HTTP+Kafka 并入三节点 HA 剧本 | **PASS**（P6/T13） |
| K8s 三副本删 Pod | 本机仅有 Docker 自带 kubectl，**无可用集群**（`cluster-info` NotFound） |
| `check-release.sh` | Git Bash **FAIL**（tgz 列表重复条目）；`build-web.sh` SOURCE_SHA256 与 `when-admin-web` 不一致 |

## 建议下一刀

1. P3 本机 Docker etcd 已复测：TTL=10 不稳定，继续默认 30s。  
2. 不幻想从上游 `main`「同步 release」。  
3. 有 Linux/CI 再跑 check-release；有 K8s 再做删 Pod。
