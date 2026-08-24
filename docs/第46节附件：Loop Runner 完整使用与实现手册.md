# 第 46 节附件：Loop Runner 完整使用与实现手册

> 适用范围：When 两次 Loop 运行。第 46 节只提交一个提示词；Codex 先开发支持 phase 的 Runner，再执行第 39—45 节的 `first`。第 53 节复用同一 Runner 执行第 47—52 节、发布验收和交付文档的 `second`。
>
> 面向对象：参加训练营的学员、实现 Runner 的开发者，以及被 Runner 调用的 AI Agent。
>
> 本手册是 Loop Runner 的完整行为规格。第 46 节课堂讲义负责讲清核心思路和演示过程；本手册负责回答如何实现、运行、恢复和排错。

---

## 1. 先看结论

When 使用一个运行在 AI 外面的 Loop Runner。项目初始只有四份项目文档和课程资料，没有代码、`AGENTS.md`、构建文件、测试或 `./loop`。学员只给 Codex 一次初始任务：先在 `lesson/46` 分支创建并验证 Runner、合并 `master`，随后直接执行 `./loop run --phase first`。Runner 在设计上同时支持第 53 节的 `./loop run --phase second`。

Runner 依次完成：

```text
读取状态
  → Codex 从 master 创建 lesson/46
  → 开发并验证 Runner
  → 提交并合并 lesson/46
  → Codex 执行 ./loop run --phase first
  → Runner 找到第一节未完成课程
  → 从最新 master 创建 lesson/{节号}
  → 准备当前课程上下文
  → 调用 AI 修改代码
  → Runner 执行验收命令
  → 失败则根据文档在同一分支继续修正
  → 通过则提交并合并回 master
  → 从更新后的 master 开启下一节
  → 最后重新执行整体端到端验收
```

五条最重要的规则：

1. AI 只处理当前课程分支，Runner 掌握全局进度。
2. AI 不能判断自己是否成功，只有 Runner 实际运行的测试和检查可以决定。
3. Runner 工具先使用 `lesson/46`；两个 phase 的课程都使用独立 `lesson/{节号}` 分支，通过后合并 `master`；不使用 Git Worktree。
4. 不使用 Docker；问题根据文档和失败证据自动解决，不等待用户确认。
5. 只有选中 phase 的课程和最终验收重新执行成功，Runner 才输出 `FIRST PHASE COMPLETE` 或 `SECOND PHASE COMPLETE`。

### 唯一入口：把一个提示词交给 Codex

学员不需要自己解决“第一次还没有 `./loop`”的问题。唯一提示词要求 Codex 连续完成：

```text
把唯一提示词交给 Codex
  → 建立本地 Git master 基线
  → 创建 lesson/46
  → 创建项目约束、验收入口和 Runner
  → 运行 Runner 自身测试、validate、plan
  → 提交并合并 lesson/46
  → Codex 立即执行 ./loop run --phase first
  → 自动修正失败并持续恢复
  → 第 39—45 节与最终验收全部通过
  → 输出 FIRST PHASE COMPLETE 和第一阶段报告
```

唯一提示词如下：

```text
这是一个没有任何代码的 When 空项目。当前输入只有 docs/ 中的四份项目文档和课程资料。

你现在只负责建立 Loop 运行环境，暂时不要实现任何 When 业务模块。

请先完整阅读：
1. docs/项目篇 When：业界调研.md
2. docs/When 项目需求文档.md
3. docs/When 项目技术方案文档.md
4. docs/When 项目 AI 编程实施指引.md
5. docs/第39—45节的第一阶段 Loop 原料文档
6. docs/第47—53节的第二阶段 Loop 原料与交付文档
7. docs/第46节：Loop 运行演示课（整合运行）.md
8. docs/第46节附件：Loop Runner 完整使用与实现手册.md

如果还没有 Git 仓库，先初始化并建立干净的 master 基线；然后从 master 创建并切换到 lesson/46。第一步只创建并验证以下内容：
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
- 必要的 .gitignore；如果当前目录还不是 Git 仓库，则初始化本地 Git 仓库并建立 master 基线提交，但不要配置远端、不要 push

强制要求：
1. loop 是一个很薄的 Bash 脚本，只负责把参数转给 Python Runner。
2. 循环、课程依赖、状态、锁、课程提交、合并记录、失败重试、指纹失效和报告由 Python Runner 实现。
3. Agent Adapter 使用 Codex 非交互命令，通过标准输入传入当前阶段提示词；命令为 codex exec --sandbox workspace-write --json -。
4. 不使用 --full-auto。
5. AGENTS.md 只能整理四份项目文档和课程资料中已经明确的约束，不能发明新的产品或技术要求。
6. harness/contracts/ 只负责从外部检查结果，不能包含 When 业务实现。
7. 开发 Runner 时不实现 when-common、when-api、when-storage-redis、when-timewheel、when-cluster、when-app 等业务模块，也不创建这些模块的业务源码。
8. Runner 自身测试、./loop validate 和 ./loop plan 全部通过后，提交 lesson/46，以 --no-ff 合并回 master，然后切换到 master。
9. 不修改课程原料、验收测试和受保护文件来让检查通过。
10. 状态文件必须原子写入；同一时间只允许一个 Runner；失败只重试当前阶段；已经通过且指纹未变化的阶段必须跳过。
11. 不使用 Git Worktree。Runner 开发使用 lesson/46；`first` 使用 lesson/39 到 lesson/45，`second` 使用 lesson/47 到 lesson/53。每个分支从最新 master 创建，通过后以 --no-ff 合并回 master。
12. 不使用 Docker、Docker Compose 或 Testcontainers。Redis、ETCD 由 harness/local/ 脚本作为本机临时进程启动，使用临时目录和随机端口，验收后停止。
13. 实现、编译或测试失败时，不询问用户、不等待确认。重新阅读四份项目文档、公共契约、当前课程文档和失败日志，在当前 lesson 分支继续修正。
14. 文档没有规定实现细节时，选择满足现有接口和验收的最简单方案，记录选择后继续；不能修改文档或降低验收标准。
15. 项目最初没有 mvnw、pom.xml 和业务模块。它们由 lesson/39 创建。validate 只需验证后续文件会在使用前由 lesson/39 生成。
16. loop.yaml 定义 `first` 和 `second`；第 46 节只创建 Runner，不在任何 phase 中再次执行。
17. Runner 从建立时就支持 `--phase`、phase 前置条件、phase 终态和 phase 报告。
18. 合并 lesson/46 后立即在 master 执行 ./loop run --phase first。不要停下来等我输入命令。
19. 持续运行，直到 lesson39 到 lesson45 全部合并 master、最终验收通过并输出 FIRST PHASE COMPLETE。
20. 如果进程中断但仓库和环境仍可用，再次执行 ./loop run --phase first，从状态继续。

最终只向我报告：lesson/46 Runner 提交和合并结果、第 39—45 节的提交与合并结果、第一阶段验收证据、FIRST PHASE COMPLETE 状态和仍存在的客观风险。
```

