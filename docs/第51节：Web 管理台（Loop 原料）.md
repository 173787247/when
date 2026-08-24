# 第 51 节：Web 管理台（Loop 原料）

> 本节交付两部分：HTTP API 和 Vue 运维控制台。HTTP API 分为业务消息接口 `/api/v1` 和管理接口 `/admin/v1`；控制台只调用管理接口。
>
> 十一段模板，引用第 42（ETCD 元数据）+ 45（接入/查询）+ 48（Controller 创建时间轮）+ 50（可观测体系）。

---

## 1. 这一节做什么

一句话：**提供业务 HTTP API 和管理 HTTP API，并实现使用管理 API 的 Vue 运维控制台。**

业务 HTTP API 向调用方提供 Submit、Query、Cancel；管理 API 提供时间轮、消息和集群视图。控制台是独立 Web 应用，通过管理 API 查看时间轮、创建时间轮、筛选消息和查看集群节点。

---

## 2. 为什么这么设计

**为什么在本节提供 HTTP API。**

![图 1　管理台经 admin HTTP API 和 When 通信，独立部署、不耦合](第51节-图1-管理台架构.svg)

第 40 节提供 gRPC 传输契约，第 45 节提供消息操作的应用处理接口。本节把这些能力映射为 HTTP：业务路由调用第 45 节的 Submit、Query、Cancel；管理路由读取 ETCD 元数据并调用 Controller。HTTP 层只处理协议转换、参数映射和错误码映射，不重复实现业务流程。

**为什么后端固定使用 Spring Boot + Spring MVC。** When 的生产代码使用 Java，HTTP 层主要是短请求、参数校验和领域服务调用，不需要再引入一套响应式编程模型。本节统一使用 Spring Boot、Spring MVC、Bean Validation 和 Jackson；通过 `@RestControllerAdvice` 处理异常，通过请求过滤器建立 `request_id` 与 Trace 上下文。除非整个项目以后统一迁移为响应式调用链，否则不使用 WebFlux。

**为什么先定 OpenAPI，再写前后端。** HTTP 契约保存在版本化的 OpenAPI 文件中。Maven 根据它生成 Spring 接口和 DTO，前端根据同一文件生成 TypeScript 类型与客户端。后端实现生成的接口，前端不再手写另一套请求类型，避免字段、枚举和错误结构各写各的。

**为什么前端固定使用 Vue3 + Element Plus。** 上手快、组件齐、打包简单，适合 MVP 阶段快速做出可用界面；对中小团队/单人项目友好（技术方案 §12.1）。本阶段不再保留 React 等并行选项，避免 Loop 自行更换前端技术栈。

**为什么"创建时间轮"是控制台功能。** 时间轮的增减是运维动作。控制台发一个创建请求，交给 Controller（第 48 节）分配 Master/Slave 节点、写入 ETCD 元数据。运维不必手动改 ETCD。

**为什么管理台不与服务端耦合。** 编译成静态文件，放 Nginx/CDN/对象存储都行，跨域用 CORS 或反向代理。它只依赖 API 契约，不依赖 When 内部实现，When 升级不影响它（技术方案 §12.1）。

**消息列表从哪里查。** 第 41 节的消息 key 适合按 `message_id` 查询和按时间轮恢复，不适合直接列出全量消息。管理 API 不能对 Redis 执行全库 `KEYS`，也不能每翻一页就 `SCAN` 全库。本节增加一个只用于管理查询的分桶索引：按小时和固定分片保存 `deliver_at → message_id`，单个桶达到上限后继续分片。查询先按时间范围读取候选 ID，再批量读取消息并按状态、标签过滤。这个索引不是调度依据，短暂更新失败不影响投递，但必须有后台修复任务。

---

## 3. 它和 Loop 的关系

本节作为 Loop 输入，产出 HTTP API（后端）和 Vue 控制台（前端）两部分。

