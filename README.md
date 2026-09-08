# When

> **本仓库说明（独立 fork）**  
> 这是 [oryx-labs/when](https://github.com/oryx-labs/when) 的独立 fork，用于 When 延时投递的实际落地实践：本机/Windows 联调与 HA 烟雾、打包发布修复、部署与运维手册，以及运行证据备份。  
> **不代表**上游官方发布。仓库中的 When 业务代码仍遵循上游 **Apache License 2.0**（见根目录 [`LICENSE`](LICENSE)）；本仓库增量修改、报告与辅助脚本同样按 Apache-2.0 分发。  
> 验证报告：[`reports/fork-verification-report.md`](reports/fork-verification-report.md)。缺口与总结：[`reports/project-summary.md`](reports/project-summary.md)、[`reports/ha-3node-practical.md`](reports/ha-3node-practical.md)。默认分支：`main`。

## 本 fork 落地状态（2026-09-08）

本机可测项以验证报告为准。**不宣称**官方 `SECOND PHASE COMPLETE`（上游公开树仍无 `harness/release/*`）。

| 项 | 本机结果 |
| --- | --- |
| 官方 6s/2s 三节点杀 Master | 接管 **4.38s**，FILE `90633011313250304` DELIVERED |
| 杀 Controller 后重选 | **4.32s**，FILE `90633096361156608` DELIVERED |
| etcd 续约 | 流式 `lease.keepAlive`；watch 回调离开 jetcd Vert.x 循环 |
| HTTP / Kafka Sink | 单节点与杀主后投递 PASS |
| 批量 HA 100 | **100/100** DELIVERED |
| 官方 `./loop run --phase second` | 未跑；缺 release harness |

证据：[`reports/evidence/ha-3node-keepalive-ttl6-summary.txt`](reports/evidence/ha-3node-keepalive-ttl6-summary.txt)。

Windows 复跑官方 6s/2s（原生 etcd 3.5.16 + Docker Redis）：

```powershell
$env:WHEN_HA_LEASE_TTL_SECONDS = "6"
$env:WHEN_HA_HEARTBEAT_MS = "2000"
.\harness\local\run-ha-3node-windows.ps1
```

When 是一个面向企业内部、可私有部署的分布式延时投递组件。业务方提交带有到期时间和目标 Sink 的消息；系统可靠保存消息，在到期时触发投递。它解决“未来某个时刻可靠交付数据”，不执行任意业务代码，也不是消息队列、Cron 平台或工作流引擎。

项目同时是 Loop Engineering 的训练样本：需求、技术方案、公共契约、Harness、自动验收和运行证据都与实现一同维护。

## 定位与边界

When 的目标规格提供跨语言 HTTP 接入、HTTP/Kafka Sink、Redis 消息事实、ETCD 集群元数据、多层时间轮、Master/Slave、Controller、至少一次语义及可观测性。

它不承诺精确一次投递；下游必须使用 `message_id` 做幂等去重。它不承担任意代码执行、DAG、工作流、消息消费组、完整 MQ 能力或 Redis/ETCD/Kafka 自身的高可用。

更多背景与产品边界见：[业界调研](docs/项目篇%20When：业界调研.md)、[项目需求](docs/When%20项目需求文档.md)。

## 当前实现状态

下面这张表是上游首阶段（第 39—45 节）README 原文口径，用来区分「当时已验证」和「当时尚未交付」。**本 fork 已继续做到第 47—53 节可测项**：HTTP/Kafka Sink、三节点 HA、管理台与制品均有本机证据，见上一节与验证报告。官方第二阶段 COMPLETE 仍未宣称。

| 已实现并有测试/运行证据 | 尚未作为本阶段交付验证 |
| --- | --- |
| 公共领域模型、状态机、SPI 与 proto 契约 | 对外 HTTP 门面、真实 HTTP/Kafka Sink 与投递重试 |
| gRPC `Submit`、`Query`、`Cancel`，以及请求校验 | `DELIVERED` 真实下游投递结果、死信和完整投递历史 |
| Redis 消息持久化、原子状态转换与恢复索引 | Master/Slave 副本同步、动态故障接管和 Rebalance |
| ETCD 节点/worker Lease 注册、成员视图与 Controller 基础 | 三进程生产集群、完整 HA 故障演练 |
| Netty 时间轮、取消与 Redis 重建 | Prometheus、健康/就绪 HTTP 端点、管理 API 与 Web 管理台 |
| 独立 `when-app` JVM、单机 `tw-0`、外部 TCP gRPC E2E 客户端 | Docker Compose、Kubernetes 与生产部署制品 |

本阶段的到期处理器只记录 `event=when_due` 日志：它将消息从 `PENDING` 原子 claim 为 `DELIVERING`，不调用 HTTP/Kafka，也不能据此宣称消息已投递或已进入 `DELIVERED`。

最近一次单机核心闭环的范围、命令与证据见：[第 39—45 节核心流程测试报告](docs/When%20第39—45节核心流程测试报告.md)。

## 架构

目标架构中，Redis 是消息事实的权威来源，ETCD 是节点、Controller 和时间轮分布的权威来源；内存时间轮只是可由 Redis 重建的调度索引。

```text
客户端
  -> HTTP（目标规格）/ gRPC（当前首阶段）
  -> 校验、发号、路由
  -> Redis：消息事实与状态
  -> 本地时间轮：到期触发
  -> DueMessageHandler
  -> HTTP 或 Kafka Sink（目标规格；当前阶段仅写日志）

ETCD：节点 Lease、worker ID、Controller、时间轮元数据
```

状态机严格限制为：

```text
PENDING -> DELIVERING -> DELIVERED
PENDING -> CANCELLED
DELIVERING -> PENDING   # 可重试
DELIVERING -> FAILED
```

消息先成功写入 Redis，才能进入时间轮并返回成功；内存时间轮、ETCD 和 Sink 都不能替代这条事实边界。完整设计见：[技术方案](docs/When%20项目技术方案文档.md)。

## 模块

| 模块 | 当前职责 |
| --- | --- |
| `when-common` | `Message`、状态、Sink 配置、公共 SPI 与 proto 契约 |
| `when-api` | gRPC 协议、服务端薄层、请求校验与应用 DTO |
| `when-storage-redis` | Redis 消息事实、调度索引、原子状态转换 |
| `when-cluster` | ETCD Key、Lease、成员、Controller 与集群视图基础 |
| `when-timewheel` | Netty 时间轮、取消、隔离和重建 |
| `when-ingress-router` | 发号、路由、先持久化后调度与 gRPC 转发 |
| `when-app` | Composition Root；单机 Server 与生命周期管理 |
| `when-e2e-test` | 独立 TCP gRPC 客户端：health、submit、query、cancel |
| `when-test-support` / `when-acceptance` | 测试替身、课程验收和黑盒验证 |

依赖方向、字段语义和 Redis/ETCD Key 约定以技术方案和课程公共契约为准；业务模块不得绕过 SPI 直接耦合插件实现。

## 环境要求

- JDK 17；
- 本仓库的 Maven Wrapper（`./mvnw`）；
- 本地 `redis-server`、`redis-cli`；
- 本地 `etcd`、`etcdctl`；
- `curl`、`python3`、`perl`；
- macOS 上执行分支发布脚本还需要 `bsdtar`。

首阶段集成测试不使用 Docker、Docker Compose 或 Testcontainers。`harness/local/` 使用隔离端口和临时数据目录启动 Redis、ETCD，结束后必须停止。

## 构建与自动验收

在仓库根目录执行：

```bash
./mvnw -q verify
```

`./mvnw -q verify` 运行 Maven 模块测试。课程阶段契约与首次验收需要先启动本地依赖并导出 Harness 环境：

```bash
harness/local/start-deps.sh
harness/local/wait-deps.sh --timeout 30
source .loop/runtime.env
./mvnw -q verify
harness/contracts/acceptance.sh
harness/local/stop-deps.sh
```

如果中途失败，也应执行 `harness/local/stop-deps.sh` 清理临时进程和数据目录。

## 单机核心 E2E

该流程验证当前阶段的真实闭环：独立 Server 连接 ETCD、通过外部 gRPC 客户端提交消息、Redis 观察 `PENDING`、时间轮到期日志、取消，以及重启后的待处理消息重建。

```bash
harness/local/start-deps.sh
harness/local/wait-deps.sh --timeout 30

harness/core-tests/start-single-node.sh
trap 'harness/core-tests/stop-single-node.sh; harness/local/stop-deps.sh' EXIT

harness/core-tests/run-single-node-flow.sh
```

成功时脚本输出 `E2E_SINGLE_NODE=PASS`，证据保存在 `.loop/core-flow.*`。完整断言、时限和边界见：[核心流程测试方案](docs/When%20核心流程测试方案与报告模板.md)。

## Loop Runner

`loop` 是 Python Runner 的薄 Bash 入口；它管理课程依赖、阶段状态、锁、失败重试、阶段指纹和报告。第 46 节只负责创建 Runner，正式业务阶段为 `lesson39` 到 `lesson45`。

```bash
./loop validate
./loop plan
./loop run
```

Runner 配置在 `loop.yaml`，实现位于 `harness/loop/`。Loop 规则、课程阶段和恢复行为见：[第 46 节整合运行课](docs/第46节：Loop%20运行演示课（整合运行）.md)。

## 文档索引

- [项目篇 When：业界调研](docs/项目篇%20When：业界调研.md)：问题空间、替代方案和产品定位；
- [When 项目需求文档](docs/When%20项目需求文档.md)：范围、用户故事、非功能要求与最终验收；
- [When 项目技术方案文档](docs/When%20项目技术方案文档.md)：架构、状态机、存储、集群、部署与测试设计；
- [When 项目 AI 编程实施指引](docs/When%20项目%20AI%20编程实施指引.md)：SDD、Harness、Loop 与阶段化实施方式；
- [第 39—45 节核心流程测试报告](docs/When%20第39—45节核心流程测试报告.md)：本阶段实际运行证据与客观风险；
- [课程导读](docs/课程导读-When（第六到八周·第33-54节）.md)：第 33—54 节的路线图。

## 全分支远端同步与人工验证

`publish-all-branches.sh` 用于本机无法直接 push 时，通过远端 `/root/when` 中转发布所有本地同名分支。它逐个切换本地分支，将仓库目录全量打包（排除 `.git` 和所有 `target/`）、上传、远端解压，然后由远端 `git add -A`、commit、push。

先确认脚本语法、当前工作区与远端连接：

```bash
bash -n publish-all-branches.sh
git status --short
./publish-all-branches.sh
ssh -o BatchMode=yes -o ConnectTimeout=10 root@117.72.92.117 \
  'git -C /root/when status --short; git -C /root/when branch --show-current'
```

发布前必须提交或暂存本地改动，因为脚本会切换分支：

```bash
git add README.md publish-all-branches.sh
git commit -m "chore: document branch publication workflow"
./publish-all-branches.sh --execute
```

逐分支验证本地与远端的受跟踪内容是否一致。远端同步提交的提交 ID 可以不同；相同 tree ID 才表示内容一致：

```bash
failed=0
while IFS= read -r branch; do
  local_tree="$(git rev-parse "refs/heads/${branch}^{tree}")"
  remote_tree="$(ssh -o BatchMode=yes -o ConnectTimeout=10 root@117.72.92.117 \
    "git -C /root/when rev-parse 'refs/heads/${branch}^{tree}'")"
  if [ "$local_tree" = "$remote_tree" ]; then
    printf 'PASS  %s  %s\n' "$branch" "$local_tree"
  else
    printf 'FAIL  %s  local=%s remote=%s\n' "$branch" "$local_tree" "$remote_tree" >&2
    failed=1
  fi
done < <(git for-each-ref --format='%(refname:short)' refs/heads | sort)
[ "$failed" -eq 0 ]
```

该同步脚本是“全量覆盖解压 + `git add -A`”，不根据本地删除记录删除远端已有文件。因此本地删除文件不在其同步保证范围内。

## 安全与工程约束

- 不记录或暴露 payload、密码、Token、完整 Sink 配置；
- Redis 保存消息事实，ETCD 不保存 payload，内存时间轮不作为事实来源；
- 调度线程不得执行同步 Redis、网络或 Sink I/O；
- `message_id`、`trace_id`、URL、topic 和异常文本不能作为指标标签；
- 当前管理能力无鉴权时，只能部署在可信内网；
- 自动验收、课程原料、公共契约与 Harness 不应为通过检查而被削弱或修改。

## 贡献与演进

新增能力先补充 delta spec 和自动验收，再实现与 Review。公共模型、协议、状态机、存储 Key 或故障语义发生变化时，应先更新基准文档并完成架构 Review；不要让模块各自发明兼容性约定。

完整 HA、真实 Sink、可观测性、管理台和生产部署属于第 47—53 节及之后的演进阶段，详见 [AI 编程实施指引](docs/When%20项目%20AI%20编程实施指引.md)。本 fork 的本机落地进度见文首「本 fork 落地状态」。
