---
workflow_version: 2
plan_id: plan_9.hotfix_3
type: hotfix
parent_plan: plan_9
status: ready
owner: parent-agent
created: 2026-06-19
updated: 2026-06-19
---

# plan_9.hotfix_3 — HTTP API 契约边界收口

## 背景与目标

`plan_9` 已完成 `crag-api`、公开 API 包和统一异常边界，但当前实现仍有两处与现行约束不一致：

- HTTP DTO 仍横向堆在 `ai.cerbur.crag.api.dto.request`，没有按 `rag`、`query` 业务能力组织。
- `ResponseCode` 只保存整数，`BAD_REQUEST` 同时覆盖 Bean Validation 与程序化非法参数，HTTP 状态仍散落在异常处理器中。

本 Hotfix 收口当前已经存在的 API 契约：建立稳定业务码与 HTTP 状态元数据、按业务能力迁移请求 DTO、为 AdminRag 增加 API 所有的响应 DTO，并用轻量组件测试锁定边界行为。尚未实现的 Query 响应契约继续归 `plan_7`。

## 范围

- `ResponseCode` 保存 `code`、`defaultMessage`、`HttpStatus`。
- 移除宽泛 `BAD_REQUEST`，新增 `VALIDATION_ERROR`、`INVALID_ARGUMENT`。
- 业务码调整为 `SUCCESS=0`、`VALIDATION_ERROR=40001`、`INVALID_ARGUMENT=40002`、`NOT_FOUND=40401`、`INTERNAL_ERROR=50001`。
- `GlobalExceptionHandler` 统一从 `ResponseCode` 读取 HTTP 状态。
- 保持响应 JSON 为 `success`、`code`、`result` 三字段，不增加 `message`。
- 将 `AdminRagRequest` 移至 `dto.rag`，将 `UserQueryRequest` 移至 `dto.query`。
- 新增 `AdminRagResponse`，由 API 层把 `AdminRagResult` 映射为 HTTP 响应 DTO。
- 使用 `@WebMvcTest` 覆盖成功映射、校验、非法参数、未知路径和兜底异常。

## 非目标

- 不设计或新增 `UserQueryResponse`；该契约由 `plan_7` 与 Query 功能一起落地。
- 不增加 `message`、`timestamp`、`path`、`traceId` 或错误详情 JSON。
- 不修改 AdminRag 业务逻辑、持久化、Retrieval、Docker 或供应商集成。
- 不保留 `BAD_REQUEST` 兼容别名。
- 不把真实数据库或完整链路装入 API 组件测试。

## 前置依赖

- **执行前置 Plan**：`plan_9`、`plan_12`
- `plan_9` 已完成 API 模块与统一异常边界。
- `plan_12` 必须先完成约束事实校准和防漂移校验，使本 Hotfix 依据稳定的现行规则执行。

## 文件边界

- `crag-common/src/main/java/ai/cerbur/crag/common/dto/result/ResponseCode.java`
- `crag-common/src/test/**`
- `crag-api/src/main/java/ai/cerbur/crag/api/controller/**`
- `crag-api/src/main/java/ai/cerbur/crag/api/dto/**`
- `crag-api/src/test/**`
- `crag-api/build.gradle.kts`
- `constraints/api-style.md`
- `constraints/package-structure.md`
- `plan/plan_9/plan_9.hotfix_3.md`
- `plan/index/README.md`

## 关联范围与规模说明

- 修正 `plan_9` 完成后遗留的 HTTP API 边界债，涉及 `crag-common` 与 `crag-api` 两个业务模块，共 3 个任务，未超过 Hotfix 上限。

## 关键决策