- **明确输入**：功能↔API 映射、统一响应、HTTP 状态与错误码、Spring MVC 实现边界、核心页面状态、API 字段和管理查询索引（第 5、6、7 节）。
- **自动化验收**：第 8 节——业务和管理 API 符合 OpenAPI，成功与失败响应一致，创建时间轮后可查询到对应元数据，页面能够查询、筛选和创建。
- **边界**：第 9 节——只做控制台 + admin API，不改核心业务逻辑。

---

## 4. 依赖与被依赖

**本节依赖（上游）**

- 第 45 节：查询消息、取消（admin API 映射到它）。
- 第 42 节：`/when/timewheels` 元数据（查看时间轮）。
- 第 48 节：`Controller.createTimeWheel`（创建时间轮）。
- 第 50 节：结构化日志、Request ID、Trace 和 Metrics（接口观测与控制台概要）。
- 第 43 节：集群节点视图。

**本节产出、被谁消费（下游）**

| 本节产出 | 被哪节消费 | 怎么用 |
|---|---|---|
| 业务 HTTP API | 第 53 节联调、业务调用方 | HTTP 提交、查询和取消消息 |
| admin HTTP API | 第 53 节联调、管理台 | 查看消息、时间轮和集群，创建时间轮 |
| Vue 控制台（静态） | 第 53 联调（看页面） | 打开页面看时间轮、消息、集群 |
| 时间轮/消息的只读视图 | 运维 | 日常巡检 |

---

## 5. 功能与交互

**功能 ↔ API 映射**：

![图 2　管理台功能 ↔ admin HTTP API 映射](第51节-图2-功能与API.svg)

- **业务消息操作**：`POST /api/v1/messages`、`GET /api/v1/messages/{id}`、`DELETE /api/v1/messages/{id}` → 调用第 45 节应用处理接口。
- **查看时间轮**：`GET /admin/v1/timewheels` → 读 ETCD `/when/timewheels/*` 和消息数。
- **创建时间轮**：`POST /admin/v1/timewheels` + `Idempotency-Key` → 调 `Controller.createTimeWheel` → 返回分配结果。
- **查看延时消息**：`GET /admin/v1/messages?status=&tag=&sink_type=&from=&to=&cursor=&limit=` → 列表和筛选结果。
- **消息详情/投递记录**：`GET /admin/v1/messages/{id}` → 状态、时间和最近 20 次投递尝试；每次只返回清理后的结果码与错误摘要。
- **集群节点**：`GET /admin/v1/cluster/nodes` → 返回第 43 节维护的节点列表。

**管理查询索引**：消息创建成功后，异步写入有界 ZSet `when:admin:idx:{yyyyMMddHH}:{shard}:{part}`，score 是 `deliver_at`，member 是 `message_id`。`shard=hash(message_id)%16`，每个 part 最多 10,000 条，索引按终态保留期设置 TTL。查询默认最近 24 小时，最大跨度 7 天，每页最多 100 条。API 使用游标翻页，避免页码越大查询越慢。

**索引修复**：写管理索引失败时记录指标和待修复任务。后台任务按时间轮的小 key 索引补写最近时间窗口。管理页面需要提示“列表可能延迟”，但按 ID 查询始终直接读取消息主记录。管理索引绝不能参与提交、调度、取消或故障恢复。

**核心页面**：

- **任务列表页**：顶部筛选（状态/时间/sink_type/business_tag）+ 表格（message_id/状态/创建/到期/sink/操作）+ 游标分页。
- **消息详情页**：消息基本信息 + 最近 20 次投递时间线（尝试时间、结果、稳定错误码、清理后的错误摘要）。
- **时间轮与集群页**：查看 Master/Slave、同步状态、分配版本、消息数量和节点就绪状态；创建时间轮前显示数量和分配预览。

所有页面都要有加载中、空数据、失败和数据可能延迟四种状态。请求失败不能只在控制台打印错误；取消消息、创建时间轮等写操作必须禁用重复点击，并显示服务端返回的 `request_id`，便于查日志和 Trace。

