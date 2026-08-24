# When 核心流程测试方案与报告模板

## 目的

本方案不是通用的系统测试清单，而是第 39—45 节交付物的联调测试方案。主验收路径是**一个可执行的 When 单机节点**：在本机启动 Redis、ETCD 和该节点，验证从 gRPC 接入到 Redis 持久化、时间轮到期日志的闭环，并形成可复核的测试报告。三节点成员和转发测试保留为补充验证，不替代单机 Server 验收。测试必须保留命令输出、进程日志、Git 版本和结果；失败不能通过修改课程原料、验收测试或受保护文件来规避。

本轮测试以第 39—45 节已交付的“首次可运行闭环”为准：

```text
gRPC 客户端 -> 单机 When Server 的 Submit
          -> Redis 持久化(PENDING) -> 时间轮调度
          -> 到期回调 -> 仅写入可检索日志
```

## 范围与边界

| 类别 | 本轮必须验证 | 当前不能据此宣称已验证 |
| --- | --- | --- |
| 本地依赖 | 由 `harness/local/` 启动、等待并停止 Redis 与 ETCD；数据目录和端口隔离 | Docker、Docker Compose、Testcontainers |
| 单机 Server | 独立进程启动、gRPC 端口就绪、ETCD 节点/worker 注册和保活 | 真实三进程应用部署和故障接管 |
| 消息入口 | gRPC `Submit`、消息 ID、参数校验、Redis 先持久化、时间轮路由和本地处理 | 未实现的公开 HTTP 服务形态、鉴权与限流能力 |
| 时间轮与状态 | 未来消息到期、取消、查询、重启后从 Redis 重建并继续到期处理；到期仅写日志 | 真实 HTTP/Kafka 投递、投递失败重试、死信和生产级投递语义 |
| E2E 测试客户端 | 新建 `when-e2e-test` 模块，以独立 gRPC 客户端发送、查询、取消消息，并从外部读取 Redis/ETCD 与 Server 日志 | When 业务实现、真实下游 Sink、修改 Server 内部状态绕过接口 |
| 集群补充 | 三个独立节点身份注册到 ETCD、节点视图一致、唯一 Controller 选举、节点退出后的租约失效/视图变化 | Master/Slave 切换、负载重平衡、动态槽位迁移和故障接管（后续课程） |
| 验收 | 课程契约检查、模块测试、首次阶段验收和新增核心流程测试 | 需求文档中第 47 节及以后才实现的完整高可用与生产 Sink 能力 |

现有 `when-acceptance` 的 `FirstStageAcceptance` 可作为模块联调证据，但不能代替本方案的单机 Server 验收：本方案要求启动实际 `when-app` 进程、经其 TCP gRPC 端口提交消息并检查该进程日志。第 43 节允许以“多节点进程/线程模拟”完成三节点成员验收，因此这种模拟仍可作为集群补充证据；只有在 `when-app` 提供可执行启动器后，才能执行本方案的主路径。

## 第 39—45 节到测试场景的追溯

| 课程 | 已交付的测试对象 | 联调中必须保留的断言 | 对应场景 |
| --- | --- | --- | --- |
| 39：公共模型与模块契约 | `Message`、状态、`StoragePlugin`、`TimeWheel`、路由和 Sink SPI | 只使用公共接口；消息状态和 SinkConfig 分支不跨模块绕过 | 0、1、2、3、4、6 |
| 40：gRPC Server | `Submit`、`Query`、`Cancel` 三个 RPC；请求校验和错误映射 | 通过启动后的 Server 调用 `Submit`；过去时间、超范围延时、缺字段或 Sink 类型/配置不符返回 `INVALID_ARGUMENT` | 2、6 |
| 41：Redis 存储 | 创建/查询、原子状态变更、待处理消息恢复、终态清理 | `Submit` 返回后，从 Redis 外部诊断确认 `PENDING`；重启只重建待处理消息 | 2、5、6 |
| 42：ETCD 元数据 | key 空间、Lease、KeepAlive、Watch、事务 | 单机启动后节点和 worker claim 存在且持续保活 | 1、7 |
| 43：节点集群 | 注册、心跳、成员视图、Controller 选举 | 单机注册是主路径；三节点注册、唯一 Controller、下线 Watch 是补充场景 | 1、7 |
| 44：时间轮 | 延时注册、取消、隔离、到期回调、重建 | 到期为近似定时；回调只记录消息标识日志，不执行真实 Sink I/O | 3、5 |
| 45：接入与路由 | Snowflake ID、一致性 Hash、gRPC 转发、先落库后调度 | 单机路径直接处理且先落库后调度；跨节点 gRPC 转发为补充验证 | 2、5、6、7 |