这段提示词既负责把“只有文档的空目录”变成“可以启动 Loop 的工程目录”，也负责立即启动并跑完第一次 Loop。这里的“一个提示词”指学员只提交一次；Runner 后续为各课程自动生成内部任务，不需要学员参与。开发 Runner 时不能顺手写 When 业务代码；Runner 合并后，业务代码只能由正式 Loop 在对应课程分支中实现。

根目录的 `loop` 应是一个薄 Bash 入口：

```bash
#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$repo_root/harness/loop/loop_runner.py" "$@"
```

创建后设置执行权限：

```bash
chmod +x loop
```

因此，对“Loop 是不是 Shell 脚本”的准确回答是：**`loop` 命令是 Shell 脚本，Loop Runner 的主要逻辑是 Python。** Shell 只负责找到仓库目录并转发参数，Python 负责循环、状态、重试、验收和恢复。

---

## 2. Runner 不是什么

Runner 是唯一提示词先在 `lesson/46` 分支创建的工程工具，不是 When 的生产模块。自身测试通过后先合并 `master`，随后同一个 Codex 任务启动正式 Loop。

- 不进入消息提交、调度、投递或故障切换链路；
- 不随 When 服务部署到生产环境；
- 不代替 Maven、JUnit、本机依赖进程或端到端测试；
- 不靠一段无限循环 Prompt 维持状态；
- 不允许 AI 修改状态后绕过验收命令；
- 不负责自动修改需求、技术方案和公共契约；
- 不自动 push、发布、部署生产环境或执行不可恢复操作。

Runner 可以使用 Python 实现，因为它属于工程工具；When 的生产代码仍使用 Java。

---

## 3. 三个角色怎样配合

| 角色 | 主要职责 | 权限边界 |
|---|---|---|
| Runner | 创建课程分支、状态、锁、上下文、调用 AI、验收、合并 `master`、重试和报告 | 不能编写业务实现，不能放宽验收 |
| AI Agent | 阅读当前原料、实现当前模块、解释修改、根据失败证据修正 | 只能修改当前课程允许路径，不能宣布 `PASSED` |
| 验收程序 | 构建、单测、集成测试、契约和整体端到端验收 | 由 Runner 执行，AI 只能读取结果 |

人只负责启动 Runner、查看状态和最终报告。普通实现选择、编译错误、测试失败和分支内冲突由 Runner 根据文档处理，不向用户发起确认。宿主机缺少必需程序或仓库损坏时，Runner直接退出并报告事实，也不进入等待确认状态。

---

## 4. `lesson/46` 合并后，仓库应该有哪些文件

```text
when/
├── loop                              # 薄 Bash 入口
├── loop.yaml
├── AGENTS.md
├── docs/
│   ├── 项目篇 When：业界调研.md
│   ├── When 项目需求文档.md
│   ├── When 项目技术方案文档.md
│   ├── When 项目 AI 编程实施指引.md
│   └── 第39—46节课程原料
├── harness/
│   ├── contracts/
│   ├── local/
│   │   ├── start-deps.sh
│   │   ├── wait-deps.sh
│   │   └── stop-deps.sh
│   └── loop/
│       ├── loop_runner.py
│       ├── agent_adapter.py
│       ├── schema/
│       │   ├── loop.schema.json
│       │   └── state.schema.json
│       ├── prompts/
│       │   └── stage.md
│       └── protected-paths.txt
└── .loop/                            # 首次执行命令后由 Runner 创建
    ├── state.json
    ├── state.json.bak
    ├── run.lock
    └── runs/{run_id}/
```

说明：

- `loop`：唯一用户入口，内部调用 Python Runner；
- `loop.yaml`：唯一阶段清单；
- `AGENTS.md`：Codex 自动读取、对所有阶段生效的公共 Harness；
- `agent_adapter.py`：隔离具体 AI 产品的调用方式；
- `schema/`：严格校验配置和状态，未知字段默认报错；
- `protected-paths.txt`：AI 可读取但不得修改的验收命令、契约快照和 CI 文件；
- `.loop/`：运行时状态，不提交密码、Token、业务 payload 或完整连接串。

课程提供的原始输入只有 `docs/` 中的四份项目文档、课程原料、Runner 完整规格和唯一提示词。上图其余文件都在 `lesson/46` 中生成；`.loop/` 则在执行 Runner 命令时创建。Codex 先完成这一步，再由同一个任务调用新生成的 `./loop`。

---

## 5. 命令完整说明

### 5.1 `./loop validate`

```bash
./loop validate
```

只做只读检查，不调用 AI，不修改代码：

- `loop.yaml` 符合 schema；
- phase ID 和课程 ID 唯一，`first` 顺序固定为 39—45，`second` 顺序固定为 47—52 后进入发布验收和第 53 节交付；
- 初始输入文档和 `lesson/46` 生成的受保护路径存在；
- 每节输出范围没有未经声明的重叠；
- 当前分支为 `master`，工作区干净，`lesson/46` 已正确合并，不存在来源不明的 `lesson/39`—`lesson/45`；
- Git、Python、Codex、Java、Redis、ETCD 等宿主机工具可用；
- 配置明确禁止 Worktree、Docker、Docker Compose 和 Testcontainers；
- 暂时不存在的构建文件或验收命令会由 lesson/39 在使用前生成；
- Agent Adapter 可以启动，但不发送真实任务；
- 当前仓库满足干净基线要求。

空项目第一次预检时，`pom.xml`、`mvnw` 和业务模块尚不存在是正常现象，它们由 lesson/39 创建。Redis 和 ETCD 可执行程序必须已经安装，但预检只检查版本和可启动性，不长期占用端口。

验证失败必须给出具体字段、路径或依赖关系，不能只输出“配置错误”。

### 5.2 `./loop plan`

```bash
./loop plan --phase first
./loop plan --phase second
```

打印选中 phase 的课程顺序、分支名、每节输入、输出范围、验收命令、发布门禁和当前状态。它不创建分支、不调用 AI、不修改代码。

示例输出：

```text
39 lesson/39  PENDING -> project skeleton + common contract
40 lesson/40  PENDING -> gRPC API
41 lesson/41  PENDING -> Redis storage
42 lesson/42  PENDING -> ETCD metadata
43 lesson/43  PENDING -> cluster membership
44 lesson/44  PENDING -> time wheel
45 lesson/45  PENDING -> ingress, routing and first full acceptance
```

### 5.3 `./loop run`

```bash
./loop run --phase first
```

没有状态时为选中 phase 创建新 run；已有未完成状态时恢复当前 run。Runner 自动跳过已经合并到 `master` 且指纹仍有效的课程。不允许同时运行两个 phase。

可选参数：

```bash
./loop run --phase first --config loop.yaml
./loop run --phase second --config loop.yaml
```

不提供从命令行放宽重试次数、忽略验收命令、跳过受保护检查或强制成功的参数。

### 5.4 `./loop status`

```bash
./loop status --phase first
./loop status --phase second --json
```