**Request ID 与幂等键分开**：`X-Request-Id` 只用于一次 HTTP 请求的排障关联，由服务端校验或生成，并在响应头、响应体、日志中原样返回；`Idempotency-Key` 用于创建时间轮等写操作的业务幂等。二者不能共用一个字段，重试同一业务操作时可以换 Request ID，但必须保留相同的 Idempotency Key。

---

## 6. HTTP 接口与返回契约

### 6.1 服务端实现约束

- 后端使用 Spring Boot + Spring MVC，不使用 WebFlux。
- `when-api` 提供业务 HTTP Controller，`when-admin-api` 提供管理 Controller，二者在 `when-app` 中组合启动；Vue 前端仍是独立静态应用。
- Bean Validation 负责字段级校验，Jackson 统一使用 `snake_case`；请求中的未知字段、非法枚举和格式错误必须明确返回 400。
- `@RestControllerAdvice` 是唯一异常转换入口；Controller 内不重复写 try/catch，也不能把 Java 异常直接序列化给客户端。
- `OncePerRequestFilter` 处理 `X-Request-Id`、OpenTelemetry Context 和结构化访问日志。合法 Request ID 只允许 `[A-Za-z0-9._-]`，最长 64 个字符；缺失或非法时重新生成。
- `openapi/when-v1.yaml` 是 HTTP 契约的唯一来源。Maven 生成 Spring 接口和 DTO，前端生成 TypeScript 类型与客户端；生成代码不手工修改。

### 6.2 统一响应格式

除 `/metrics`、`/health`、`/ready` 和静态资源外，所有 `/api/v1`、`/admin/v1` 接口都返回同一个外壳：

```json
{
  "code": "OK",
  "message": "success",
  "request_id": "req_01K...",
  "data": {}
}
```

字段约定：

| 字段 | 类型 | 规则 |
|---|---|---|
| `code` | string | 稳定枚举；成功固定为 `OK`，失败使用第 6.5 节错误码 |
| `message` | string | 给人阅读，可以优化措辞，前端不能依赖它判断逻辑 |
| `request_id` | string | 与响应头 `X-Request-Id` 一致，并写入日志和 Trace |
| `data` | object / array / null | 成功时为接口数据；失败时为 `null` 或有限的结构化校验信息 |

分页结果统一放进 `data`：

```json
{
  "code": "OK",
  "message": "success",
  "request_id": "req_01K...",
  "data": {
    "items": [],
    "next_cursor": "opaque_cursor",
    "has_more": true,
    "index_updated_at": 1787040000000
  }
}
```

`next_cursor` 没有下一页时为 `null`，`has_more` 必须与它一致。客户端不能解析或修改 cursor。

### 6.3 接口、HTTP 状态与 data

| 接口 | 成功状态 | `data` |
|---|---:|---|
| `POST /api/v1/messages` | 201 | `{message_id,status,created_at}` |
| `GET /api/v1/messages/{id}` | 200 | 消息查询 DTO |
| `DELETE /api/v1/messages/{id}` | 200 | `{message_id,status,canceled_at}` |
| `GET /admin/v1/timewheels` | 200 | `{items:[...]}` |
| `POST /admin/v1/timewheels` | 201 | `{created:[...],assignments:[...]}` |
| `GET /admin/v1/messages` | 200 | `{items,next_cursor,has_more,index_updated_at}` |
| `GET /admin/v1/messages/{id}` | 200 | 消息详情和最近 20 次投递记录 |
| `GET /admin/v1/cluster/nodes` | 200 | `{items:[...]}` |

Submit 只有在 Redis 持久化成功后才能返回 201，并设置 `Location: /api/v1/messages/{message_id}`。Cancel 返回取消后的状态，不使用 204；已经是 `CANCELED` 时返回原结果，已经进入不可取消终态时返回 409。

### 6.4 查询、分页与幂等