因此，测试报告不能只写“集群已启动”或“接口可调用”；每一项结果都必须能回填到上表的课程交付物和断言。

## 运行原则

- 新建 Maven 测试模块 **`when-e2e-test`**。它是独立客户端和外部断言入口，不得包含 `when-common`、`when-api`、`when-storage-redis`、`when-timewheel`、`when-cluster`、`when-app` 的业务实现，也不得直接调用 Server 的应用层对象。
- `when-e2e-test` 只通过 TCP gRPC 调用 `Submit`、`Query`、`Cancel`；只读方式检查 Redis、ETCD 和 Server 日志。它从 Harness 生成的运行环境读取连接地址、Server 端口和日志路径。
- 主路径必须用独立 `when-app` 进程启动一个节点；进程使用唯一的 `WHEN_NODE_ID`、worker ID、gRPC 端口，并连接 Harness 启动的 Redis、ETCD。
- 测试启动前记录 `git rev-parse HEAD`、Java/Maven 版本、依赖端口、Server PID、Server 日志文件和配置来源；不得记录任何真实凭证、payload 或完整 Sink 配置。
- Server 就绪不是“进程存在”而已：必须同时确认 gRPC health 为 `SERVING`，并通过 ETCD 外部读取确认 `/when/nodes/{node_id}` 与 `/when/workers/{worker_id}` claim 已出现。保活期间二者不得消失。
- 到期处理器在本测试中只写一条机器可检索日志，至少包含 `message_id`、`trace_id`（若有）和 `tw_id`；不得写入 payload、凭证或完整 Sink 配置，也不得调用 HTTP/Kafka 等下游。
- 日志事件只证明“时间轮已触发回调”，不等价于真实投递成功。没有真实 Sink 成功结果时，报告不得把该日志写成“消息已投递”或擅自宣称 `DELIVERED`。
- 每个场景独立清理消息键、ETCD 前缀和节点进程；依赖通过 `harness/local/stop-deps.sh` 停止。失败也必须保留 Server、Redis、ETCD 和客户端日志。
- 任何实现、编译或测试失败都在当前分支修复后重试；测试标准、课程原料、外部验收和受保护文件不得为通过测试而修改。

## 可执行验收契约

### 单机 Server

| 项目 | 约定 |
| --- | --- |
| 启动入口 | `com.when.app.WhenServer`，由 `harness/core-tests/start-single-node.sh` 启动为独立 JVM 进程。 |
| 必需环境 | `WHEN_NODE_ID`、`WHEN_WORKER_ID`、`WHEN_GRPC_PORT`、`WHEN_REDIS_HOST`、`WHEN_REDIS_PORT`、`WHEN_ETCD_ENDPOINTS`。 |
| 单机时间轮 | 使用 `tw-0`，其静态 Master 指向本节点；单机场景不得依赖跨节点转发。 |
| 就绪判定 | gRPC health 为 `SERVING`，ETCD 中 `/when/nodes/{node_id}` 和 `/when/workers/{worker_id}` 均存在，且跨一次 2 秒保活间隔仍存在。 |
| 到期处理 | `LogOnlyDueMessageHandler` 只对成功从 `PENDING` claim 到 `DELIVERING` 的消息写日志；不调用 HTTP/Kafka Sink，不写 `DELIVERED`。 |
| 日志事件 | 单行必须包含 `event=when_due`、`message_id=<id>`、`tw_id=tw-0`；有 trace 时包含 `trace_id=<id>`。不得包含 payload、凭证或完整 Sink 配置。 |
| 停止 | `harness/core-tests/stop-single-node.sh` 终止 Server PID；节点 Lease 随正常关闭或进程退出释放。 |

### `when-e2e-test` 客户端

模块提供只经 TCP gRPC 调用的命令：`health`、`submit <delay-seconds>`、`query <message-id>`、`cancel <message-id>`。每条命令输出机器可解析的 `key=value` 行；`submit` 输出 `message_id`，`query`/`cancel` 输出 `status`。模块不得写 Redis、ETCD 或 Server 日志。

`harness/core-tests/run-single-node-flow.sh` 负责把客户端命令、Redis/ETCD 的只读诊断和 `grep -F` 日志断言串成场景。采用以下固定测试参数：