输出：phase、run ID、当前课程或发布门禁、`lesson/{节号}` 分支、已经合并的课程、尝试次数、最近一次验收结果和 phase 完成状态。`--json` 供其他工具读取。

### 5.5 `./loop logs`

```bash
./loop logs --stage lesson44
./loop logs --stage lesson44 --attempt 2
```

默认显示指定课程最近一次尝试的摘要。指定 `--attempt` 时读取历史尝试。日志必须脱敏，不显示 Token、密码、payload 和完整连接串。

### 5.6 `./loop invalidate`

```bash
./loop invalidate --stage lesson39 --downstream
```

用于课程文档或公共契约发生主动变更。`--downstream` 表示同时失效后续课程。修改第 39 节公共契约时必须使用该参数。

Runner 不允许静默保留依赖旧契约构建出的 `PASSED` 状态。

### 5.7 `./loop report`

```bash
./loop report
```

生成或刷新：

```text
.loop/runs/{run_id}/final-report.md
```

报告包含课程、lesson commit、master merge commit、尝试、输入/输出指纹、验收命令、失败修复记录和最终验收。

---

## 6. `loop.yaml` 完整参考配置

下面是第一次运行的完整基线。实际仓库可以调整路径，但不能删除行为约束。

```yaml
version: 1

project:
  name: when
  docs_root: docs

git:
  base_branch: master
  branch_template: "lesson/{lesson}"
  use_worktree: false
  require_clean_master: true
  merge_after_pass: true
  merge_strategy: no_ff
  keep_lesson_branches: true
  allow_push: false

runtime:
  allow_docker: false
  allow_docker_compose: false
  allow_testcontainers: false
  local_services:
    start: ["harness/local/start-deps.sh"]
    wait: ["harness/local/wait-deps.sh", "--timeout", "30"]
    stop: ["harness/local/stop-deps.sh"]
    env_file: ".loop/runtime.env"

agent:
  adapter: harness/loop/agent_adapter.py
  timeout_seconds: 1800
  continue_without_confirmation: true
  environment_allowlist:
    - PATH
    - JAVA_HOME
    - MAVEN_OPTS
    - REDIS_URL
    - ETCD_ENDPOINTS
  max_output_bytes: 10485760

defaults:
  attempts_per_batch: 5
  on_batch_exhausted: replan_same_lesson
  one_active_stage: true
  judge_timeout_seconds: 1200
  retry_backoff_seconds: [2, 5, 10, 20]

reference_docs:
  - "docs/项目篇 When：业界调研.md"
  - "docs/When 项目需求文档.md"
  - "docs/When 项目技术方案文档.md"
  - "docs/When 项目 AI 编程实施指引.md"

orchestrator_spec: "docs/第46节：Loop 运行演示课（整合运行）.md"
common_contract: "docs/第39节：构建 When 领域模型与模块间契约（Loop 原料·底座）.md"

protected_paths:
  - AGENTS.md
  - docs/**
  - harness/contracts/**
  - harness/loop/schema/**
  - harness/loop/protected-paths.txt
  - when-acceptance/src/test/**
  - .github/workflows/**

phases:
  first:
    stages: [lesson39, lesson40, lesson41, lesson42, lesson43, lesson44, lesson45]
    final_judges: [root_verify, start_environment, wait_environment, acceptance, stop_environment]
    completion_message: FIRST PHASE COMPLETE
  second:
    requires_phase: first
    stages: [lesson47, lesson48, lesson49, lesson50, lesson51, lesson52]
    release_candidate_judges:
      - second_full_integration
      - second_ha_failover
      - second_observability
      - second_release_artifacts
    delivery_stage: lesson53
    final_judges:
      - second_full_regression
      - second_documentation_smoke
      - second_release_report_complete
    completion_message: SECOND PHASE COMPLETE

stages:
  - id: lesson39
    lesson: 39
    branch: lesson/39
    specs:
      - "docs/第39节：构建 When 领域模型与模块间契约（Loop 原料·底座）.md"
    depends_on: []
    write_paths:
      - pom.xml
      - mvnw
      - .mvn/**
      - "*/pom.xml"
      - when-common/**
    fingerprint_paths:
      - pom.xml
      - mvnw
      - .mvn/**
      - when-common/**
    judge:
      command: ["./mvnw", "-q", "verify"]
      timeout_seconds: 1200

  - id: lesson40
    lesson: 40
    branch: lesson/40
    specs:
      - "docs/第40节：gRPC Server 与对外接口设计（Loop 原料）.md"
    depends_on: [lesson39]
    write_paths:
      - when-api/**
    fingerprint_paths:
      - when-api/**
    judge:
      command: ["./mvnw", "-q", "-pl", "when-api", "-am", "verify"]
      timeout_seconds: 1200

  - id: lesson41
    lesson: 41
    branch: lesson/41
    specs:
      - "docs/第41节：存储层插件化 + Redis 插件（Loop 原料）.md"
    depends_on: [lesson40]
    services: [redis]
    write_paths:
      - when-storage-redis/**
    fingerprint_paths:
      - when-storage-redis/**
    judge:
      command: ["./mvnw", "-q", "-pl", "when-storage-redis", "-am", "verify"]
      timeout_seconds: 1200

  - id: lesson42
    lesson: 42
    branch: lesson/42
    specs:
      - "docs/第42节：元数据存储 ZK vs ETCD 选型（Loop 原料）.md"
    depends_on: [lesson41]
    services: [etcd]
    write_paths:
      - when-cluster/**
    fingerprint_paths:
      - when-cluster/**
    judge:
      command: ["./mvnw", "-q", "-pl", "when-cluster", "-am", "verify"]
      timeout_seconds: 1200

  - id: lesson43
    lesson: 43
    branch: lesson/43
    specs:
      - "docs/第43节：节点集群——注册、组集群、切换（Loop 原料）.md"
    depends_on: [lesson42]
    services: [etcd]
    shared_write_paths:
      - when-cluster/**
    write_paths:
      - when-cluster/**
    fingerprint_paths:
      - when-cluster/**
    judge:
      command: ["./mvnw", "-q", "-pl", "when-cluster", "-am", "verify"]
      timeout_seconds: 1200

  - id: lesson44
    lesson: 44
    branch: lesson/44
    specs:
      - "docs/第44节：时间轮——延时机制原理与实现（Loop 原料）.md"
    depends_on: [lesson43]
    services: [redis]
    write_paths:
      - when-timewheel/**
    fingerprint_paths:
      - when-timewheel/**
    judge:
      command: ["./mvnw", "-q", "-pl", "when-timewheel", "-am", "verify"]
      timeout_seconds: 1200

  - id: lesson45
    lesson: 45
    branch: lesson/45
    specs:
      - "docs/第45节：接入层 + 路由层（Loop 原料）.md"
    depends_on: [lesson44]
    services: [redis, etcd]
    write_paths:
      - when-ingress-router/**
      - when-app/**
      - when-test-support/**
      - when-acceptance/src/main/**
      - config/first-run/**
    fingerprint_paths:
      - when-ingress-router/**
      - when-app/**
      - when-test-support/**
      - when-acceptance/src/main/**
      - config/first-run/**
    judge:
      command: ["./mvnw", "-q", "-pl", "when-acceptance", "-Pacceptance", "verify"]
      timeout_seconds: 1800

final_judges:
  - id: root_verify
    command: ["./mvnw", "-q", "verify"]
    timeout_seconds: 1800
  - id: start_environment
    command: ["harness/local/start-deps.sh"]
    timeout_seconds: 60
  - id: wait_environment
    command: ["harness/local/wait-deps.sh", "--timeout", "30"]
    timeout_seconds: 45
  - id: acceptance
    command: ["./mvnw", "-q", "-pl", "when-acceptance", "-Pacceptance", "verify"]
    timeout_seconds: 1800
  - id: stop_environment
    command: ["harness/local/stop-deps.sh"]
    always_run: true
    timeout_seconds: 30
```