- `GET /admin/v1/messages?status=&tag=&sink_type=&from=&to=&cursor=&limit=` 使用不透明游标；`limit` 默认 50、最大 100。
- `from/to` 表示 `deliver_at` 范围，使用 Unix 毫秒；默认最近 24 小时，最大跨度 7 天。超出范围返回 400，不执行大范围扫描。
- `POST /admin/v1/timewheels` 请求体为 `{count}`，`count` 范围为 1—100；幂等键来自 `Idempotency-Key` Header，格式与 Request ID 相同但最长 128 个字符。
- Controller 把幂等记录保存到 ETCD `/when/operations/idempotency/timewheel-create/{sha256(key)}`，内容包含请求摘要、执行状态和结果，保留 24 小时。相同 Key 与相同请求体返回首次 HTTP 状态和响应，并增加 `Idempotency-Replayed: true`；相同 Key 配不同请求体返回 409 `IDEMPOTENCY_KEY_CONFLICT`。
- `X-Request-Id` 是排障标识，不承担幂等职责。一次业务重试可以产生新的 Request ID，但必须复用原 Idempotency Key。
- 时间字段统一为 Unix 毫秒，OpenAPI 使用 `integer/int64`；字段名保持 `created_at/deliver_at/canceled_at`，不能混入秒或 ISO 字符串。

### 6.5 HTTP 状态与错误码

服务端必须同时使用正确的 HTTP 状态和稳定的业务错误码，禁止所有情况都返回 200。

| HTTP | `code` | 适用场景 |
|---:|---|---|
| 400 | `INVALID_ARGUMENT` | 字段缺失、长度或数值范围错误 |
| 400 | `INVALID_JSON` | JSON 语法、未知字段或枚举解析失败 |
| 400 | `INVALID_TIME_RANGE` | 时间范围非法或超过 7 天 |
| 400 | `INVALID_CURSOR` | cursor 非法、过期或与筛选条件不匹配 |
| 404 | `MESSAGE_NOT_FOUND` | 消息不存在 |
| 404 | `TIMEWHEEL_NOT_FOUND` | 时间轮不存在 |
| 409 | `MESSAGE_STATE_CONFLICT` | 当前消息状态不允许取消或修改 |
| 409 | `IDEMPOTENCY_KEY_CONFLICT` | 同一幂等键对应了不同请求体 |
| 409 | `TIMEWHEEL_OPERATION_CONFLICT` | 创建或分配操作与当前状态冲突 |
| 503 | `NODE_NOT_READY` | 当前节点尚未完成初始化或角色恢复 |
| 503 | `REDIS_UNAVAILABLE` | Redis 暂时不可用 |
| 503 | `ETCD_UNAVAILABLE` | ETCD 暂时不可用 |
| 503 | `CONTROLLER_UNAVAILABLE` | 暂时找不到可处理管理命令的 Controller |
| 500 | `INTERNAL_ERROR` | 未分类服务端异常 |

错误响应仍使用统一外壳：

```json
{
  "code": "INVALID_TIME_RANGE",
  "message": "查询时间范围不能超过 7 天",
  "request_id": "req_01K...",
  "data": {
    "field": "from,to",
    "max_range_days": 7
  }
}
```

`data` 只能包含调用方可以安全使用的有限信息，不得包含堆栈、类名、Redis/ETCD 地址、SQL/Key、payload、完整 Sink 配置或内部异常文本。未预期异常由 `@RestControllerAdvice` 记录完整服务端日志，响应固定为 500 `INTERNAL_ERROR`。

### 6.6 OpenAPI 与兼容性

- OpenAPI 必须定义统一响应、全部错误码、枚举、格式、最大长度、HTTP Header 和示例。
- OpenAPI 必须把 `X-Request-Id`、`Idempotency-Key`、`Idempotency-Replayed` 和 `Location` 定义到对应接口，不能只写在讲义里。
- 请求 Schema 使用 `additionalProperties: false`，防止字段拼错后被静默忽略。
- 后端契约测试验证运行时响应符合 OpenAPI；前端构建验证生成客户端未过期。
- v1 已发布字段不能改变语义或类型；新增可选字段允许向后兼容，删除字段、修改枚举含义或把可选改为必填必须升级 API 版本。