- HTTP 状态表达协议结果；`Response.code` 使用独立稳定业务码，二者不再复用同一数值语义。
- `ResponseCode` 立即持有默认安全消息，但本次不序列化消息，保持现有 JSON 结构兼容。
- `GlobalExceptionHandler` 使用 `ResponseEntity<Response<?>>` 和 `ResponseCode.getHttpStatus()` 统一设置所有错误 HTTP 状态。
- Bean Validation 映射 `VALIDATION_ERROR/40001/HTTP 400`；`IllegalArgumentException` 映射 `INVALID_ARGUMENT/40002/HTTP 400`。
- 未知路径映射 `NOT_FOUND/40401/HTTP 404`；未处理异常映射 `INTERNAL_ERROR/50001/HTTP 500`，并只在兜底边界记录一次完整堆栈。
- API 层拥有 HTTP DTO。AdminRag 成功响应不再直接序列化 `AdminRagResult`；`AdminRagResponse` 保持相同业务字段，避免无关客户端结构变化。
- 当前已有但尚未实现完整功能的 `UserQueryRequest` 只迁移到 `dto.query`；`UserQueryResponse` 由 `plan_7` 设计。
- API 测试使用 `@WebMvcTest` 和边界 Mock，只证明 MVC 校验、序列化、映射与异常转换，不声称数据库或端到端保证。

## 未决问题

无。业务码、HTTP 状态、JSON 兼容边界、DTO 归属和测试范围均已在 grilling 中确认。

## 风险与回滚

- 业务错误码从 400/404/500 改为 40001/40401/50001，可能影响依赖旧数值的客户端；当前项目尚无声明的稳定外部客户端，测试与 README 搜索用于确认影响面。
- DTO 移包会导致 import 编译失败；通过全仓库编译和 `rg` 检查旧包引用。
- `@WebMvcTest` 可能因安全或自动配置差异加载额外 Bean；测试只导入目标 Controller 与 Advice，并显式 Mock 业务边界。
- 本 Hotfix 不改变数据库或部署。失败时可逆序撤销测试、DTO 与错误码提交。

## 测试与验证计划

- 纯单元测试：验证 `ResponseCode` 每个值的业务码、默认消息与 HTTP 状态。
- 轻量组件测试：使用 `@WebMvcTest` 覆盖 AdminRag 成功响应、Bean Validation、`IllegalArgumentException`、未知路径与未处理异常。
- 架构检查：现有 `ModuleBoundaryArchitectureTest` 验证 DTO 和 Controller 仍位于 `crag-api`，下层模块不依赖 HTTP DTO。
- 全量 Gradle：`./gradlew check`。
- 静态检查：`rg -n 'BAD_REQUEST|api\.dto\.request' crag-* constraints` 必须无过期实现命中。
- 本 Hotfix 不触发 Docker HTTP 回归：HTTP 字段集合和业务成功字段保持不变，真实链路回归由后续 `plan_7` 和既有脚本覆盖；组件测试覆盖本次协议语义变化。若实现时发现既有自动化脚本断言旧业务码，则本 Hotfix 同步修正并执行受影响脚本。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 9.hotfix_3.1 | 重构 ResponseCode 与统一异常映射 | ⏳ 待开始 | — | — |
| 9.hotfix_3.2 | DTO 按业务分包并增加 AdminRagResponse | ⏳ 待开始 | — | — |
| 9.hotfix_3.3 | 增加 API 组件测试并完成全量验收 | ⏳ 待开始 | — | — |

整体进度：0 / 3（0%）

## 9.hotfix_3.1 重构 ResponseCode 与统一异常映射