| 检查 | 参数与通过条件 |
| --- | --- |
| Server 就绪 | 30 秒内 health 与 ETCD claim 都通过。 |
| 正常延时消息 | `delay_seconds=2`；`Submit` 后 1 秒内 Query/Redis 均为 `PENDING`。 |
| 到期日志 | 从 `Submit` 返回起 8 秒内 `grep -F -- "$message_id" "$server_log"` 命中一次 `event=when_due`；Redis 状态为 `DELIVERING`，不得声称 `DELIVERED`。 |
| 取消消息 | `delay_seconds=5`；Cancel 后 Query/Redis 均为 `CANCELLED`；在原计划时间后额外等待 3 秒，grep 不得命中该 message ID。 |
| 重启恢复 | `delay_seconds=6`；提交并确认 `PENDING` 后在到期前重启同一 node/worker/端口；30 秒内重新就绪，8 秒内日志命中且只命中一次。 |
| 失败处理 | 任一超时、gRPC 非预期状态、Redis/ETCD 诊断不符、日志多次/缺失均使脚本非零退出，并保留证据。 |

## 核心测试流程

### 0. 基线和依赖就绪

执行课程契约检查和模块测试，启动并等待本机 Redis、ETCD。此步骤失败时，不启动业务节点。

预期证据：`./mvnw -q verify`、依赖启动/就绪日志、依赖端口与 PID、`harness/contracts/acceptance.sh` 的结果。

### 1. 单机 Server 启动与 ETCD 注册

1. 通过实际 `when-app` 启动入口启动一个 Server 进程，并等待其 gRPC health 为 `SERVING`。
2. 从 ETCD 读取该 Server 的 node 和 worker claim，确认 node ID、worker ID、监听端口与启动配置一致。
3. 跨一个以上保活周期再次读取，确认 claim 持续存在。
4. 记录启动到 health 就绪、启动到 ETCD 注册完成的实际耗时。

预期结果：Server 真正监听端口并注册 ETCD；ETCD 的 6 秒租约、2 秒保活是课程既定参数。该步骤不需要多节点选主。

### 2. 通过 Server Submit 并验证 Redis 持久化

1. `when-e2e-test` 的 gRPC 客户端连接场景 1 的 Server 端口，调用 `Submit` 提交一条短延时消息。
2. 从 `Submit` 响应记录 `message_id` 和计划到期时间。
3. 在消息到期前，客户端调用 `Query(message_id)`，并由模块以只读方式从 Redis 外部诊断读取该 `message_id`，两者均断言状态为 `PENDING`。
4. 断言 Redis 记录和 Server 返回的消息 ID 一致，且未发生真实下游投递。

预期结果：`Submit -> Redis PENDING -> 时间轮注册` 的顺序成立；Redis 持久化失败时 Server 必须返回失败且不得调度。

### 3. 到期触发并写日志

1. `when-e2e-test` 等待场景 2 的消息达到计划时间。
2. 模块以 `grep -F -- "$message_id" "$server_log"`（或同等不解释正则的日志查询）等待并读取单机 Server 日志中的到期事件。
3. 断言该日志表明 `DueMessageHandler` 已接收该消息，记录实际触发时间与计划时间的差值。
4. 检查运行期间没有 HTTP/Kafka 客户端或真实 Sink 调用记录。

预期结果：消息只触发一次可检索的到期日志；该日志是本场景的终点，不替代投递成功状态。

### 4. 取消与查询

1. `when-e2e-test` 调用 `Submit` 提交一条远期消息，调用 `Query` 并确认 Redis 为 `PENDING`。
2. 模块通过同一 Server 调用 `Cancel(message_id)`，再调用 `Query(message_id)` 并读取 Redis，二者均应为 `CANCELLED`。
3. 等待其原计划到期时间，以 `grep -F` 检查 Server 日志，断言没有该消息的到期事件。

预期结果：取消终态持久化，且已取消消息不触发到期日志。

### 5. Server 重启后的待处理消息重建

1. `when-e2e-test` 调用 `Submit` 提交一条未到期消息，并调用 `Query` 确认 Redis 为 `PENDING`。
2. 在到期前停止 Server 进程，再以相同 node ID、worker ID 和依赖连接配置重启。
3. 确认重启后的 Server 再次完成 ETCD 注册和 health 就绪，并从 Redis 重建待处理消息。
4. 模块等待到期并以 `grep -F` 断言新进程日志出现该消息的一条到期事件。

预期结果：重启只恢复需要恢复的待处理消息；报告必须记录是否观测到重复到期日志。

### 6. 负向路径

至少覆盖无效投递时间或请求参数、Redis 持久化失败两类错误。每项记录 gRPC 错误、Redis 是否留下脏数据、时间轮是否被错误调度，以及 Server 是否仍可继续处理下一条正常消息。

这里验证“先持久化再调度”的边界；不把尚未实现的投递重试、死信或下游投递行为作为通过条件。

### 7. 三节点集群补充验证

在单机主路径通过后，按第 43 节以三进程或线程模拟验证注册、心跳、唯一 Controller、Watch 和节点下线感知。该场景不包含时间轮切换、任务迁移或 rebalance，也不影响单机闭环的通过判定。

## 建议执行顺序

