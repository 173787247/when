# 第 52 节：部署与 CI——docker-compose、Kubernetes（Loop 原料）

> 本节提供本地开发、单节点和 Kubernetes 三种部署方式，以及持续集成（CI）流水线。第 53 节将直接使用这些制品部署测试集群。
>
> 十一段模板，引用第 50（健康检查）+ 各模块的环境变量配置。

---

## 1. 这一节做什么

一句话：**提供本地开发、单节点部署和 Kubernetes 集群部署，并用 CI 执行不可由 Loop 修改的验收。**

三种形态：本地开发使用 docker-compose 启动依赖和多节点 When；单节点部署使用 jar 或 Docker 运行一个 When 节点，适合功能验证和低可用场景；生产集群使用 Kubernetes。配置通过环境变量提供。CI 负责构建、测试、安全检查和合并门禁。

---

## 2. 为什么这么设计

**为什么三种形态。**

![图 1　三种部署形态：本地开发 / 单机生产 / Kubernetes](第52节-图1-三形态部署.svg)

需求要求三种：本地开发环境用于学习和演示；单节点部署用于最小化验证，不提供节点级高可用；Kubernetes 用于多节点生产集群。When 节点在 Kubernetes 中使用 StatefulSet，Pod 名可以稳定地作为 `node_id`，也便于逐个升级。方案还包括 Service、ConfigMap、Secret、startup/liveness/readiness 探针和 PDB。PDB（PodDisruptionBudget）用于限制计划内维护时可同时停止的 Pod 数量（技术方案 §14）。

**为什么配置使用环境变量。** 按 12-factor 应用原则，程序配置与构建制品分离。`WHEN_NODE_ID`、`WHEN_GRPC_PORT`、`WHEN_ETCD_ENDPOINTS`、`WHEN_REDIS_*`、`WHEN_TIMEWHEEL_COUNT` 等配置在不同环境中通过环境变量注入，敏感值由 Secret 提供（技术方案 §14.4）。

**为什么 CI 是"外部自动化验收"。**

![图 2　CI 流水线：Loop 无法修改的外部自动化验收](第52节-图2-CI流水线.svg)

Loop 本地执行快速测试；杀节点、网络分区等慢速测试由 CI 执行。第 52 阶段需要创建 CI 文件，因此本阶段允许修改 `.github/workflows/ci.yml`，但不能修改第 0 步生成的外部验收脚本。第 52 阶段通过后，Runner 保存 CI 文件指纹；第 53 节运行时 CI 文件转为受保护路径。构建、单元测试、集成测试、故障测试、安全检查和 lint 全部通过后才能合并。

---

## 3. 它和 Loop 的关系

本节是提供给 Loop 的模块任务规格：Loop 产出 compose/yaml/CI 配置 + 部署文档。

- **明确输入**：三形态的组成、环境变量、CI 阶段（第 5、6、7 节）。
- **自动化验收**：第 8 节验证部署命令、健康探针和 CI 合并门禁。
- **边界**：第 9 节——不做 Operator（社区共建）、不管 ETCD/Redis 自身 HA。

---

## 4. 依赖与被依赖

**本节依赖（上游）**

- 第 50 节：`/health`、`/ready`（K8s 探针）、`/metrics`（Prometheus 抓取）。
- 各模块：环境变量配置项。
- 第 47/48 节：停止节点、网络分区等故障测试（CI 里跑）。

**本节产出、被谁消费（下游）**

| 本节产出 | 被哪节消费 | 怎么用 |
|---|---|---|
| docker-compose | 第 53 联调（自动部署集群） | 一条命令起完整集群 |
| K8s yaml | 生产 / 第 53 演示 | replicas=3 + 探针 + PDB |
| CI 流水线 | 全流程 | 每次 PR 的外部自动化验收 |
| 部署文档 | 使用方 / 学员 | 按文档可在半小时内启动 |

---

## 5. 功能与交互

**本地（docker-compose）**：一个 `docker-compose.yml` 起 ETCD、Redis、Kafka、HTTP Mock 和 when-1/2/3。三个节点使用固定且不同的 `WHEN_NODE_ID` 与 `WHEN_WORKER_ID`。所有镜像固定版本，不使用浮动的 `latest`。依赖服务和 When 都配置 healthcheck；When 等依赖健康后再启动，并由自身重连处理运行中的短暂断开。演示和第 53 节验收都使用这套环境。