**目标**：分离业务错误码与 HTTP 状态，并让异常处理统一读取枚举元数据。  
**前置任务**：无  
**范围**：先为 `ResponseCode` 元数据和异常映射增加失败测试；将枚举改为 `code/defaultMessage/httpStatus`；删除 `BAD_REQUEST`；增加 `VALIDATION_ERROR`、`INVALID_ARGUMENT`；让 Validation、非法参数、404 和兜底异常均返回 `ResponseEntity` 并使用枚举 HTTP 状态。  
**非目标**：不修改 `Response` 字段、不序列化默认消息、不增加业务异常体系。  
**验收标准**：所有错误码唯一且符合已确认数值；异常映射的 HTTP 状态与枚举一致；兜底异常记录完整堆栈；项目中无 `BAD_REQUEST` 引用。  
**验证方式**：先运行新增测试确认对旧实现失败；实现后运行 `./gradlew :crag-common:test :crag-api:test`；运行 `rg -n 'BAD_REQUEST' crag-* constraints`。  
**涉及文件**：`crag-common/src/main/java/ai/cerbur/crag/common/dto/result/ResponseCode.java`、`crag-common/src/test/**`、`crag-api/src/main/java/ai/cerbur/crag/api/controller/advice/GlobalExceptionHandler.java`、`crag-api/src/test/**`

## 9.hotfix_3.2 DTO 按业务分包并增加 AdminRagResponse

**目标**：让 HTTP 请求与响应契约由 API 边界按业务能力所有。  
**前置任务**：9.hotfix_3.1  
**范围**：把 `AdminRagRequest` 移至 `ai.cerbur.crag.api.dto.rag`，把 `UserQueryRequest` 移至 `ai.cerbur.crag.api.dto.query`；新增字段为 `docId/chunks/status` 的 `AdminRagResponse` record；在 `AdminRagController` 中显式从 `AdminRagResult` 映射并返回 `Response<AdminRagResponse>`；同步当前实现索引。  
**非目标**：不新增 `UserQueryResponse`，不修改 `AdminRagResult`，不改变请求字段、URL 或成功响应业务字段。  
**验收标准**：旧 `dto.request` 包不存在；下层模块不引用 API DTO；AdminRag 成功 JSON 的字段集合和值保持一致；包结构索引与源码一致。  
**验证方式**：运行 `./gradlew :crag-api:compileJava :crag-app:test`；运行 `rg -n 'api\.dto\.request|dto/request' crag-* constraints`；核对 AdminRag MVC 成功测试。  
**涉及文件**：`crag-api/src/main/java/ai/cerbur/crag/api/dto/**`、`crag-api/src/main/java/ai/cerbur/crag/api/controller/AdminRagController.java`、`crag-api/src/main/java/ai/cerbur/crag/api/controller/UserQueryController.java`、`constraints/package-structure.md`

## 9.hotfix_3.3 增加 API 组件测试并完成全量验收

**目标**：用 API 边界测试锁定本 Hotfix 的协议语义并完成回归。  
**前置任务**：9.hotfix_3.1、9.hotfix_3.2  
**范围**：完善 `@WebMvcTest` 测试，覆盖 AdminRag 成功映射、请求校验、程序化非法参数、未知路径和未处理异常；必要时补充测试专用 Controller 触发 Advice 分支；同步 `api-style.md` 的当前业务码与 DTO 结构示例；执行全量验证并记录证据。  
**非目标**：不启动数据库、Sidecar 或 Docker，不测试业务 Service 内部逻辑。  
**验收标准**：五类 HTTP 行为均断言 HTTP 状态、`success`、`code` 和 `result`；无测试依赖完整错误文案；`./gradlew check` 与全部静态校验通过。  
**验证方式**：运行 `./gradlew :crag-api:test --tests '*ComponentTest'`、`./gradlew check`、`python3 scripts/validate_constraints.py`、`python3 scripts/validate_plans.py --strict`、`git diff --check`。  
**涉及文件**：`crag-api/src/test/**`、`crag-api/build.gradle.kts`、`constraints/api-style.md`、`plan/plan_9/plan_9.hotfix_3.md`、`plan/index/README.md`

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |

## 阻塞记录

无。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-19 | 创建并转为 ready | API 约束与当前实现存在 DTO 组织和错误码语义偏差，grilling 已完成 | 建立 3 项修复任务；等待 plan_12 完成后执行 |
