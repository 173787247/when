# 第 53 节：运行第二阶段 Loop，完成 When 全流程交付
> 第 46 节用同一个 Loop Runner 完成了第一阶段（第 39—45 节）。本节不是假定第 47—52 节已经被人工实现，而是继续使用第 46 节建好的 Runner，一次启动第二阶段。
>
> Runner 先按顺序完成第 47—52 节，再执行全流程集成测试和发布制品验证，最后以已经跑通的命令、配置和制品为依据，完成部署手册、使用手册和交付报告。
---
## 1. 这一节做什么
一句话：**在第一阶段代码和 Runner 的基础上，执行 `./loop run --phase second`，连续完成高可用、真实投递、可观测、管理台、打包部署和最终交付。**
本节分为五部分：
1. **运行第二阶段**：Runner 按第 47—52 节顺序开发，每节单独分支、验收、合并。
2. **完整集成测试**：对最终 `master` 执行功能、集群、投递、故障、可观测和管理台验收。
3. **发布制品验证**：验证 TGZ、When 镜像和只部署 When 的 Kubernetes 清单。
4. **完成两份手册**：校准部署手册，生成使用手册，手册中的操作必须已被验证。
5. **生成交付报告**：汇总提交、测试、故障时间线、制品、修复记录和剩余风险。
![图 1　第二阶段：逐节开发 → 全流程测试 → 制品验证 → 手册与报告](第53节-图1-联调全流程.svg)
---
## 1.1 交给 Codex 的唯一启动提示词
第 53 节不要求学员手工逐节下提示词。第一阶段已经完成后，把下面这一段完整交给 Codex；它负责检查环境并启动既有 Runner。这个提示词是第二阶段的入口，不是让 Codex 绕过 Runner 直接在 `master` 上写代码。
```text
继续完成 When 项目第二阶段。先阅读仓库中的 AGENTS.md、loop.yaml，以及第 47—53 节的课程原料；不要臆测缺失的约定。

如果当前 Runner 尚不支持 `--phase second`，或 `loop.yaml` 尚未定义 `second`、lesson47—lesson53、发布候选验收或第二阶段最终验收，则先把它视为第 46 节 Runner 的未完成交付：在独立的 Runner 修复分支中，仅按第 46 节附件和本节定义补齐 phase 编排、状态迁移、候选验收责任回退、lesson53 交付阶段、报告及 Runner 自身测试。不得在该修复分支实现 When 业务模块，不得修改课程原料、既有 Harness、验收命令或阈值来让检查通过。Runner 自测、`./loop validate`、`./loop plan --phase first` 和 `./loop plan --phase second` 全部通过后，提交并以 `--no-ff` 合并 `master`。**这不是完成条件：同一 Codex 任务必须立即切回干净的 `master`，重新执行本提示词的预检命令，并在预检通过后自动执行 `./loop run --phase second`；不得向用户索要第二条“继续”提示词或停在“下一步可以运行”。**

确认当前在 master、工作区干净，且第一阶段为 FIRST PHASE COMPLETE。依次执行：
  ./loop validate
  ./loop plan --phase second
  ./loop status --phase second

检查通过后，立即执行 ./loop run --phase second，并持续推进，直到 Runner 输出 SECOND PHASE COMPLETE。不要只给我方案、不要停下来等待确认、不要要求我逐节发提示词。

严格遵守 Runner 的规则：每节从最新 master 创建或恢复 lesson/N 分支；通过本节验收后才合并 master；失败时保留失败证据，在原课程分支按课程原料修复并重试；已经 PASSED 且指纹未变化的课程不得重跑。发布候选验收失败时，按 Runner 指向的责任课程回退，不在 master 上临时补丁。

不得修改课程原料、Harness、验收命令或阈值来让自己通过；不得伪造测试、报告或手册内容。Redis、ETCD、Kafka 与观测后端是外部依赖，不打进 When 制品；本地开发和验收不使用 Docker Compose 或 Testcontainers。仅在第 52 节的制品验收中，按课程要求构建 When 自身的 OCI 镜像和 Kubernetes 清单。

完成后，给出 SECOND PHASE COMPLETE、最终 master 提交、三个报告路径、制品路径，以及仍存在的客观限制；如果因外部环境不可用无法完成，保留证据并明确说明缺少的环境条件。
```
这段提示词会先让 Codex 做只读预检，再把真正的调度权交给 `./loop run --phase second`。因此重试、断点恢复、分支切换和验收回退都由 Runner 统一处理，而不是靠聊天上下文记忆。
---
## 2. 它和第 46 节是什么关系
第 46 节已经产出：
- 根目录下的 `loop` 入口；
- Python Loop Runner 和 Codex Agent Adapter；
- `loop.yaml` 与对应 schema；
- 分支、状态、锁、重试、指纹失效和报告能力；
- 第一阶段的代码和验收证据。
本节不重写 Runner，也不再发一组“把每节实现掉”的人工提示词。它只选择 `loop.yaml` 中已定义的 `second` 阶段：
```bash
./loop run --phase second
```
两次运行共用一个状态仓，但每个阶段有自己的完成状态和最终验收：
```text
first  ：lesson39 → lesson40 → … → lesson45 → FIRST PHASE COMPLETE
second ：lesson47 → lesson48 → … → lesson52 → release gate → lesson53 delivery → SECOND PHASE COMPLETE
```
第一阶段已通过且指纹没有变化时，第二阶段不会重跑第 39—45 节。如果公共契约或第一阶段代码被改动，Runner 必须根据指纹使受影响的阶段失效，不允许带着过期的 `PASSED` 状态继续。
---
## 3. 第二阶段的 `loop.yaml`
### 3.1 阶段划分
`loop.yaml` 需要明确两个 phase，不再把所有课程混成一串无法选择的 stages：
```yaml
phases:
  first:
    stages: [lesson39, lesson40, lesson41, lesson42, lesson43, lesson44, lesson45]
    completion_message: FIRST PHASE COMPLETE
  second:
    requires_phase: first
    stages: [lesson47, lesson48, lesson49, lesson50, lesson51, lesson52]
    release_candidate_judges:
      - full_integration
      - ha_failover
      - observability
      - release_artifacts
    delivery_stage: lesson53
    final_judges:
      - full_regression
      - documentation_smoke
      - release_report_complete
    completion_message: SECOND PHASE COMPLETE
```
### 3.2 第 47—52 节怎样运行
| 阶段 | 主要产出 | 课程分支 | 通过后才能进入 |
|---|---|---|---|
| `lesson47` | Master/Slave 同步、重建与切换执行 | `lesson/47` | `lesson48` |
| `lesson48` | Controller、rebalance 与切换决策 | `lesson/48` | `lesson49` |
| `lesson49` | HTTP/Kafka Sink、重试与投递状态 | `lesson/49` | `lesson50` |
| `lesson50` | 指标、日志、Trace、健康检查与观测配置 | `lesson/50` | `lesson51` |
| `lesson51` | HTTP API、OpenAPI 和 Web 管理台 | `lesson/51` | `lesson52` |
| `lesson52` | Make、TGZ、When 镜像、When K8s 清单与 CI | `lesson/52` | 发布候选验收 |
每一节仍然使用第 46 节的 Git 规则：
```text
最新 master → 创建 lesson/N → Codex 实现当前课程 → Runner 验收
                                              ├─ 失败：同一分支保留证据并重试
                                              └─ 通过：提交并 --no-ff 合并 master
```
### 3.3 `lesson53` 是交付阶段
发布候选验收通过后才创建 `lesson/53` 分支。该分支只允许：
- 校准第 52 节生成的 `DEPLOY.md`；
- 新增 `USER_GUIDE.md`；
- 补充与已验证操作直接相关的示例配置；
- 引用 Runner 生成的测试证据和制品信息。
它不允许为了“写完手册”而修改验收测试、放宽阈值或改变业务语义。
---
## 4. 跑之前要具备什么
- 当前分支是 `master`，工作区干净。
- 第一阶段状态是 `FIRST PHASE COMPLETE`。
- 第 47—53 节文档存在且通过 `loop.yaml` schema 校验。
- Runner 支持 `--phase`、阶段状态和发布候选验收。
- 本机开发验收仍可使用 `harness/local/` 启动临时 Redis、ETCD，不使用 Docker、Docker Compose 或 Testcontainers。
- Kafka、三节点 HA、容器和 Kubernetes 验收使用课程事先准备的外部测试环境。
- Redis、ETCD、Kafka、HTTP Mock、Prometheus、Loki、Tempo、Grafana 和 OpenTelemetry Collector 都是外部依赖，不进入 When 制品。
现场演示前先做只读检查：
```bash
./loop validate
./loop plan --phase second
./loop status --phase second
```
`plan` 必须显示第 47—52 节、发布候选验收、`lesson53` 交付阶段和最终验收。
---
## 5. 跑第二阶段
正常情况下，学员只需提交上一节的启动提示词；其中会执行：
```bash
./loop run --phase second
```
课堂演示也可以直接执行这条命令，但两种方式都必须经过同一份 `loop.yaml` 和同一个 Runner，不能改成手工串行开发。
Runner 必须连续完成：
1. 验证第一阶段基线和指纹。
2. 从最新 `master` 创建或恢复 `lesson/47`。
3. 调用 Codex 实现当前课程，由 Runner 执行验收。
4. 通过后合并 `master`，继续第 48—52 节。
5. 六节全部合并后，执行发布候选验收。
6. 候选验收通过后创建 `lesson/53`，完成手册。
7. 合并 `lesson/53`，重新执行最终回归、手册命令检查和报告完整性检查。
8. 生成最终报告，输出 `SECOND PHASE COMPLETE`。
运行期间可以在另一个终端只读查看：
```bash
./loop status --phase second
./loop logs --stage lesson49
./loop report --phase second
```
不要同时启动第二个 Runner，不要手工修改 `.loop/state.json`，也不要在课程分支之外直接改业务代码。
---
## 6. 失败、重试与断点恢复
### 6.1 课程内失败
编译、单测、契约或该节集成测试失败时：
- Runner 保留当前 `lesson/N` 分支；
- 记录命令、退出码、输出和失败指纹；
- 把课程原料和失败证据交给下一次 Codex；
- 已经通过的课程不重跑。
### 6.2 发布候选验收失败
不在 `master` 上临时打补丁。Runner 根据失败的 judge 回到责任阶段：
| 失败类型 | 返回的责任阶段 |
|---|---|
| 副本同步、不丢消息、旧 Master 隔离 | `lesson47` |
| Controller 选主、切换决策、rebalance | `lesson48` |
| HTTP/Kafka 投递、重试、租约恢复 | `lesson49` |
| 指标、日志、Trace、健康检查 | `lesson50` |
| HTTP API、OpenAPI、管理台 | `lesson51` |
| Make、TGZ、镜像、K8s、CI、配置 | `lesson52` |
相关阶段及下游状态置为 `STALE`，从对应课程分支按文档修正，不通过改测试或降低阈值解决。
### 6.3 进程中断
仓库和测试环境仍可用时，重新执行：
```bash
./loop run --phase second
```
Runner 根据阶段状态、lesson commit、master merge commit 和指纹恢复，不从第 47 节重新开始。
---
## 7. 发布候选验收：全流程测试
第 52 节合并后，Runner 必须在最终 `master` 上用全新环境执行，不复用某节课的测试缓存。
### 7.1 基本功能和状态机
- Submit：提交不同延时、不同 Sink 的消息。
- Query：验证 `PENDING → DELIVERING → DELIVERED`。
- Cancel：取消 `PENDING` 消息，确认不再投递。
- 幂等：相同 Idempotency-Key 不创建两条业务消息。
- 重启恢复：节点重启后从 Redis 恢复未到期消息。
### 7.2 真实投递
- HTTP Mock 收到正确请求，超时、4xx、5xx 分类符合第 49 节约定。
- Kafka topic 收到正确的 key、headers 和 payload。
- 重试次数、退避和最终状态正确。
- 至少一次语义下可以重复，但已成功持久化的消息不能丢失。
### 7.3 集群与故障
![图 2　停止 Master 后，验证新 Master 接管、数据恢复和投递结果](第53节-图2-HA演示.svg)
- 三个 When 节点全部 `/ready`。
- 时间轮满足一主一从且 Master/Slave 不在同一节点。
- 消息按权威分配路由，跨节点转发正常。
- 在一批消息到期前停止 Master，10 秒内完成接管。
- 故障前已成功持久化的消息最终全部进入终态。
- 旧 Master 恢复后不能绕过 term/assignment revision 继续接受写入或投递。
- 停止 Controller 后重新选主，不影响已有时间轮继续运行。
### 7.4 可观测与管理台
- 指标显示提交、到期、投递、重试、副本落后和故障切换。
- Trace 显示 HTTP/gRPC/Sink 跨进程路径，Delivery Trace 通过 Span Link 关联 Submit Trace。
- JSON 日志能使用 `trace_id`、`message_id`、`node_id` 还原现场。
- `/health` 和 `/ready` 反映进程存活与是否能接收流量。
- 管理台可查看消息、投递记录、时间轮、节点和 Controller，不展示 payload 或敏感配置。
---
## 8. 发布制品验证
发布验收使用第 52 节定义的 Make 入口：
```bash
make verify
make release VERSION=1.0.0
make image VERSION=1.0.0 IMAGE_REPO=whenproject/when
make k8s-validate VERSION=1.0.0
```
### 8.1 TGZ
- `when-server-1.0.0.tgz` 解压后可通过示例配置连接外部 Redis、ETCD、Kafka 和 OTLP Collector。
- `bin/when start|stop|status|run` 行为正确。
- 启动后 `/health`、`/ready`、`/metrics` 符合配置。
- TGZ 中不存在外部依赖的二进制和数据目录。
### 8.2 Docker 镜像
- 镜像只包含 JRE 和 When Server 运行文件。
- 通过环境变量或外挂配置提供外部依赖地址。
- 使用非 root 用户，不在镜像中写入凭据。
- 容器启动、readiness 和优雅停机验收通过。
### 8.3 Kubernetes
- `deploy/k8s/base/` 只有 When StatefulSet、Service、ConfigMap、Secret 引用、探针、PDB 和安全上下文。
- 清单中不创建 Redis、ETCD、Kafka 和观测后端。
- 在已准备外部依赖的 namespace 中部署 When，三个 Pod 全部 Ready。
- 删除一个 When Pod 后能够恢复，业务和 HA 结果符合预期。
---
## 9. 生成部署手册和使用手册
手册在发布候选验收通过后生成，内容必须和当前代码、配置、OpenAPI 以及制品一致。
### 9.1 `DEPLOY.md`
第 52 节产出初版，本节根据真实验证结果完成校准。至少包含：
- 部署形态、外部依赖、配置字段、环境变量和 Secret；
- TGZ 解压、配置、启停和状态检查；
- Docker 镜像启动、配置挂载和优雅停机；
- Kubernetes 部署、探针、资源、PDB 和 Secret 引用；
- 升级、回退、日志、指标、Trace 和常见问题排查。
### 9.2 `USER_GUIDE.md`
使用手册面向调用方和运维人员，至少包含：
- When 解决什么问题、快速开始；
- Submit、Query、Cancel 的 HTTP 与 gRPC 示例；
- Idempotency-Key、消息状态和错误码；
- HTTP Sink 和 Kafka Sink 配置；
- 至少一次投递语义与下游幂等责任；
- 管理台使用，以及通过指标、Trace、日志和 Request ID 排查问题。
### 9.3 手册验收
- 不允许出现未实现的命令、配置项、API 字段或页面。
- 快速开始和部署命令使用全新目录重新执行。
- OpenAPI 示例由当前契约校验，不手工复制一份会漂移的字段定义。
- 所有示例使用占位凭据和非生产地址。
---
## 10. 生成测试和交付报告
Runner 使用本次 run 的原始证据生成：
```text
reports/
├── integration-test-report.md
├── deployment-verification-report.md
└── release-report.md
```
### 10.1 集成测试报告
- Git commit、运行 ID、时间、环境与外部依赖版本，不记录凭据；
- 每项验收命令、退出码、耗时和日志路径；
- 功能、真实投递、故障切换、恢复、可观测和管理台结果；
- 消息总数、终态分布、重复数、丢失数和故障时间线。
### 10.2 部署验证报告
- TGZ 文件名、大小、SHA-256、启停结果；
- OCI 镜像标签、digest、运行用户与边界检查；
- Kubernetes 清单校验、部署、Pod Ready 和删 Pod 恢复结果；
- 外部依赖未进入 When 制品的检查结果。
### 10.3 发布报告
- 第 47—53 节 lesson commit 与 master merge commit；
- 每个阶段的尝试数、失败指纹、修复摘要和结果；
- 发布制品、手册路径、已知限制和剩余风险；
- 是否满足本节停止条件。
报告中的“通过”必须来自命令证据，不接受 Codex 的文字自评。
---
## 11. 现场怎么演示
完整第二阶段可能运行较长时间。课堂上不快进播放所有编译日志，而是围绕五个关键画面：
1. **启动**：展示 `./loop plan --phase second`，然后执行 `./loop run --phase second`。
2. **过程**：展示当前课程、分支、尝试次数、验收命令和已合并课程。
3. **恢复**：演示一次可控失败或中断，重新执行同一条命令，已完成课程被跳过。
4. **验收**：展示一次 Master 故障切换，用指标、Trace、日志和消息结果互相印证。
5. **交付**：展示 TGZ、镜像、K8s 清单、`DEPLOY.md`、`USER_GUIDE.md` 和三份报告。
现场人员可以查看、讲解和做高风险判断，但不在屏幕上临时手工修业务代码把演示“救活”。
---
## 12. 停止条件
只有以下条件全部满足，Runner 才能输出 `SECOND PHASE COMPLETE`：
- [ ] 第 47—52 节全部通过，各自的课程分支已合并 `master`。
- [ ] 基本功能、真实 HTTP/Kafka 投递和状态机正确。
- [ ] 三节点、Master/Slave、Controller、rebalance 和跨节点路由正常。
- [ ] Master 故障后 10 秒内接管，已成功持久化的消息不丢失。
- [ ] 指标、日志、Trace 和健康检查能够完整说明故障过程。
- [ ] 管理台的消息、时间轮和集群功能通过验收。
- [ ] `make release` 生成的 TGZ 可连接外部依赖启动。
- [ ] When 镜像和只包含 When 的 Kubernetes 清单通过部署验收。
- [ ] `DEPLOY.md` 和 `USER_GUIDE.md` 与当前配置、OpenAPI 和制品一致。
- [ ] 集成测试、部署验证和发布报告完整，关键结论可追溯。
- [ ] 当前分支是 `master`，工作区干净，必需清理已执行。
---
## 13. 本节不做什么
- 不把 Redis、ETCD、Kafka 或观测组件打包进 When 制品。
- 不提供带全部依赖的 Docker Compose “一键生产环境”。
- 不为了演示好看而跳过故障、降低阈值、删除测试或修改历史证据。
- 不增加更多 Sink、鉴权限流、死信、优先级、跨地域、Operator 或 MCP。
- 不把“AI 说完成了”当作验收结果。
---
## 14. 本节交付物
- [ ] 第 47—52 节的课程提交、合并提交和阶段状态。
- [ ] 一次可恢复、可追溯的第二阶段 Loop 运行。
- [ ] 完整集成测试和 HA 故障注入证据。
- [ ] When Server TGZ、Web TGZ、When OCI 镜像和 When Kubernetes 清单。
- [ ] 校准完成的 `DEPLOY.md`。
- [ ] 面向使用方和运维人员的 `USER_GUIDE.md`。
- [ ] `integration-test-report.md`、`deployment-verification-report.md` 和 `release-report.md`。
- [ ] `SECOND PHASE COMPLETE` 状态和仍存在的客观风险。
> 到这里，When 才从“模块已经写完”走到“功能已验证、制品可部署、使用方有手册、问题有证据”。第 54 节回顾的不只是代码，而是这条完整交付链路。
