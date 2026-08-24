# 本对话提示词记录

按用户消息原始顺序整理；第 1、2 条内容相同，保留重复提交记录。

## 1. 建立 Loop 运行环境

这是一个没有任何代码的 When 空项目。当前输入只有 docs/ 中的四份项目文档和课程资料。

你现在只负责建立 Loop 运行环境，暂时不要实现任何 When 业务模块。

请先完整阅读：
1. docs/项目篇 When：业界调研.md
2. docs/When 项目需求文档.md
3. docs/When 项目技术方案文档.md
4. docs/When 项目 AI 编程实施指引.md
5. docs/第39—45节的 Loop 原料文档
6. docs/第46节：Loop 运行演示课（整合运行）.md
7. docs/第46节附件：Loop Runner 完整使用与实现手册.md

如果还没有 Git 仓库，先初始化并建立干净的 `master` 基线；然后从 `master` 创建并切换到 `lesson/46`。第一步只创建并验证以下内容：
- 根据上述文档生成适用于 Codex 的 AGENTS.md
- 仓库根目录的可执行入口 loop
- loop.yaml
- harness/loop/loop_runner.py
- harness/loop/agent_adapter.py
- harness/loop/schema/loop.schema.json
- harness/loop/schema/state.schema.json
- harness/loop/prompts/stage.md
- harness/loop/protected-paths.txt
- harness/contracts/ 下由文档提取的契约检查、验收入口和必要测试数据
- Loop Runner 自身的自动化测试
- harness/local/start-deps.sh、wait-deps.sh、stop-deps.sh，用本机进程启动和停止 Redis、ETCD
- 必要的 .gitignore；如果当前目录还不是 Git 仓库，则初始化本地 Git 仓库，建立 `master` 基线提交，但不要配置远端、不要 push

强制要求：
1. loop 是一个很薄的 Bash 脚本，只负责把参数转给 Python Runner。
2. 循环、课程依赖、状态、锁、课程提交、失败重试、指纹失效和报告由 Python Runner 实现。
3. Agent Adapter 使用 Codex 非交互命令，通过标准输入传入当前阶段提示词；命令为 codex exec --sandbox workspace-write --json -。
4. 不使用 --full-auto。
5. AGENTS.md 只能整理四份项目文档和课程资料中已经明确的约束，不能发明新的产品或技术要求。
6. harness/contracts/ 只负责从外部检查结果，不能包含 When 业务实现。
7. 开发 Runner 时不实现 when-common、when-api、when-storage-redis、when-timewheel、when-cluster、when-app 等业务模块，也不创建这些模块的业务源码。
8. Runner 自身测试、./loop validate 和 ./loop plan 全部通过后，提交 `lesson/46`，以 --no-ff 合并回 master，然后切换到 master。
9. 不修改课程原料、验收测试和受保护文件来让检查通过。
10. 状态文件必须原子写入；同一时间只允许一个 Runner；失败只重试当前阶段；已经通过且指纹未变化的阶段必须跳过。
11. 不使用 Git Worktree。Runner 开发使用第一个分支 lesson/46；正式 Loop 使用 lesson/39、lesson/40、lesson/41、lesson/42、lesson/43、lesson/44、lesson/45。每个业务分支都从最新 master 创建，通过后提交并合并回 master，下一节再从更新后的 master 创建分支。
12. 不使用 Docker、Docker Compose 或 Testcontainers。Redis、ETCD 由 harness/local/ 下的脚本作为本机临时进程启动，数据目录和端口相互隔离，验收结束后停止。
13. 实现、编译或测试出现问题时，不询问用户、不等待确认。重新阅读四份项目文档、公共契约、当前课程文档和失败日志，在当前 lesson 分支继续修改并重试。
14. 文档没有规定实现细节时，选择满足现有接口和验收的最简单方案，并把选择写入本轮报告；不能借此改变需求、公共接口或降低测试标准。
15. 项目最初没有 mvnw、pom.xml 和业务模块。它们由 lesson/39 创建。validate 不能仅因这些尚未生成就失败，但必须验证它们会在后续使用前由 lesson/39 生成。
16. loop.yaml 的正式业务阶段是 lesson39 到 lesson45；第 46 节负责创建和启动 Runner，不在正式 Loop 中再次执行。
17. 合并 lesson/46 后立即在 master 执行 ./loop run。不要停下来等我输入命令，也不要只告诉我“下一步可以运行”。
18. 持续运行，直到 lesson39 到 lesson45 全部完成、分别合并 master、最终端到端验收通过并输出 LOOP COMPLETE。
19. 如果进程中断但仓库和环境仍可用，直接再次执行 ./loop run，从保存的状态继续。

最终只向我报告：lesson/46 Runner 提交和合并结果、第 39—45 节的提交与合并结果、最终验收证据、LOOP COMPLETE 状态和仍存在的客观风险。

## 2. 建立 Loop 运行环境（重复提交）

这是一个没有任何代码的 When 空项目。当前输入只有 docs/ 中的四份项目文档和课程资料。

你现在只负责建立 Loop 运行环境，暂时不要实现任何 When 业务模块。

请先完整阅读：
1. docs/项目篇 When：业界调研.md
2. docs/When 项目需求文档.md
3. docs/When 项目技术方案文档.md
4. docs/When 项目 AI 编程实施指引.md
5. docs/第39—45节的 Loop 原料文档
6. docs/第46节：Loop 运行演示课（整合运行）.md
7. docs/第46节附件：Loop Runner 完整使用与实现手册.md