上面完整展开了第 39—45 节的第一阶段基线。同一份 `loop.yaml` 还必须声明第二阶段，Runner 不能到第 53 节才临时增加 phase 能力。第二阶段的 stage 至少包含：

```yaml
  - id: lesson47
    phase: second
    lesson: 47
    branch: lesson/47
    specs: ["docs/第47节：故障恢复——Master-Slave 副本同步（Loop 原料）.md"]
    depends_on: [lesson45]
    judge:
      command: ["./mvnw", "-q", "verify"]
      timeout_seconds: 1800

  - id: lesson48
    phase: second
    lesson: 48
    branch: lesson/48
    specs: ["docs/第48节：故障恢复——Controller 需求、原理、拆解（Loop 原料）.md"]
    depends_on: [lesson47]

  - id: lesson49
    phase: second
    lesson: 49
    branch: lesson/49
    specs: ["docs/第49节：Sink 投递插件化——HTTP + Kafka（Loop 原料）.md"]
    depends_on: [lesson48]

  - id: lesson50
    phase: second
    lesson: 50
    branch: lesson/50
    specs: ["docs/第50节：可观测体系搭建——指标、日志、链路追踪与健康检查（Loop 原料）.md"]
    depends_on: [lesson49]

  - id: lesson51
    phase: second
    lesson: 51
    branch: lesson/51
    specs: ["docs/第51节：Web 管理台（Loop 原料）.md"]
    depends_on: [lesson50]

  - id: lesson52
    phase: second
    lesson: 52
    branch: lesson/52
    specs: ["docs/第52节：When 打包与部署——TGZ、Docker、Kubernetes 与 CI（Loop 原料）.md"]
    depends_on: [lesson51]
    stage_protected_path_overrides:
      allow_write: [".github/workflows/ci.yml"]

delivery_stages:
  - id: lesson53
    phase: second
    lesson: 53
    kind: delivery
    branch: lesson/53
    specs: ["docs/第53节：Loop 运行演示·联调（第二次跑·完整 HA）.md"]
    depends_on_release_candidate: true
    write_paths:
      - DEPLOY.md
      - USER_GUIDE.md
      - deploy/examples/**
    protected_paths:
      - .github/workflows/**
      - when-acceptance/src/test/**
      - harness/contracts/**

release_candidate_judges:
  - id: second_full_integration
    command: ["harness/release/run-full-integration.sh"]
    owner_stages: [lesson47, lesson48, lesson49, lesson51]
  - id: second_ha_failover
    command: ["harness/release/run-ha-failover.sh"]
    owner_stages: [lesson47, lesson48]
  - id: second_observability
    command: ["harness/release/verify-observability.sh"]
    owner_stages: [lesson50]
  - id: second_release_artifacts
    command: ["harness/release/verify-artifacts.sh"]
    owner_stages: [lesson52]

second_final_judges:
  - id: second_full_regression
    command: ["harness/release/run-full-regression.sh"]
  - id: second_documentation_smoke
    command: ["harness/release/verify-docs.sh"]
  - id: second_release_report_complete
    command: ["harness/release/verify-release-report.sh"]
```

第 47—52 节的精确 `write_paths`、`fingerprint_paths` 和 judge 命令由各节原料中的交付物和 Harness 生成，但不能将整个仓库作为可写范围。第 52 节是唯一可创建或修改 `.github/workflows/ci.yml` 的阶段；它通过后 Runner 保存 CI 指纹，第 53 节再次将 CI 列为受保护路径。

第二阶段还必须配置《第 53 节》定义的四类发布候选 judge 和三类最终 judge。发布候选验收失败时，Runner 根据 judge 所属的责任课程使该课程及下游状态失效，不在 `master` 上直接修代码。

### 6.1 为什么命令使用数组

验收命令使用参数数组，不使用任意 Shell 字符串。Runner 直接创建子进程，避免配置中的 `;`、反引号、变量替换等意外执行。

### 6.2 `write_paths` 和 `fingerprint_paths`

- `write_paths`：当前 AI 允许修改的范围；
- `fingerprint_paths`：阶段通过后用于判断输出是否变化的范围；
- 修改 `write_paths` 外文件时，本轮失败；Runner 让 AI 撤销越界修改后在当前课程分支继续；
- 不同阶段如需修改同一文件，必须在配置中明确声明共享所有权，不能默认放开整个仓库。

### 6.3 `final_judges` 的清理规则

带 `always_run: true` 的清理命令无论前一步成功、失败或中断都要尝试执行。清理失败需要写进最终报告，但不能覆盖最初的失败原因。

---

## 7. 课程状态机

每节课程只能处于以下状态之一：

| 状态 | 含义 |
|---|---|
| `PENDING` | 尚未开始，或前面课程变化后需要重新执行 |
| `READING` | 正在生成上下文清单并检查输入 |
| `RUNNING` | AI 正在当前 lesson 分支修改代码 |
| `VERIFYING` | Runner 正在执行验收命令和边界检查 |
| `COMMITTING` | 验收通过，正在提交 `lesson/{节号}` |
| `MERGING` | 正在以 `--no-ff` 合并回 `master` |
| `PASSED` | 课程提交和 master merge commit 都已保存 |
| `ENVIRONMENT_ERROR` | 宿主机程序、权限、磁盘或 Git 仓库状态导致无法继续 |

正常状态转换：

```text
PENDING → READING → RUNNING → VERIFYING → COMMITTING → MERGING → PASSED
                       ▲           │
                       └── 失败 ───┘
```

不允许：

- AI 直接把课程改为 `PASSED`；
- `PENDING` 跳过验收命令进入 `PASSED`；
- 上一节没有合并到 `master` 就创建下一节分支；
- 在课程分支之外修改业务代码；
- 通过修改验收命令让 `VERIFYING` 成功。

---

## 8. `.loop/state.json` 完整结构

示例：