**单节点低可用部署**：使用同一个 When 镜像，通过环境变量连接已有的 ETCD、Redis 和 Kafka。可以用 `java -jar`、Docker 或 systemd 启动。把所有依赖也放在一台机器上只适合本地演示，不作为生产建议。该形态没有节点级容灾能力，部署文档必须在命令前明确这一限制。

**多节点生产部署**：ETCD 集群 + Redis 主从或托管 Redis + Kafka 集群 + 至少三台 When 节点，并在前面配置负载均衡。各组件应分散到不同故障域，避免单台机器故障导致整个系统不可用。

**Kubernetes**：StatefulSet 默认 3 个副本，Pod 名作为 `WHEN_NODE_ID`；Pod 名末尾的固定序号加一个基数，得到 0—1023 范围内的 `WHEN_WORKER_ID`。Headless Service 为每个 Pod 提供稳定地址，普通 Service 暴露业务 API，管理端口只在集群内部访问。ConfigMap 放非敏感配置，Secret 只引用已有 secret key，不在仓库保存真实值。PDB 设置 `minAvailable: 2`，再用反亲和或拓扑分布规则尽量把 Pod 分散到不同机器。

**启动与退出**：startupProbe 允许首次恢复花较长时间；启动完成后才启用 liveness/readiness。收到 SIGTERM 时先让 `/ready` 失败并停止接收新请求，再停止本地调度和续约，等待正在进行的投递结束，最长 30 秒后退出。StatefulSet 使用逐个滚动升级，前一个 Pod 恢复就绪后才更新下一个。升级前后的消息状态和时间轮分配必须可恢复。

**资源与权限**：容器使用非 root 用户、只读根文件系统和最小 Linux capabilities；临时目录单独挂载。配置 CPU/memory requests 与 limits，并让 JVM 最大堆根据容器内存设置。镜像使用不可变版本或 digest，运行时不下载依赖。

**CI**：提 PR 后依次执行格式与静态检查、构建和单元测试、Testcontainers 集成测试、compose 冒烟、kind 部署和故障测试、依赖与镜像安全扫描。每个 Job 有超时，失败时上传测试报告和容器日志，最后无论成功失败都清理 compose/kind 资源。来自外部仓库的 PR 使用只读权限，不向测试步骤暴露生产 Secret。

**分支保护**：CI 文件只能让 Job 失败，不能自己配置代码仓库的合并规则。`DEPLOY.md` 需要列出应设为 required 的 status check，仓库管理员在平台设置中完成一次配置。

**本地运行命令**：文档和 CI 使用同一组脚本，避免课堂上手工执行一套、CI 再维护另一套。

```bash
docker compose -p when-dev -f deploy/compose/docker-compose.yml up -d --build
./deploy/scripts/wait-ready.sh --mode compose --timeout 180
./deploy/scripts/smoke.sh --base-url http://127.0.0.1:8080
docker compose -p when-dev -f deploy/compose/docker-compose.yml logs --no-color
docker compose -p when-dev -f deploy/compose/docker-compose.yml down -v
```

Kubernetes 开发环境使用固定名称，脚本只能操作该名称：

```bash
kind create cluster --name when-dev
kubectl apply -k deploy/k8s/overlays/dev
kubectl rollout status statefulset/when --timeout=300s
./deploy/scripts/smoke.sh --mode kubernetes
./deploy/scripts/cleanup.sh --kind-cluster when-dev
```

`wait-ready.sh` 超时后必须打印 compose 状态或 Pod events；`cleanup.sh` 在资源不存在时也能安全退出。CI 的清理步骤使用 `if: always()`，测试失败也要运行。

---

## 6. 接口 / 契约设计（配置 + 制品）

核心环境变量（各形态使用同一套名字）：

| 变量 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `WHEN_NODE_ID` | 是 | — | 集群内唯一的稳定节点名；K8s 使用 Pod 名 |
| `WHEN_WORKER_ID` | 是 | — | Snowflake 数字节点位，范围 0—1023，存活节点之间不得重复 |
| `WHEN_GRPC_PORT` | 否 | `9090` | 节点间 gRPC 端口 |
| `WHEN_HTTP_PORT` | 否 | `8080` | 业务 HTTP 端口 |
| `WHEN_MANAGEMENT_PORT` | 否 | `8081` | admin、metrics 和健康检查端口，只向管理网络开放 |
| `WHEN_ETCD_ENDPOINTS` | 是 | — | ETCD 地址列表 |
| `WHEN_ETCD_USERNAME/PASSWORD` | 按环境 | — | ETCD 凭据，只能来自 Secret |
| `WHEN_REDIS_HOST/PORT` | 是 | — | Redis 地址 |
| `WHEN_REDIS_PASSWORD` | 按环境 | — | Redis 密码，只能来自 Secret |
| `WHEN_KAFKA_BOOTSTRAP_SERVERS` | 使用 Kafka Sink 时 | — | Kafka 地址列表 |
| `WHEN_TIMEWHEEL_COUNT` | 否 | `4` | 集群初始化的时间轮数量，不表示每个节点固定拥有 4 个 |
| `WHEN_LOG_LEVEL` | 否 | `INFO` | 根日志级别 |
| `WHEN_LOG_FORMAT` | 否 | `json` | `json` 或 `plain` |
| `WHEN_SHUTDOWN_TIMEOUT` | 否 | `30s` | 优雅退出最长等待时间 |