---

## 7. 字段 / 页面元素

**任务列表页字段**：message_id、status、created_at、deliver_at、sink_type、business_tag、操作（详情/取消）。
**消息详情页**：message_id、status、created_at、deliver_at、sink_type、business_tag；时间线包含 attempt_id、started_at、finished_at、result、error_code，最多 20 条。
**时间轮页**：tw_id、master、slave、status、sync_state、assignment_version、message_count。

**HTTP DTO 约束**：请求/响应 DTO 与领域模型分开；Controller 不能直接返回 Redis 实体、ETCD 数据结构或内部异常。字段映射集中在 Mapper 中，状态和 Sink 类型使用 OpenAPI 枚举。

约定：时间用 Unix 毫秒，前端 date picker 转换；状态用枚举直传；列表不返回 payload、完整目标地址和 Sink 密钥。投递记录只保留最近 20 次，和消息主记录使用相同 TTL。

---

## 8. 验收标准 + 必须有的测试（自动化验收）

**功能验收**

- [ ] 所有业务和管理接口使用统一 `{code,message,request_id,data}` 外壳，并返回契约规定的 HTTP 状态。
- [ ] 响应头 `X-Request-Id` 与响应体一致；结构化日志和 Trace 可以用该 ID 定位。
- [ ] `GET /admin/v1/timewheels` 返回时间轮列表，字段含 master/slave/status/assignment_version/message_count。
- [ ] `POST /admin/v1/timewheels` 经 Controller 创建时间轮；同一个 `Idempotency-Key` 和请求体重复提交不会重复创建。
- [ ] `GET /admin/v1/messages` 支持按 status/tag/sink_type/时间筛选和游标分页，不使用 Redis `KEYS` 或全库 `SCAN`。
- [ ] `GET /admin/v1/messages/{id}` 返回状态、最近错误和最多 20 次投递记录，不返回 payload 和敏感配置。
- [ ] 控制台任务列表、消息详情、时间轮和集群页面能正常渲染、筛选和翻页，并处理加载、空数据、失败与数据延迟状态。
- [ ] 管理台可独立部署（静态文件），经 CORS/反代访问 API，不与服务端同进程。
- [ ] 管理查询索引写入失败不影响消息投递，后台修复后列表可以查到该消息。
- [ ] 服务端使用 Spring MVC、Bean Validation、Jackson 和全局异常处理；没有 Controller 自行拼接错误响应。

**必须有的测试**

- [ ] API 集成测试：业务与管理端点的 HTTP 状态、统一响应、Header 和 data Schema 正确。
- [ ] 错误映射测试：逐项覆盖第 6.5 节错误码；未知异常固定返回 500 `INTERNAL_ERROR`，响应不含堆栈和内部地址。
- [ ] 参数校验测试：缺字段、未知字段、非法枚举、超长字段、错误 JSON、非法时间范围和非法 cursor 都返回确定的 400 错误码。
- [ ] Request ID 测试：合法上游 ID 沿用，缺失或非法时重新生成，响应头、响应体、日志与 Trace 一致。
- [ ] 创建时间轮端到端：POST → Controller 分配 → GET 能查到新时间轮。
- [ ] 幂等测试：相同 `Idempotency-Key` + 相同 body 返回首次状态和结果并带 replay Header；相同 Key + 不同 body 返回 409；不同 Request ID 不影响幂等结果；Controller 切换后仍能从 ETCD 读取幂等记录。
- [ ] 游标分页测试：跨小时、跨分片连续翻页，没有重复或遗漏；非法/过期 cursor 返回明确错误。
- [ ] 索引修复测试：故意让索引写入失败，确认业务提交成功，修复任务运行后管理列表可见。
- [ ] OpenAPI 契约测试：Spring 接口、DTO、运行时响应和前端生成客户端与 `openapi/when-v1.yaml` 一致，生成文件没有过期。
- [ ] 前端组件测试：四种页面状态、筛选、翻页、重复点击保护和服务端错误提示。