```json
{
  "schema_version": 1,
  "run_id": "20260817-103015-a81c",
  "status": "RUNNING",
  "source_root": "/path/to/when",
  "base_branch": "master",
  "current_branch": "lesson/44",
  "base_commit": "abc123",
  "master_head": "merge43abc",
  "current_stage": "lesson44",
  "started_at": "2026-08-17T10:30:15+08:00",
  "updated_at": "2026-08-17T11:12:08+08:00",
  "stages": {
    "lesson43": {
      "lesson": 43,
      "branch": "lesson/43",
      "status": "PASSED",
      "attempts": 1,
      "spec_hash": "sha256:...",
      "output_hash": "sha256:...",
      "judge_hash": "sha256:...",
      "protected_hash": "sha256:...",
      "lesson_commit": "lesson43def",
      "merge_commit": "merge43abc",
      "passed_at": "2026-08-17T10:52:00+08:00",
      "handoff": ".loop/runs/20260817-103015-a81c/lesson43/handoff.md"
    },
    "lesson44": {
      "lesson": 44,
      "branch": "lesson/44",
      "status": "VERIFYING",
      "attempts": 2,
      "last_failure_fingerprint": "sha256:...",
      "same_failure_count": 1,
      "last_attempt": ".loop/runs/20260817-103015-a81c/lesson44/attempt-002"
    }
  }
}
```

### 8.1 状态写入必须原子化

每次状态变化使用以下顺序：

1. 在 `.loop/` 同目录写临时文件；
2. 完成 flush 和 `fsync`；
3. 使用原子 rename/replace 替换 `state.json`；
4. 保留上一份有效状态为 `state.json.bak`；
5. 必要时对目录执行 `fsync`。

如果 `state.json` 损坏，Runner 先读取备份，再根据 `master`、`lesson/{节号}` 分支和已有 merge commit 恢复状态。能够确定时直接继续；无法确定时以 `ENVIRONMENT_ERROR` 退出并写明事实，不发起确认，也不能悄悄创建全新 run。

### 8.2 一次尝试保存什么

```text
.loop/runs/{run_id}/lesson44/attempt-002/
├── context-manifest.json
├── agent-request.md
├── agent-result.json
├── changed-files.txt
├── diff.patch
├── judge.stdout.log
├── judge.stderr.log
├── judge-result.json
└── attempt-summary.md
```

日志超过上限时保存头尾和原文件摘要，不能让无限输出撑爆磁盘。

---

## 9. 已完成课程如何跳过

Runner 不只检查 `status=PASSED`。每节必须同时验证：

1. `spec_hash`：当前课程原料及公共输入没有变化；
2. `output_hash`：当前课程负责的代码没有变化；
3. `judge_hash`：验收命令、测试和阈值没有变化；
4. `protected_hash`：受保护文件没有被改写；
5. `lesson_commit`：课程分支提交仍存在；
6. `merge_commit`：对应 merge commit 仍是 `master` 的祖先；
7. 前面课程的 `PASSED` 状态仍然有效。

全部一致才显示：

```text
SKIPPED(PASSED)
```

### 9.1 变更影响范围

| 变化 | 失效范围 |
|---|---|
| 某节原料变化 | 当前课程及后续课程 |
| 第 39 节公共契约变化 | `lesson39`—`lesson45` |
| 项目需求或技术方案变化 | 全部课程失效，从 `lesson39` 重新执行 |
| 当前课程输出代码变化 | 当前课程及后续课程 |
| 验收命令或受保护文件意外变化 | 以 `ENVIRONMENT_ERROR` 退出并报告，不覆盖这些文件 |
| 只有运行日志变化 | 不影响课程状态 |

失效只改变执行状态，不删除历史报告、课程分支或 merge commit。

---

## 10. Runner 如何准备 AI 上下文

每次 AI 调用生成 `context-manifest.json`，列出实际提供的文件、哈希和用途。默认包含：

1. 四份项目基准文档；
2. 第 39 节公共契约；
3. 当前课程原料；
4. 当前课程直接依赖的前面课程交接报告；
5. 第 46 节与当前课程相关的整合边界；
6. 当前课程允许修改的代码和测试；
7. 最近一次失败证据；
8. 当前验收命令和受保护路径清单。

未来课程文档不默认加入上下文。例如第 44 节不读取真实 Sink、Controller rebalance 和管理台原料。

### 10.1 当前课程 Prompt 模板

```text
你正在执行 When 第 {lesson} 节，当前分支是 lesson/{lesson}，这是第 {attempt} 次尝试。

目标：
{stage_goal}

必须阅读：
{spec_paths}
{upstream_handoffs}

允许修改：
{write_paths}

禁止修改：
{protected_paths}

Runner 将执行的验收命令：
{judge_command}

上一轮失败证据：
{last_failure_or_none}

要求：
1. 先只读检查现状，再给出简短计划并实施。
2. 只处理当前阶段，不提前实现未来模块。
3. 不修改、删除、跳过测试或放宽验收。
4. 出现编译、测试、接口理解或实现选择问题时，重新阅读文档和失败证据后直接解决，不向用户提问，不等待确认。
5. 文档未规定细节时，选择满足现有接口和验收的最简单方案，把判断写进结果摘要后继续。
6. 判断顺序是：需求范围 → 技术方案 → 第 39 节公共契约 → 当前课程细节 → 第 46 节运行规则。
7. 完成后报告修改、关键判断和仍存在的客观风险，不要请求用户批准。
8. 不要自行宣布 PASSED；Runner 会执行验收命令。
```

Runner 不把凭据写入 Prompt。Agent 需要的环境变量通过白名单传递，日志输出前统一脱敏。

---

## 11. Agent Adapter 契约

Adapter 隔离不同 AI 产品，Runner 不依赖某个固定 CLI 的输出文本。

当前课程使用 Codex。Adapter 在仓库根目录、当前 `lesson/{节号}` 分支中启动非交互任务，并通过标准输入传入 Runner 生成的提示词。不创建 Worktree。

```bash
codex exec --sandbox workspace-write --json - < agent-request.md
```

实现时应使用进程参数数组，并把 `agent-request.md` 写入子进程标准输入，不要拼接成一整段 Shell 命令。`--json` 输出 JSONL 事件，Adapter 负责解析结束状态与摘要；实际修改范围仍由 Runner 通过 Git diff 检查。不要使用已经废弃的 `--full-auto`，也不要在命令、配置或日志中写入凭据。

### 11.1 Runner 传给 Adapter 的输入

```json
{
  "run_id": "...",
  "stage": "lesson44",
  "lesson": 44,
  "branch": "lesson/44",
  "attempt": 2,
  "workspace": "/absolute/repository/path",
  "prompt_file": "/absolute/path/agent-request.md",
  "context_manifest": "/absolute/path/context-manifest.json",
  "allowed_write_paths": ["when-timewheel/**"],
  "timeout_seconds": 1800
}
```

### 11.2 Adapter 返回给 Runner 的结果

```json
{
  "status": "completed",
  "summary": "实现 NettyTimeWheel 并补齐取消竞态测试",
  "reported_changed_files": ["when-timewheel/..."],
  "decisions": [],
  "risks": [],
  "needs_confirmation": false
}
```

