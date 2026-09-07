# 第二阶段缺口（fork 落地对照）

对照上游 [AI 编程实施指引](https://github.com/oryx-labs/when/blob/main/docs/When%20%E9%A1%B9%E7%9B%AE%20AI%20%E7%BC%96%E7%A8%8B%E5%AE%9E%E6%96%BD%E6%8C%87%E5%BC%95.md) 第 9.2 / 阶段 C，以及 `./loop run --phase second` 期望。

日期：2026-09-07 · 仓库：`173787247/when` only（不动 `oryx-labs/when`）

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

## 仍缺（不能宣称 SECOND PHASE COMPLETE）

| 项 | 缺口 |
|----|------|
| `harness/release/*` | **上游 `oryx-labs/when@main` 也没有该目录**；`loop_runner` 仍引用 `run-ha-failover.sh` 等，属文档/Runner 超前于公开树 |
| `./loop run --phase second` | 未跑；`loop validate` 要 `master` 分支名 |
| 接管 ≤10s | 本机 ~30–45s（lease TTL=30）；TTL=10 接管 miss |
| 杀 Controller 再决策 | **PASS**（脚本扩展：重选 ~28s + 再投递 DELIVERED；软预算 15s 未达标） |
| 100 条消息到期前杀 Master | 未做批量故障演练 |
| HTTP+Kafka 真投递纳入同一 HA 剧本 | Kafka E2E 有过，未并入 HA smoke |
| K8s 三副本删 Pod | 未 live |

## 建议下一刀

1. 跑扩展后的 `run-ha-3node-windows.ps1`（Master + Controller）。  
2. 检测时延：更干净 etcd 或可控 TTL，逼近 10s。  
3. 不幻想从上游 `main`「同步 release」——公开树暂无；fork 继续用 Windows 烟雾 + 报告收口。