Kubernetes 入口脚本从 StatefulSet Pod 名末尾的序号计算 `WHEN_WORKER_ID`，并在启动前通过 ETCD 租约登记。ID 已被其他存活节点占用时启动失败，不能继续生成可能重复的消息 ID。compose 和单节点部署直接填写不同数字。

产出制品：

```text
Dockerfile
deploy/compose/docker-compose.yml
deploy/compose/.env.example
deploy/k8s/base/*.yaml
deploy/k8s/overlays/dev/*.yaml
deploy/scripts/smoke.sh
deploy/scripts/wait-ready.sh
deploy/scripts/cleanup.sh
.github/workflows/ci.yml
DEPLOY.md
```

`.env.example` 和 Secret 模板只放变量名与假值，不放任何可用凭据。所有脚本需要 `set -euo pipefail`、明确超时，并且只清理自己创建的 compose project 或 kind cluster。

---

## 7. 字段 / 阶段定义

**CI 阶段**：

| Job | 主要命令 | 超时 | 失败时保留 |
|---|---|---:|---|
| `lint` | 格式、静态检查、配置文件校验 | 10 分钟 | 检查报告 |
| `unit` | `./mvnw -B -ntp verify` 中的单元测试 | 15 分钟 | Surefire 报告 |
| `integration` | Testcontainers Redis/ETCD/Kafka | 25 分钟 | Failsafe 报告、容器日志 |
| `compose-smoke` | 构建镜像、起三节点、执行 smoke | 20 分钟 | compose ps/logs |
| `kind-ha` | kind 部署、停止 Master、检查 10 秒切换 | 30 分钟 | Pod events/logs、验收报告 |
| `security` | 依赖、Secret、镜像漏洞扫描 | 15 分钟 | 扫描报告与 SBOM |

各 Job 使用最小权限；默认 `contents: read`，不使用 `pull_request_target` 执行外部 PR 代码。Maven、Node 和镜像缓存 key 必须包含锁文件或依赖描述文件哈希，不能跨不兼容版本复用。

**探针**：startup=`/ready`（允许较高 failureThreshold）；liveness=`/health`；readiness=`/ready`。管理端口和业务端口分开。

**密钥**：`WHEN_REDIS_PASSWORD` 等经 K8s Secret 或环境注入，**不写进 yaml 明文、不进镜像、不进日志**。仓库只提供 `Secret` 字段名称示例，真实 Secret 由部署者在集群中创建。

**升级顺序**：一次只停止一个 When Pod → 等待该 Pod 从成员列表消失和受影响时间轮完成接管 → 启动新版本 → 等 `/ready=200` → 再处理下一个。任何一步超时就停止升级，不继续删除后续 Pod。

---

## 8. 验收标准 + 必须有的测试（自动化验收）

**功能验收**

- [ ] `docker compose up` 一条命令起 ETCD、Redis、Kafka、HTTP Mock 和 3 个 When 节点；三个 node ID、worker ID 都不重复。
- [ ] K8s：`kubectl apply -k` 后 StatefulSet 的 3 个副本就绪，readiness 未通过的 Pod 不进入业务 Service。
- [ ] 探针生效：`/ready` 503 时 Pod 被摘、恢复后加回。
- [ ] 配置全走环境变量；无任何明文密钥入 yaml/镜像。
- [ ] CI：构建、单元、集成、故障和安全检查齐全，任一失败都不能合并。
- [ ] 收到 SIGTERM 后先退出 readiness，再停止接入和调度；在超时内完成正在执行的投递并退出。
- [ ] StatefulSet 滚动升级一次只更新一个 Pod，升级过程中已提交消息不丢，集群始终至少有两个就绪节点。
- [ ] 镜像不使用 `latest`，以非 root 用户运行，根文件系统只读，CPU/memory requests 与 limits 齐全。
- [ ] 第 52 阶段可以创建 CI 文件；阶段通过后，第 53 节修改 CI 会被 Runner 拒绝。