如果还没有 Git 仓库，先初始化并建立干净的 `master` 基线；然后从 `master` 创建并切换到 `lesson/46`。第一步只创建并验证以下内容：
- 根据上述文档生成适用于 Codex 的 AGENTS.md
- 仓库根目录的可执行入口 loop
- loop.yaml
- harness/loop/loop_runner.py
- harness/loop/agent_adapter.py
- harness/loop/schema/loop.schema.json
- harness/loop/schema/state.schema.json
- harness/loop/prompts/stage.md
- harness/loop/protected-paths.txt
- harness/contracts/ 下由文档提取的契约检查、验收入口和必要测试数据
- Loop Runner 自身的自动化测试
- harness/local/start-deps.sh、wait-deps.sh、stop-deps.sh，用本机进程启动和停止 Redis、ETCD
- 必要的 .gitignore；如果当前目录还不是 Git 仓库，则初始化本地 Git 仓库，建立 `master` 基线提交，但不要配置远端、不要 push

强制要求：
1. loop 是一个很薄的 Bash 脚本，只负责把参数转给 Python Runner。
2. 循环、课程依赖、状态、锁、课程提交、失败重试、指纹失效和报告由 Python Runner 实现。
3. Agent Adapter 使用 Codex 非交互命令，通过标准输入传入当前阶段提示词；命令为 codex exec --sandbox workspace-write --json -。
4. 不使用 --full-auto。
5. AGENTS.md 只能整理四份项目文档和课程资料中已经明确的约束，不能发明新的产品或技术要求。
6. harness/contracts/ 只负责从外部检查结果，不能包含 When 业务实现。
7. 开发 Runner 时不实现 when-common、when-api、when-storage-redis、when-timewheel、when-cluster、when-app 等业务模块，也不创建这些模块的业务源码。
8. Runner 自身测试、./loop validate 和 ./loop plan 全部通过后，提交 `lesson/46`，以 --no-ff 合并回 master，然后切换到 master。
9. 不修改课程原料、验收测试和受保护文件来让检查通过。
10. 状态文件必须原子写入；同一时间只允许一个 Runner；失败只重试当前阶段；已经通过且指纹未变化的阶段必须跳过。
11. 不使用 Git Worktree。Runner 开发使用第一个分支 lesson/46；正式 Loop 使用 lesson/39、lesson/40、lesson/41、lesson/42、lesson/43、lesson/44、lesson/45。每个业务分支都从最新 master 创建，通过后提交并合并回 master，下一节再从更新后的 master 创建分支。
12. 不使用 Docker、Docker Compose 或 Testcontainers。Redis、ETCD 由 harness/local/ 下的脚本作为本机临时进程启动，数据目录和端口相互隔离，验收结束后停止。
13. 实现、编译或测试出现问题时，不询问用户、不等待确认。重新阅读四份项目文档、公共契约、当前课程文档和失败日志，在当前 lesson 分支继续修改并重试。
14. 文档没有规定实现细节时，选择满足现有接口和验收的最简单方案，并把选择写入本轮报告；不能借此改变需求、公共接口或降低测试标准。
15. 项目最初没有 mvnw、pom.xml 和业务模块。它们由 lesson/39 创建。validate 不能仅因这些尚未生成就失败，但必须验证它们会在后续使用前由 lesson/39 生成。
16. loop.yaml 的正式业务阶段是 lesson39 到 lesson45；第 46 节负责创建和启动 Runner，不在正式 Loop 中再次执行。
17. 合并 lesson/46 后立即在 master 执行 ./loop run。不要停下来等我输入命令，也不要只告诉我“下一步可以运行”。
18. 持续运行，直到 lesson39 到 lesson45 全部完成、分别合并 master、最终端到端验收通过并输出 LOOP COMPLETE。
19. 如果进程中断但仓库和环境仍可用，直接再次执行 ./loop run，从保存的状态继续。

最终只向我报告：lesson/46 Runner 提交和合并结果、第 39—45 节的提交与合并结果、最终验收证据、LOOP COMPLETE 状态和仍存在的客观风险。

## 3. 状态询问

还在运行吗？

## 4. 状态询问

当前进度是？

## 5. 第 45 节状态询问

45 还在执行吗

## 6. 继续执行

继续

## 7. 验收询问

验收通过了吗？

## 8. 核心流程测试与报告

运行核心流程测试，并完成核心功能的测试报告。比如运行集群，核心测试流程。你先写个文档。放doc。 你思考下，是否理解我要什么

## 9. 文档范围说明

你应该在39到45的基础上，去创建这份文档

## 10. 按流程测试并提交报告

按照这个流程运行测试，并提交测试报告。需要包含运行结果。

## 11. Server、消息提交与 Redis 持久化确认

有启动server ，submit 消息，持久化存储redis消息吗？ 测试报告里面没明确体现

## 12. 单机闭环验收定义

验证应该是完成单机 的server启动，并连接etcd，写入延时消息，并触发，触发不需要投递下游，只要写日志即可。能理解吗？

## 13. 调整核心流程测试方案

我们调整一下docs/When 核心流程测试方案与报告模板.md。

需要明确单机节点闭环的测试流程。

## 14. 新建测试客户端模块

明确新建一个test module，作为客户端发送延时消息？get 延时消息，取消延时消息。到期投递后，等grep 到日志。这些有吗？

## 15. 报告结果与核心链路覆盖

测试报告，中需要明确每个流程的结果。 你思考下覆盖了核心链路了吗？

## 16. 全链路集成测试覆盖评估

你思考下这个全链路集成测试下，文档够吗？覆盖核心链路了吗

## 17. 完善并执行报告

行，完善报告模板，然后执行，然后输出测试报告？

## 18. 创建第一阶段测试 Skill

创建一个skill，来执行这个测试。就是第一阶段的测试。并能输出对应的结果。

## 19. 导出本对话提示词

把这个对话里面的提示词，都按顺序放到：docs/prompt.md 中。提示词太长的话，可以用md格式。