允许的 `status`：

- `completed`：AI 已结束，Runner 继续执行边界检查和验收命令；
- `retryable_failed`：本次没有完成，Runner把摘要和失败证据交给下一次尝试；
- `environment_error`：缺少宿主机程序、权限、磁盘或 Git 仓库异常；
- `failed`：AI 进程异常，Runner仍在当前课程分支重试。

Adapter 不接受 `needs_human_review` 或等待确认状态。Agent 如果返回问题或不确定性，Runner把文档判断顺序补进下一次 Prompt，要求它自行选择并继续。

Runner 不信任 `reported_changed_files`，必须通过 Git diff 自己计算真实修改范围。

---

## 12. 验收命令怎样执行

验收命令必须由 Runner 在仓库根目录、当前 `lesson/{节号}` 分支中执行。

固定步骤：

1. 计算执行前受保护文件哈希；
2. 检查真实 Git diff 是否落在 `write_paths`；
3. 按当前课程的 `services` 启动本机 Redis、ETCD 临时进程并等待就绪；
4. 以参数数组启动验收命令，不拼接任意 Shell；
5. 设置超时、输出上限和明确工作目录；
6. 保存 stdout、stderr、退出码和耗时；
7. 无论成功、失败或中断，都停止本机依赖进程并清理临时目录；
8. 再次计算受保护文件哈希；
9. 检查交付物是否齐全；
10. 全部通过后才允许提交课程分支并合并 `master`。

退出码 0 是必要条件，但不是充分条件。以下任一情况仍然失败：

- AI 修改了当前阶段以外的文件；
- 测试、契约快照或 CI 阈值被修改；
- 必需交付物缺失；
- 出现未提交生成物、敏感日志或越界依赖；
- 当前不在配置指定的 `lesson/{节号}` 分支。

---

## 13. 失败分类与重试算法

### 13.1 普通问题由 Runner 继续处理

下面这些问题都不需要询问学员：

- 编译、单元测试、集成测试、格式或依赖方向检查失败；
- AI 进程临时退出或输出不完整；
- 接口理解有偏差、实现遗漏或当前模块内的设计不完整；
- 当前课程分支中的合并冲突；
- 文档已经给出方向，但实现细节需要做工程取舍。

每次失败后，Runner 按同一套顺序处理：

1. 保存失败命令、错误摘要、Git diff 和失败指纹；
2. 重新读取当前课程原料、相关项目文档和上游交接摘要；
3. 检查当前代码，只修复失败涉及的部分，不重做已经通过的内容；
4. 文档没有写死实现细节时，选择满足接口和验收的最简单方案，并把决定记入 `decisions.jsonl`；
5. 继续使用当前 `lesson/{节号}` 分支，再次执行本节全部验收。

判断依据从高到低依次是：需求文档、技术方案、项目实施指引、第 39 节公共契约、当前课程原料、第 46 节运行规则。低优先级内容不得推翻高优先级约束。

`max_attempts` 表示一批尝试的上限，不表示达到次数后向用户提问。连续失败达到上限时，Runner 整理完整失败历史，重新生成本节上下文，开始下一批尝试；已完成课程不会重跑。

### 13.2 只有运行环境异常才退出

以下情况不是业务实现问题，Runner 无法在课程文档范围内安全解决，应保存现场并以 `ENVIRONMENT_ERROR` 退出：

- `git`、`python3`、`codex`、Java、Maven、`redis-server` 或 `etcd` 等宿主机程序缺失；
- 仓库只读、磁盘空间不足或进程权限不足；
- `master` 被 Runner 之外的进程改动，且无法确认哪些修改属于本次运行；
- 项目文档、课程原料或受保护验收文件缺失、损坏或被外部修改；
- 需要生产凭据、生产权限、远端推送或不可恢复操作。

退出时生成可读报告，不发起确认，也不把环境异常伪装成代码失败。环境修复后再次执行 `./loop run --phase <原 phase>`，Runner 从原状态恢复。

### 13.3 失败指纹

Runner 从以下信息生成 `last_failure_fingerprint`：

- 验收项 ID；
- 失败测试名称；
- 规范化后的首个关键错误；
- 退出码；
- 相关堆栈摘要。

路径、时间戳、随机端口和临时文件名需要规范化，避免同一错误每次生成不同指纹。

### 13.4 退避

AI 进程或外部环境的临时失败可以按 `2s、5s、10s、20s` 退避。确定性的编译和测试失败不需要等待，直接带新证据进入下一次尝试。

---

## 14. 每节课一个分支，完成后合并 master

Runner 直接在当前仓库工作，不创建额外工作目录。`first` 固定使用 `lesson/39`—`lesson/45`；`second` 固定使用 `lesson/47`—`lesson/53`。`lesson/46` 由外层 Codex 任务用于开发 Runner，并在任何 phase 启动前合并 `master`。

每节课严格执行下面的流程：

1. 获得运行锁，确认当前没有第二个 Runner；
2. 切换到 `master`，确认工作区干净且 HEAD 与状态文件记录一致；
3. 从最新 `master` 创建 `lesson/{节号}`；如果分支已存在，则校验后从原进度继续；
4. AI 只在当前课程分支修改代码；
5. 执行文件边界检查、本节验收和必要的回归测试；
6. 全部通过后提交，提交信息使用 `lesson {节号}: {本节交付摘要}`；
7. 切换到 `master`，执行 `git merge --no-ff lesson/{节号} -m "merge lesson {节号}"`；
8. 记录课程提交和合并提交，再从更新后的 `master` 创建下一节分支。

本节未通过时不提交“完成”记录，也不合并 `master`。已有修改留在当前课程分支，下一轮继续修复。

正常情况下不会出现合并冲突，因为每个课程分支都从最新 `master` 创建，而且同一时间只有一个 Runner。若合并时发现冲突，Runner 先中止这次合并，回到当前课程分支，把最新 `master` 合入课程分支，再依据当前课程文档和验收测试解决冲突、重新执行全部验收，最后重新合并。涉及来源不明的外部修改时按 `ENVIRONMENT_ERROR` 退出，绝不覆盖用户代码。

Runner 只创建本地提交和本地合并，不删除课程分支，不 push，不创建 PR，不发布。

---

## 15. 并发锁、崩溃与恢复

### 15.1 运行锁

`./loop run --phase <name>` 启动时获得 `.loop/run.lock`。锁内记录 phase、PID、run ID、主机和启动时间。

- 锁仍被存活进程持有：第二个 Runner 退出；
- PID 不存在：提示发现陈旧锁，验证状态后恢复；
- 不允许两个 Runner 同时操作同一个仓库。

### 15.2 `Ctrl+C`

收到中断信号时：

1. 通知 Adapter 停止当前 AI；
2. 终止或等待当前验收命令；
3. 执行 `always_run` 清理；
4. 原子写入当前状态和最后证据；
5. 释放锁；
6. 以 130 退出。