**必须有的测试**

- [ ] compose 冒烟：起集群 → 端到端提交/查询一条消息通过。
- [ ] compose 依赖恢复：停止并恢复 Redis/ETCD，断言 `/ready` 变化且节点不会被错误地反复重启。
- [ ] K8s 部署测试（kind）：apply → 就绪 → 端到端 → 逐个滚动升级。
- [ ] 优雅退出测试：投递进行中删除一个 Pod，断言 readiness 先失败、进程在超时内退出、消息最终进入终态。
- [ ] worker ID 冲突测试：两个存活节点使用同一 ID，后启动节点必须失败并给出明确原因。
- [ ] CI 检查使用专门的失败样例验证：失败测试、假 Secret 和不合格镜像分别使对应 Job 失败，不能往正式源码临时塞错误再恢复。
- [ ] CI 失败收集测试：故障测试失败时仍上传日志、测试报告并清理 compose/kind 环境。

---

## 9. 边界（本节不做什么）

- **不做** K8s Operator（CRD/调谐）——社区共建。
- **不负责** ETCD/Redis 自身高可用——部署方用现成方案（技术方案 §9.4、§15.1）。
- **不做**生产级监控大盘/告警规则（可给示例，不作交付）。
- **不自动配置**代码托管平台的分支保护、环境审批和生产 Secret；文档列出人工配置项。
- **不做**自动扩缩容。节点变化会触发时间轮迁移，HPA 需要容量模型和更严格的迁移限制，留到后续版本。
- **只做**：三形态部署制品 + CI 流水线 + 部署文档。
- **停止条件**：三形态都能起、探针/CI 生效、验收全部通过。

---

## 10. 专属 Harness（叠加在公共 Harness 之上）

**代码 / 制品**

- compose 和 K8s 文件放 `deploy/`，CI 固定放 `.github/workflows/ci.yml`；部署文档 `DEPLOY.md` 给出可以直接复制执行的命令、期望结果、排错和清理步骤。
- 镜像使用多阶段构建，运行阶段只保留 JRE 和应用文件；不把源码、构建缓存、测试工具或测试凭据带进运行镜像。
- `deploy/scripts/` 是部署验收入口，CI 和学员使用同一脚本；脚本必须有超时和明确退出码。
- 第 0 步生成的 `harness/contracts/**` 始终受保护。`.github/workflows/ci.yml` 仅在第 52 阶段可写，第 52 阶段通过后保存指纹并冻结。

**安全**（强制约束）

- 所有密钥经 Secret/环境注入，**yaml/compose/镜像/日志里绝无明文**；示例用 `${ENV}` 占位。
- CI 加"无硬编码密钥"扫描门禁。
- CI 对外部 PR 使用只读 Token，不读取生产环境 Secret；工作流中的第三方 Action 固定到明确版本或 commit。
- 容器非 root、只读根文件系统、移除不需要的 capabilities；镜像和依赖扫描报告作为 CI 产物保留。

**依赖**

- 选择仍在维护的基础镜像和依赖部署方式，并固定版本或 digest。生产环境的 ETCD、Redis、Kafka 通常由平台或托管服务提供，本节只定义连接方式和健康要求。

---

## 11. 交付物清单（这节结束应产出）

- [ ] Dockerfile 与 `deploy/compose/`：ETCD、Redis、Kafka、HTTP Mock 和 3 个 When 节点，镜像版本固定。
- [ ] `deploy/k8s/`：StatefulSet、Headless/业务/管理 Service、ConfigMap、Secret 引用、PDB、探针、资源和安全上下文。
- [ ] `deploy/scripts/`：等待就绪、冒烟、故障测试、日志收集和安全清理。
- [ ] `.github/workflows/ci.yml`：lint、单元、集成、compose、kind HA、安全扫描及失败产物上传。
- [ ] `DEPLOY.md`：三种形态的前置条件、逐步命令、期望输出、升级、回退、排错和清理。
- [ ] compose 冒烟、依赖恢复、kind 部署、优雅退出、滚动升级、worker ID 冲突和 CI 门禁测试。
- [ ] 本文档第 4—11 节作为该模块的 Loop 输入规格。

> 完成本节后，可以用统一命令部署 When，并由 CI 执行构建、测试和安全检查。第 53 节使用这些制品完成高可用联调。