在新增单机 Server 启动入口和 `when-e2e-test` 模块后，统一按以下顺序执行；其中前两项现已存在，其余为本方案要求新增的运行入口，而非现成命令。

```bash
./mvnw -q verify
harness/local/start-deps.sh
harness/local/wait-deps.sh
# 未来入口：harness/core-tests/start-single-node.sh
# 未来入口：./mvnw -q -pl when-e2e-test -Pe2e verify
# `when-e2e-test` 依次执行 Submit -> Query/Redis PENDING -> 等待并 grep 到期日志，
# 然后执行 Submit -> Query -> Cancel -> Query/Redis CANCELLED -> grep 无到期日志。
# 未来入口：harness/core-tests/stop-single-node.sh
# 未来入口：harness/core-tests/run-cluster-supplement.sh
# 未来入口：harness/core-tests/render-report.sh <run-directory>
harness/local/stop-deps.sh
```

核心流程脚本应以非零退出码表示任一断言失败，并在 `reports/core-flow/<UTC时间戳>/` 保留：环境清单、单机 Server stdout/stderr、Server PID 和健康检查结果、ETCD/Redis 诊断、Submit/Query/Cancel 客户端结果、到期日志、断言摘要和清理结果。报告生成步骤只能读取这些外部结果，不能包含或替代 When 业务实现。

## 测试报告模板

```markdown
# When 核心流程测试报告

- 执行时间（UTC）：
- Git 提交：
- 执行人/自动化入口：
- Java / Maven：
- Redis / ETCD 启动方式、端口与 PID：
- 单机 Server（node ID、worker ID、监听端口、PID、日志文件）：
- `when-e2e-test` 模块与测试用例：
- 测试数据前缀与清理结果：

| 流程 | 实际客户端/运维动作 | 结果 | 关键断言 | message_id / 节点 ID | 证据文件与命令 | 实测耗时/状态 |
| --- | --- | --- | --- | --- | --- |
| 0. 基线和依赖 | 构建、启动并等待依赖 | PASS / FAIL | 契约、构建、依赖就绪 | N/A | 命令输出 | |
| 1. 单机启动与 ETCD | 启动 Server、health、读取 ETCD | PASS / FAIL / BLOCKED | health、node/worker claim、保活 | node ID / worker ID | Server 日志、ETCD 读取 | |
| 2. Submit、Query 与 Redis | E2E 客户端 Submit、Query、Redis 读取 | PASS / FAIL / NOT RUN | gRPC Submit/Query、Redis PENDING、先落库后调度 | message_id | 客户端输出、Redis 诊断 | |
| 3. 到期日志 | 等待到期并 `grep -F` Server 日志 | PASS / FAIL / NOT RUN | 指定 message_id 的 grep 命中、无下游投递 | message_id | grep 命令与日志行 | |
| 4. Cancel、Query 与 Redis | Submit、Cancel、Query、Redis 读取、grep | PASS / FAIL / NOT RUN | gRPC Cancel/Query、Redis CANCELLED、grep 无到期日志 | message_id | 客户端/Redis/grep 输出 | |
| 5. 重启重建 | 停止/重启 Server、Query、grep | PASS / FAIL / NOT RUN | 重启注册、恢复一次、到期日志 | message_id / PID | 两次 Server 日志、ETCD 读取 | |
| 6. 负向路径 | E2E 客户端提交非法/存储失败请求 | PASS / FAIL / PARTIAL | 不脏写、不误调度、可继续服务 | message_id（若生成） | gRPC 错误、Redis/grep 输出 | |
| 7. 三节点补充 | 三节点启动或线程模拟 | PASS / FAIL / N/A | 注册、选主、Watch；不含切换/rebalance | 3 个 node ID | 节点/ETCD 日志 | |

## 结论

- 单机节点核心闭环（场景 0—5）：PASS / FAIL / BLOCKED
- 单机节点完整矩阵（场景 0—6）：PASS / FAIL / PARTIAL
- 三节点补充验证：PASS / FAIL / N/A
- 已验证边界：
- 未覆盖或尚未实现能力：
- 失败项、复现命令和日志：
- 客观风险：
```

## 通过判定

场景 0—5 全部通过、依赖已停止、测试数据已清理、证据目录完整时，报告可以写“第 39—45 节单机节点核心闭环通过”。场景 6 是完整矩阵的负向验证，未执行时完整矩阵必须写为 `PARTIAL`，但不得掩盖场景 0—5 的实际结果。场景 7 的三节点验证单独报告；若未执行，必须写为 `N/A`，不得影响或冒充单机闭环结果。若当前没有可执行 `when-app` 启动入口，单机节点闭环必须判定为 `FAIL` 或 `BLOCKED`，不能用 JVM 内组合测试替代。