再次执行相同的 `./loop run --phase <name>` 时，Runner 跳过已经合并到 `master` 且证据仍有效的课程。被中断的课程继续使用原来的 `lesson/{节号}` 分支，保留已经写好的代码，并从本节验收开始判断下一步。

### 15.3 进程或电脑异常退出

恢复时依次验证：

- 状态 JSON 或备份可读取；
- 当前课程分支、课程提交和合并提交存在；
- 当前 diff 属于记录的 lesson；
- 原料、验收命令和受保护文件没有意外变化；
- 已完成课程的合并提交仍然是 `master` 的祖先；
- `master` HEAD 与状态记录能够对应。

如果当前课程分支有未提交修改，Runner 先根据状态文件和 Git diff 判断它是否属于本节；属于本节就继续，不属于本节则以 `ENVIRONMENT_ERROR` 退出并保留现场。Runner 不创建新 run 掩盖旧状态，也不请求用户确认。

---

## 16. 如何判定 phase 完成

Runner 必须重新跨过四道门：

1. 选中 phase 的所有课程全部通过，每节课程提交和合并提交都可追溯；
2. 阶段验收命令均有退出码 0 的证据，且边界检查通过；
3. 当前分支是 `master`，工作区干净，所有课程合并提交都位于 `master`；
4. 从最终代码重新执行所有 `final_judges`，不复用阶段缓存；
5. 停止本机 Redis、ETCD 临时进程并删除本次临时数据目录；
6. 生成最终报告并释放锁。

成功输出示例：

```text
FIRST PHASE COMPLETE
phase: first
run_id: 20260817-103015-a81c
lessons: 7/7 passed
final_judges: 4/4 passed
report: .loop/runs/20260817-103015-a81c/final-report.md
```

以下情况不能显示完成：

- 只有 AI 回复“已完成”；
- 阶段测试通过但最终端到端失败；
- 本机 Redis、ETCD 临时进程或数据目录没有清理；
- 受保护文件发生变化；
- 某节课程尚未通过，或课程分支尚未合并 `master`；
- 报告或证据缺失。

---

## 17. CLI 退出码

| 退出码 | 含义 |
|---:|---|
| 0 | 命令成功；对 `run` 而言表示选中 phase 已输出对应的 `PHASE COMPLETE` |
| 2 | Loop 尚未完成，Runner 已保存当前课程进度 |
| 3 | 配置、schema、阶段依赖或路径校验失败 |
| 4 | Git 基线、课程分支、合并记录或文件边界异常 |
| 5 | Agent Adapter 无法启动、超时或返回非法结果 |
| 6 | 验收命令最终失败或超时 |
| 7 | 宿主机程序、权限、磁盘、文档或仓库环境异常 |
| 10 | Runner 内部错误或状态损坏 |
| 130 | 用户中断 |

错误输出第一行必须可读，例如：

```text
ENVIRONMENT_ERROR lesson=44 reason="etcd executable not found"
```

---

## 18. 日志与安全

### 18.1 必须脱敏

至少识别并替换：

- API Key、Token、密码；
- Redis/ETCD 带密码连接串；
- Authorization、Cookie 等 Header；
- When 消息 payload；
- 私钥、证书内容和云凭据。

### 18.2 环境变量

Adapter 只继承 `environment_allowlist`。验收命令需要的测试凭据由测试环境注入，不写入状态、Prompt、报告或 Git。

### 18.3 路径安全

- 所有配置路径先解析成绝对路径并验证仍位于允许根目录；
- 拒绝通过 `..`、符号链接或大小写差异逃逸 `write_paths`；
- 不允许以仓库根、用户主目录或 `/` 作为清理目标；
- 临时目录使用安全创建方式，清理前再次验证实际路径。

### 18.4 命令安全

- 验收命令使用参数数组；
- 不执行来自 AI 输出的任意命令字符串；
- AI 可以建议命令，但只有 Runner 配置中的验收命令才会自动执行；
- 生产部署、push、发布和不可恢复操作永远需要另行授权。

---

## 19. Runner 自身必须具备的测试

### 19.1 配置

- 合法 `loop.yaml` 通过；
- 未知字段、重复 ID、环依赖、缺失文档和缺少验收命令时失败；
- `write_paths` 越界或冲突失败；
- 验收命令中出现非法命令格式失败。

### 19.2 状态与恢复

- 状态原子写入；
- 主状态损坏时识别备份，不静默开新 run；
- `Ctrl+C` 后保存状态、清理并返回 130；
- 陈旧锁识别；
- 两个 Runner 不能同时运行。

### 19.3 重试

- 验收命令失败只重试当前课程；
- 已合并 `master` 的课程不重复调用 Agent；
- 相同失败达到一批尝试的上限后，能够带完整失败历史重新生成上下文；
- 新一批尝试仍在原课程分支继续，不丢失已完成代码；
- 普通实现问题不会进入等待确认状态。

### 19.4 指纹与失效

- 原料变化使当前及下游失效；
- 输出代码变化使当前及下游失效；
- 公共契约变化使全部依赖阶段失效；
- 验收命令或受保护文件被外部修改时以 `ENVIRONMENT_ERROR` 退出并保留现场；
- 只有日志变化不触发失效。

### 19.5 边界与安全

- AI 越界修改被拒绝；
- 符号链接逃逸被拒绝；
- 日志敏感字段被脱敏；
- 超大输出被截断并保留摘要；
- `always_run` 清理在失败和中断时都执行。

### 19.6 完成判定

- 选中 phase 的所有课程通过但最终验收失败时不能 COMPLETE；
- 任一课程分支没有合并 `master` 时不能 COMPLETE；
- 受保护文件变化时不能 COMPLETE；
- 最终报告缺失时不能 COMPLETE；
- 全部证据满足时返回 0，并输出 `FIRST PHASE COMPLETE` 或 `SECOND PHASE COMPLETE`。

---

## 20. 唯一提示词的完整执行过程

### 20.1 从空项目建立运行环境

确认 `docs/` 中已有四份项目文档和课程资料，然后把本手册第 1 节的唯一提示词交给 Codex。Codex 先在 `lesson/46` 中完成以下检查：

- `AGENTS.md` 是否只整理已有文档约束；
- `harness/contracts/` 是否只有外部验收逻辑，没有业务实现；
- `loop.yaml` 是否覆盖两个 phase，第 39—45 节、第 47—52 节与第 53 节交付关系是否正确；
- `loop` 是否只是薄 Bash 入口；
- Runner 自身测试是否通过；
- 本地 Git 是否已有干净的基线提交；
- Runner 自身测试、`./loop validate`、`./loop plan --phase first` 和 `./loop plan --phase second` 是否全部通过。

这些检查通过后，Codex 提交 `lesson/46` 并以 `--no-ff` 合并 `master`。开发 Runner 的这一步不出现 When 业务源码。

### 20.2 Codex 自动预检

```bash
./loop validate
./loop plan --phase first
./loop plan --phase second
```