---

## 9. 边界（本节不做什么）

- **不改**核心业务逻辑——HTTP API 是协议转换层，委托第 45 节应用服务、ETCD 查询和 Controller。
- **不做**应用内用户、角色、Token 和权限管理。按照需求文档，MVP 管理 API 只允许部署在可信内网；需要身份认证时由部署侧身份代理完成。未经过网络隔离或身份代理时禁止公网暴露。
- **不做**无限投递历史；每条消息只保留最近 20 次投递记录。
- **只做**：业务 HTTP API、admin HTTP API 和 Vue 控制台。
- **停止条件**：功能↔API 全通、创建时间轮生效、页面可用、独立部署验收全部通过。

---

## 10. 专属 Harness（叠加在公共 Harness 之上）

**代码**

- 后端 admin HTTP 层放 `api/admin`，协议转换层只做映射，不写业务逻辑。
- 业务与管理 Controller 实现 OpenAPI 生成的 Spring 接口；DTO、Mapper、异常映射分别放置，不直接返回领域对象。
- `@RestControllerAdvice` 统一完成领域异常 → HTTP 状态/错误码映射；`OncePerRequestFilter` 统一处理 Request ID、Trace 和访问日志。
- 管理查询经 `AdminQueryService`，使用分桶、分片、带 TTL 的只读索引；禁止 Redis `KEYS` 和无边界 `SCAN`。
- 时间轮创建幂等记录由 Controller 通过 ETCD 事务写入，必须在 Controller 切换后仍然有效，并由有界清理任务处理过期记录。
- 前端独立工程，产物为静态文件；只通过 API 契约依赖后端。

**安全**（强制约束）

- admin API 不回显 payload 原文、不回显 sink_config 敏感值；跨域白名单化，不用 `*`。
- MVP 不在应用内读取或校验管理令牌；可信内网和部署侧身份代理是管理 API 的访问边界。日志不得记录代理传入的认证凭据。
- 创建时间轮要求 `Idempotency-Key` 并做服务端幂等处理；Cancel 按消息状态保证幂等；前端同时禁用重复点击。`request_id` 只用于排障。

**依赖**

- 后端使用 Spring Boot、Spring MVC、Bean Validation、Jackson、OpenAPI Generator，并复用已有领域服务、gRPC/ETCD/Controller 客户端；前端使用 Vue3 + Element Plus 和 OpenAPI 生成客户端。

---

## 11. 交付物清单（这节结束应产出）

- [ ] 业务 HTTP API（Submit、Query、Cancel）。
- [ ] admin HTTP API（timewheels 查/建、messages 查/详情、cluster/nodes）及 OpenAPI 定义。
- [ ] `openapi/when-v1.yaml`、统一响应 DTO、分页 DTO、完整错误码表、生成的 Spring 接口和 TypeScript 客户端。
- [ ] Spring MVC Controller、Bean Validation、DTO Mapper、`@RestControllerAdvice` 和 Request ID/Trace Filter。
- [ ] `AdminQueryService`、分桶索引、游标分页和索引修复任务。
- [ ] 每条消息最近 20 次投递记录的查询与脱敏。
- [ ] Vue3 + Element Plus 控制台：任务列表、消息详情、时间轮和集群页面。
- [ ] 独立部署配置（静态产物 + CORS/反代说明）。
- [ ] API 状态/响应、错误映射、参数校验、Request ID、OpenAPI 契约、游标分页、索引修复、Idempotency-Key 和前端组件测试。
- [ ] 本文档第 4—11 节作为该模块的 Loop 输入规格。

> 完成本节后，业务方可以通过 HTTP 操作消息，运维人员可以通过管理台查看消息、时间轮和集群状态。下一节提供部署和 CI 配置。