Codex 确认 `first` 依次为第 39—45 节，`second` 依次为第 47—52 节、发布候选验收和第 53 节交付，并确认第一阶段所需的本机 Redis、ETCD 可启动。它不会在这里停下来等待学员确认。

### 20.3 Codex 直接启动

```bash
./loop run --phase first
```

这是唯一提示词中的连续动作，不是学员需要补发的第二条指令。

终端持续显示：

```text
RUN  20260817-103015-a81c
PASS lesson39 branch=lesson/39 merged=master attempt=1 judge=18s
PASS lesson40 branch=lesson/40 merged=master attempt=2 judge=42s
PASS lesson41 branch=lesson/41 merged=master attempt=1 judge=31s
RUN  lesson42 branch=lesson/42 attempt=1
```

### 20.4 Codex 查看失败并继续

```bash
./loop status --phase first
./loop logs --stage lesson44
```

Runner 自动把验收结果交给下一次 AI。外层 Codex 任务持续观察状态；普通实现失败不需要人执行额外命令，也不会弹出确认问题。

### 20.5 中断后由同一个任务恢复

如果子进程意外中断但环境仍可用，Codex 再次执行：

```bash
./loop run --phase first
```

预期：已经合并 `master` 的课程显示 `SKIPPED(PASSED)`；未完成课程继续使用原来的 `lesson/{节号}` 分支。

### 20.6 文档没有写全时

Runner 重新读取项目文档、当前课程原料和验收结果，按照本手册第 13 节的优先级自行判断。没有明确规定的实现细节，选择满足现有接口与测试的最简单方案，并把决定写入运行记录。只有宿主机程序缺失、仓库异常或文档损坏等运行环境问题才退出。

### 20.7 最终完成

```bash
./loop status --phase first
./loop report --phase first
```

预期 `status` 显示 `FIRST PHASE COMPLETE`，报告列出 `lesson/46` 的 Runner 提交与合并、第 39—45 节的课程提交与合并，以及第一阶段最终验收结果。

---

## 21. 常见问题与排查

| 现象 | 先看哪里 | 处理 |
|---|---|---|
| `./loop` 不存在 | `lesson/46` 的 Runner 创建步骤 | Codex 在同一个任务中完成 Runner，不能跳过这一步直接运行不存在的命令 |
| `./loop` 存在但不可执行 | 根目录文件权限 | 执行 `chmod +x loop`，再运行 `./loop validate` |
| validate 报阶段有环 | `loop.yaml depends_on` | 修正依赖图，不能强制跳过 |
| AI 已说完成但 Runner 仍失败 | 当前课程的验收日志 | 以验收命令为准，把证据交给下一次尝试 |
| 每次从头运行 | `.loop/state.json`、课程提交、合并提交和四项指纹 | 查状态是否损坏、输出或验收命令是否变化 |
| 某节重复相同错误 | failure fingerprint、`decisions.jsonl` | 由 Runner 汇总完整失败历史，重建上下文后继续当前课程 |
| PASSED 课程突然失效 | `./loop status --json` 的 invalidation reason | 检查原料、输出、验收命令或上游契约变化 |
| Runner 提示越界修改 | `changed-files.txt` | 回退越界文件，让 AI 只修改当前 `write_paths` |
| 验收命令一直超时 | 验收日志、本机依赖进程、CPU/内存 | 先排除进程未就绪和端口冲突，不能直接放宽阈值 |
| 中断后分支状态不一致 | `master`、当前 `lesson/{节号}`、状态文件 | 保留现场并以 `ENVIRONMENT_ERROR` 退出，不覆盖现有代码 |
| Redis 或 ETCD 没有关闭 | `.loop/runtime.env`、进程 PID、清理日志 | Runner 执行停止脚本，只清理本次运行创建的进程和临时目录 |

---

## 22. 实现顺序建议

Runner 本身建议按以下顺序开发：

1. schema 和 `validate`；
2. 阶段拓扑和 `plan`；
3. 状态原子写入、锁和 `status`；
4. 课程分支、提交和合并记录；
5. Agent Adapter；
6. 单节课程 AI → 验收命令 → 失败重试 → 合并 `master` 流程；
7. 指纹、跳过和下游失效；
8. 中断恢复；
9. 最终验收和报告；
10. 安全、脱敏和完整自测。

不要先实现“无限循环调用 AI”，再补状态和验收命令。Runner 的第一职责是可控和可恢复，调用 AI 只是其中一步。

---

## 23. 本手册的完成检查表

### 使用层

- [ ] 能从只有四份项目文档和课程资料的空项目建立完整运行环境；
- [ ] 学员只向 Codex 提交一个提示词；
- [ ] 同一个 Codex 任务先创建 Runner，再自行执行 `./loop run --phase first`，不需要第二个提示词；
- [ ] 所有命令帮助信息与本手册一致；
- [ ] 首次运行、中断恢复、自动解决问题和最终报告都有完整示例。

### 实现层

- [ ] `lesson/46` 开发 Runner 时没有生成任何 When 业务源码；
- [ ] 已建立干净的本地 `master` 基线提交，且没有配置或推送远端；
- [ ] Runner 先在 `lesson/46` 创建、测试并合并 `master`；
- [ ] `first` 使用 `lesson/39`—`lesson/45`，`second` 使用 `lesson/47`—`lesson/53`，每节都从最新 `master` 创建；
- [ ] 每个业务课程通过后提交并以 `--no-ff` 合并 `master`，再开始下一节；
- [ ] 未创建额外工作目录；
- [ ] 未调用容器工具；Redis、ETCD 只以本机临时进程运行并自动清理；
- [ ] 根目录 `loop` 是薄 Bash 入口，主要逻辑位于 Python Runner；
- [ ] Agent Adapter 使用 Codex 非交互模式，通过标准输入传入阶段提示词；
- [ ] `loop.yaml` 经过严格 schema 校验；
- [ ] 状态写入原子化并有备份；
- [ ] 同一时间只有一个活动 Runner 和一个活动课程；
- [ ] Agent Adapter 输入输出稳定、可替换；
- [ ] AI 修改范围和受保护文件可自动验证；
- [ ] 验收命令由 Runner 执行并保留证据；
- [ ] 重试、环境异常退出、指纹、失效和恢复规则全部实现；
- [ ] 普通实现问题由 Runner 根据文档和失败证据继续处理，不等待确认；
- [ ] Runner 不 push、不发布、不访问生产环境。

### 验收层

- [ ] 已完成且合并 `master` 的课程经过指纹检查后可以直接跳过；
- [ ] 当前课程失败不会导致从头重跑；
- [ ] 规格或公共契约变化能正确传播失效；
- [ ] 最终端到端验收重新执行；
- [ ] 只有证据齐全时输出对应的 `FIRST PHASE COMPLETE` 或 `SECOND PHASE COMPLETE`；
- [ ] 最终报告可以完整追溯本次运行。

> 做到这些，Loop 才不是“让 AI 一直试”，而是一套按课程推进、有状态、有自动验收、能恢复、能合并、能证明完成的工程执行系统。
