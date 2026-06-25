---
workflow_version: 3
plan_id: plan_17
type: main
status: ready
created: 2026-06-25
updated: 2026-06-25
---

# plan_17 — 可靠事件基础设施

> **For agentic workers:** 执行本计划必须先读取 `skill/execute-crag-plan/SKILL.md`；实现步骤使用测试先行、任务级提交和独立验收交接。

**Goal**：新增领域无关的 `crag-event` 可靠事件基础设施，并用 Knowledge smoke-only 闭环验证 Outbox、Redis Streams、ACK、Reclaim、DLQ 和消费幂等。

**Architecture**：`crag-event` 是独立 library module，不依赖任何业务 application module。事件持久化仍属于各服务自己的 schema；plan17 只在 Knowledge schema 创建 `outbox_event` 与 `processed_event` 作为 smoke 验证宿主。Redis Streams 是传输层，不是业务事实来源。

**Tech Stack**：Java 21、Spring Boot 4.1.0、Spring Framework 7、Gradle 9.4.1、Spring JDBC、Spring Data Redis、Micrometer、Actuator、PostgreSQL 17、Redis 7.4、Docker Compose。

## 全局实现约束

- 设计事实来源：`docs/superpowers/specs/2026-06-25-reliable-event-infrastructure-design.md`。
- 新增 `crag-event` library module，不放入 `crag-common`。
- `crag-event` 禁止依赖 `crag-access-service`、`crag-knowledge-service`、`crag-rag-service`、`crag-console-api`、`crag-open-api` 或任何具体业务领域 package。
- 每个接入服务使用自己的 schema 保存 `outbox_event` 与 `processed_event`；plan17 只在 Knowledge schema 落地 smoke 表。
- Redis Stream entry 使用字段化信封 + JSON payload。
- `eventId`、`resourceId`、`operationVersion` 在 Redis 与 HTTP 边界使用十进制字符串。
- Knowledge 事件诊断 Controller 只允许在 `smoke` Profile 下注册。
- 不实现 `DOC_UPLOADED`、Document、KnowledgeBase、RAG Ingestion Job、API Key 失效或真实业务事件。
- 不建立全局 event schema，不跨 schema 查询事件表。
- 不提供正式管理 API、人工 DLQ 重放界面、告警平台或补偿扫描平台。
- Java 代码遵守 `constraints/code-style.md`；持久化遵守 `constraints/persistence-style.md`；HTTP smoke 边界遵守 `constraints/api-style.md`；测试遵守 `constraints/test-workflow.md`。

## 背景与目标

多租户知识平台后续需要跨服务生命周期事件：Knowledge 上传后通知 RAG，RAG 回传 Ingestion 状态，删除流程通知下游清理，Access API Key 状态变化通知 Open API 缓存失效。这些事件必须至少一次投递、消费者幂等、失败可重试、长期失败可诊断。

当前仓库已经具备五 Java 服务、独立 schema、gRPC runtime、Snowflake ID 和 Redis Worker lease。Redis 在现有约束中仍被描述为只承载 Worker lease；本计划将其职责扩展为 Worker lease 与 Redis Streams 传输，同时继续禁止把 Redis 当作业务持久化事实来源。

仓库中实际 `plan_16` 已被 RAG Service Module 收口占用；本计划对应 `plan_main.md` 原路线中“可靠事件基础设施”的语义，实际创建为 `plan_17`。

## 范围

- 新增 `crag-event` Gradle library module。
- 定义事件信封、Outbox 状态、processed event 状态、handler 结果和基础设施错误码。
- 实现 Outbox 与 processed_event JDBC DAO、CAS claim、状态推进、backoff 和幂等记录。
- 实现 Redis Streams publisher、consumer group、ACK、pending reclaim、DLQ 和字段化映射。
- 实现 Spring Boot auto-configuration、`crag.event.*` 配置、health indicator 与 metrics/logging 接入。
- 在 `crag-knowledge-service` 的 `smoke` Profile 下实现事件诊断 Controller 与 test handler。
- 为 Knowledge smoke 创建 `outbox_event` 与 `processed_event` 初始化 SQL。
- 新增 Docker smoke 回归脚本，验证成功事件、DLQ 事件和默认 profile 禁用。
- 更新 `settings.gradle.kts`、Gradle 依赖白名单、约束文档、静态校验器与 README 中受影响的当前事实。
- 更新 `docker-compose.yml`，如需要新增 `knowledge-service-smoke` profile 服务。

## 非目标

- 不实现真实 KnowledgeBase、Document、File Object 或文件上传。
- 不实现 RAG 多知识库隔离、Ingestion Job 或状态回传。
- 不实现 Access User、Tenant、Membership、JWT、Refresh Session 或 API Key。
- 不实现真实业务事件类型，例如 `DOC_UPLOADED`、`DOCUMENT_DELETED`、`API_KEY_INVALIDATED`。
- 不引入 Flyway、Liquibase 或三服务完整版本化迁移体系；Knowledge smoke 表可使用当前项目已有初始化脚本方式。
- 不修改 plan16 的验收状态，不回填 plan16 任务提交。
- 不重构现有 RAG ingestion、retrieval、query 或 storage 业务代码。

## 前置依赖

- **执行前置 Plan**：无
- `plan_16` 的 RAG Service Module 收口源码事实已经存在；执行本计划前应确认 `settings.gradle.kts` 已只保留当前 9 个模块，且 `crag-rag-service` 承载 RAG 内部 packages。
- 用户确认本计划可与待验收的 `plan_16` 并发推进，前提是实现文件边界不冲突。
- 设计文档已提交：`31f436a docs: design reliable event infrastructure`。
- 进入实现前必须先提交本计划和索引；未提交规划修订时不得开始 17.1。

## 并行执行与冲突保护

- 本计划核心新增边界为 `crag-event/**` 与 `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/smoke/**`，与 `plan_16` 的 RAG module 收口实现不重叠。
- 本计划会修改共享治理文件：`constraints/*.md`、`scripts/validate_*.py`、`scripts/tests/test_validate_*.py`、`README.md`、`docker-compose.yml`。
- 若 `plan_16` 独立验收退回且修复范围触及上述共享治理文件，执行 session 必须暂停本计划中涉及同一文件的任务，先让 `plan_16` 完成修复与交接。
- 若 `plan_16` 退回修复只触及 `crag-rag-service/**` 或 RAG smoke HTTP 脚本，本计划可继续执行不重叠任务。
- 执行 session 开始 17.1 前必须运行 `git status --short` 并检查 `plan_16` 最新状态；如发现共享文件已有未提交改动，不得覆盖，需先向用户确认归属。

## 文件边界

- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle/libs.versions.toml`
- `crag-event/**`
- `crag-knowledge-service/build.gradle.kts`
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/**`
- `crag-knowledge-service/src/main/resources/**`
- `crag-knowledge-service/src/test/java/ai/cerbur/crag/knowledge/**`
- `crag-knowledge-service/src/test/resources/**`
- `docker-compose.yml`
- `constraints/package-structure.md`
- `constraints/docker-structure.md`
- `constraints/test-workflow.md`
- `constraints/api-style.md`
- `constraints/persistence-style.md`
- `scripts/validate_module_dependencies.py`
- `scripts/tests/test_validate_module_dependencies.py`
- `scripts/validate_constraints.py`
- `scripts/tests/test_validate_constraints.py`
- `scripts/tests/http/event_smoke_success_test.sh`
- `scripts/tests/http/event_smoke_dlq_test.sh`
- `scripts/tests/http/event_smoke_default_disabled_test.sh`
- `README.md`
- `plan/plan_17/plan_17.md`
- `plan/index/README.md`

## 实现文件地图

### `crag-event`

- `crag-event/build.gradle.kts`：library module，依赖 Spring JDBC、Spring Data Redis、Actuator、Jackson、Micrometer、Spring Boot autoconfigure 和测试依赖。
- `crag-event/src/main/java/ai/cerbur/crag/event/api/EventEnvelope.java`：稳定事件信封，负责必填字段和十进制字符串边界。
- `crag-event/src/main/java/ai/cerbur/crag/event/api/EventHandler.java`：事件处理契约，handler 必须可重复调用。
- `crag-event/src/main/java/ai/cerbur/crag/event/api/EventHandlerResult.java`：`success`、`retryableFailure`、`nonRetryableFailure`。
- `crag-event/src/main/java/ai/cerbur/crag/event/api/OutboxEventStatus.java`：`PENDING / PUBLISHING / PUBLISHED / RETRY_WAIT / DEAD`。
- `crag-event/src/main/java/ai/cerbur/crag/event/api/ProcessedEventStatus.java`：`PROCESSED / FAILED / DEAD_LETTERED`。
- `crag-event/src/main/java/ai/cerbur/crag/event/api/EventErrorCode.java`：`REDIS_UNAVAILABLE`、`MESSAGE_MALFORMED`、`HANDLER_FAILED`、`HANDLER_NON_RETRYABLE`、`OUTBOX_CAS_CONFLICT`、`OUTBOX_EXHAUSTED`。
- `crag-event/src/main/java/ai/cerbur/crag/event/jdbc/JdbcOutboxEventDao.java`：Outbox insert、claim、publish success、retry wait、dead 状态推进。
- `crag-event/src/main/java/ai/cerbur/crag/event/jdbc/JdbcProcessedEventDao.java`：processed_event 幂等占位、成功、失败、dead-letter 状态推进。
- `crag-event/src/main/java/ai/cerbur/crag/event/jdbc/OutboxBackoffPolicy.java`：attempt 到 nextAttemptAt 的计算。
- `crag-event/src/main/java/ai/cerbur/crag/event/redis/RedisStreamEventMapper.java`：字段化 Stream entry 与 `EventEnvelope` 互转。
- `crag-event/src/main/java/ai/cerbur/crag/event/redis/RedisStreamEventPublisher.java`：写入 Redis Stream。
- `crag-event/src/main/java/ai/cerbur/crag/event/redis/RedisStreamEventConsumer.java`：consumer group 读取、handler 调用和 ACK。
- `crag-event/src/main/java/ai/cerbur/crag/event/redis/RedisPendingReclaimer.java`：pending idle reclaim 与 delivery count 判断。
- `crag-event/src/main/java/ai/cerbur/crag/event/redis/DeadLetterPublisher.java`：写入 DLQ stream。
- `crag-event/src/main/java/ai/cerbur/crag/event/spring/EventProperties.java`：`crag.event.*` 配置绑定。
- `crag-event/src/main/java/ai/cerbur/crag/event/spring/EventAutoConfiguration.java`：publisher、consumer、health、metrics 自动装配。
- `crag-event/src/main/java/ai/cerbur/crag/event/spring/EventHealthIndicator.java`：可选 Redis 和表访问 health。
- `crag-event/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：注册 auto-configuration。

### `crag-knowledge-service`

- `crag-knowledge-service/build.gradle.kts`：依赖 `crag-event`、Spring Data Redis、JDBC 和测试所需依赖。
- `crag-knowledge-service/src/main/resources/schema-event-smoke.sql`：Knowledge schema 下 smoke 事件表初始化 SQL。
- `crag-knowledge-service/src/main/resources/application.yml`：默认不启用 event publisher/consumer。
- `crag-knowledge-service/src/main/resources/application-smoke.yml`：启用 smoke event publisher/consumer 和 stream 配置。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/smoke/controller/KnowledgeEventSmokeController.java`：`@Profile("smoke")`，提供 smoke 事件创建和查询。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/smoke/dto/KnowledgeSmokeEventRequest.java`：受控请求字段 `runId`、`message`、`failMode`。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/smoke/dto/KnowledgeSmokeEventResponse.java`：创建响应。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/smoke/dto/KnowledgeSmokeEventStatusResponse.java`：查询诊断摘要。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/smoke/event/KnowledgeSmokeEventService.java`：写 outbox 与查询状态。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/smoke/event/KnowledgeSmokeEventHandler.java`：根据 `failMode` 返回 success/retryable/non-retryable。

### 测试与脚本

- `crag-event/src/test/java/ai/cerbur/crag/event/api/**`：纯单元测试。
- `crag-event/src/test/java/ai/cerbur/crag/event/jdbc/**`：H2 轻量组件测试。
- `crag-event/src/test/java/ai/cerbur/crag/event/redis/**`：Redis mapper/consumer/reclaimer 替身测试。
- `crag-event/src/test/java/ai/cerbur/crag/event/spring/**`：auto-configuration 组件测试。
- `crag-knowledge-service/src/test/java/ai/cerbur/crag/knowledge/smoke/**`：profile、Controller、handler 组件测试。
- `scripts/tests/http/event_smoke_success_test.sh`：成功事件 Docker HTTP 回归。
- `scripts/tests/http/event_smoke_dlq_test.sh`：retryable failure 进入 DLQ 的 Docker HTTP 回归。
- `scripts/tests/http/event_smoke_default_disabled_test.sh`：默认 profile smoke endpoint 禁用回归。

## 关键决策

- `crag-event` 是独立 library module，不进入 `crag-common`。
- Outbox 与 processed_event 属于各服务本地 schema；plan17 只落地 Knowledge smoke 表。
- Redis Stream entry 使用字段化信封，payload 是 JSON 字符串。
- Publisher 与 Consumer 都通过 `crag.event.*` 显式启用；新增依赖本身不启动后台任务。
- 开启 consumer 但没有 handler 时允许启动但不轮询，并记录清晰日志。
- malformed message 写 DLQ 并 ACK 原消息，避免毒消息卡住队列。
- handler retryable failure 不 ACK，等待 reclaim；超过 `maxDeliveries` 后写 DLQ。
- Knowledge smoke Controller 只允许受控字段，不接收任意 JSON payload，不回显完整 payload。
- Docker 回归主断言走 HTTP smoke endpoint，不用 `redis-cli` 作为主证明。

## 未决问题

无。

## 风险与回滚

- 风险：事件基础设施膨胀并提前侵入业务语义。预防措施是测试事件只使用 `EVENT_SMOKE_CREATED`，禁止真实业务事件进入本计划。
- 风险：H2 组件测试被误表述为 PostgreSQL 兼容证明。预防措施是在测试计划和验收记录中明确 H2 只证明 DAO 行为，真实 PostgreSQL 与 Redis 由 Docker HTTP 回归证明。
- 风险：Redis fake 测试无法覆盖真实 `XREADGROUP`、`XPENDING`、`XCLAIM` 行为。预防措施是新增 success 与 DLQ Docker 回归。
- 风险：Smoke Controller profile 限制遗漏，导致默认服务暴露诊断入口。预防措施是组件测试、ArchUnit 和 `event_smoke_default_disabled_test.sh` 同时覆盖。
- 风险：Redis 约束文档未同步，继续声明 Redis 不承载事件传输。预防措施是将 Docker 约束和约束校验器列入任务。
- 回滚：本计划不包含不可逆业务数据迁移。可按任务提交 revert `crag-event`、Knowledge smoke 接入、Compose smoke 服务和约束文档改动。Knowledge smoke 表只承载测试事件，回滚时可保留无害残留或由后续初始化脚本处理。

## 测试与验证计划

- 纯单元测试：`./gradlew :crag-event:test --tests '*EventEnvelopeTest' --tests '*OutboxEventStatusTest' --tests '*OutboxBackoffPolicyTest' --tests '*RedisStreamEventMapperTest' --tests '*ProcessedEventIdempotencyKeyTest' --tests '*EventHandlerResultTest'`。
- 轻量组件测试：`./gradlew :crag-event:test --tests '*ComponentTest'`，覆盖 JDBC DAO、auto-configuration 和 health。
- Knowledge smoke 组件测试：`./gradlew :crag-knowledge-service:test --tests '*Smoke*' --tests '*Event*'`。
- 架构测试：`./gradlew test --tests '*ArchitectureTest'`，覆盖 `crag-event` 依赖边界、Knowledge smoke profile 和 module dependency 白名单。
- 静态与格式：`./gradlew spotlessCheck`、`./gradlew check`。
- Plan 校验：`python3 scripts/validate_plans.py`；完成前由验收 session 运行 `python3 scripts/validate_plans.py --strict --verify-git`。
- 约束/依赖校验器：`python3 -m unittest scripts.tests.test_validate_module_dependencies scripts.tests.test_validate_constraints -v`。
- Docker HTTP 回归：`scripts/tests/http/event_smoke_success_test.sh`、`scripts/tests/http/event_smoke_dlq_test.sh`、`scripts/tests/http/event_smoke_default_disabled_test.sh`。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 17.1 | 创建 `crag-event` 模块、API 类型与架构约束 | ⏳ 待开始 | — | — |
| 17.2 | 实现 Outbox 与 processed_event JDBC 基础设施 | ⏳ 待开始 | — | — |
| 17.3 | 实现 Redis Streams publisher、consumer、Reclaim 与 DLQ | ⏳ 待开始 | — | — |
| 17.4 | 接入 Knowledge smoke 事件闭环 | ⏳ 待开始 | — | — |
| 17.5 | 补齐可观测性、约束文档、校验器与 Docker 回归 | ⏳ 待开始 | — | — |
| 17.6 | 完成全量验证、Plan 交接和索引同步 | ⏳ 待开始 | — | — |

整体进度：0 / 6（0%）

## 17.1 创建 `crag-event` 模块、API 类型与架构约束

**目标**：建立 `crag-event` library module，定义领域无关事件 API 与基础状态类型，并让构建、依赖白名单和约束文档识别新模块。  
**前置任务**：无  
**范围**：新增 `crag-event` module；更新 `settings.gradle.kts`、root/模块 Gradle 依赖；新增 `EventEnvelope`、`EventHandler`、`EventHandlerResult`、`OutboxEventStatus`、`ProcessedEventStatus`、`EventErrorCode`；新增纯单元测试；更新 `constraints/package-structure.md`、`scripts/validate_module_dependencies.py` 及测试，禁止 `crag-event` 依赖 application module。  
**非目标**：不实现 JDBC DAO、Redis publisher/consumer、Knowledge smoke 接入。  
**验收标准**：`crag-event` 可单独测试；API 类型覆盖 spec 必填字段和状态；模块依赖校验允许 `crag-event` 并禁止其依赖 application module；`crag-common` 没有事件基础设施类型。  
**验证方式**：运行 `./gradlew :crag-event:test --tests '*EventEnvelopeTest' --tests '*OutboxEventStatusTest' --tests '*ProcessedEventIdempotencyKeyTest' --tests '*EventHandlerResultTest'`；运行 `python3 -m unittest scripts.tests.test_validate_module_dependencies -v`；运行 `rg 'crag-event|ai\\.cerbur\\.crag\\.event' crag-common` 应无生产代码命中。  
**涉及文件**：`settings.gradle.kts`、`build.gradle.kts`、`crag-event/**`、`constraints/package-structure.md`、`scripts/validate_module_dependencies.py`、`scripts/tests/test_validate_module_dependencies.py`

## 17.2 实现 Outbox 与 processed_event JDBC 基础设施

**目标**：实现本地 schema 内的 Outbox 与 processed_event DAO、CAS claim、状态推进、backoff 和幂等记录。  
**前置任务**：17.1  
**范围**：新增 `JdbcOutboxEventDao`、`JdbcProcessedEventDao`、`OutboxEventRecord`、`ProcessedEventRecord`、`OutboxClaim`、`OutboxBackoffPolicy`；实现 insert、batch claim、mark published、mark retry wait、mark dead、processed idempotency claim、mark processed、mark failed、mark dead-lettered；新增 H2 组件测试。  
**非目标**：不写 Redis，不启动 scheduler，不接入 Knowledge schema。  
**验收标准**：DAO 方法不泄漏 Repository；自定义更新包含 version 条件和 version 递增；并发 claim 只有一个成功；`PUBLISHING` 超时可被重新 claim；`consumerName + eventId` 与 `consumerName + idempotencyKey` 幂等约束生效；H2 测试不被表述为 PostgreSQL 兼容证明。  
**验证方式**：运行 `./gradlew :crag-event:test --tests '*JdbcOutboxEventDaoComponentTest' --tests '*JdbcProcessedEventDaoComponentTest' --tests '*OutboxPublisherServiceTest'`。  
**涉及文件**：`crag-event/src/main/java/ai/cerbur/crag/event/jdbc/**`、`crag-event/src/test/java/ai/cerbur/crag/event/jdbc/**`

## 17.3 实现 Redis Streams publisher、consumer、Reclaim 与 DLQ

**目标**：实现字段化 Redis Stream 生产、Consumer Group 消费、ACK、Pending Reclaim、DLQ 和 handler 结果映射。  
**前置任务**：17.2  
**范围**：新增 `RedisStreamEventMapper`、`RedisStreamEventPublisher`、`RedisStreamEventConsumer`、`RedisPendingReclaimer`、`DeadLetterPublisher`；实现 malformed message DLQ + ACK；success 处理后 `PROCESSED` + ACK；retryable failure 不 ACK；non-retryable failure DLQ + ACK；超过 `maxDeliveries` 后 DLQ；新增 Redis 适配替身测试。  
**非目标**：不接入 Spring auto-configuration，不写 Docker 回归，不实现业务 handler。  
**验收标准**：Redis entry 字段包含完整信封；payload 非法 JSON 或缺字段进入 DLQ；Redis 不可用时 publisher 回写 `RETRY_WAIT`，consumer 不误 ACK；delivery count 到达上限后写入 DLQ stream。  
**验证方式**：运行 `./gradlew :crag-event:test --tests '*RedisStreamPublisherTest' --tests '*RedisStreamConsumerTest' --tests '*RedisPendingReclaimerTest' --tests '*DeadLetterPublisherTest' --tests '*RedisStreamEventMapperTest'`。  
**涉及文件**：`crag-event/src/main/java/ai/cerbur/crag/event/redis/**`、`crag-event/src/test/java/ai/cerbur/crag/event/redis/**`

## 17.4 接入 Knowledge smoke 事件闭环

**目标**：在 `crag-knowledge-service` 的 `smoke` Profile 下提供事件诊断入口，用真实 Knowledge schema 表和 `crag-event` 完成测试事件闭环。  
**前置任务**：17.3  
**范围**：`crag-knowledge-service` 依赖 `crag-event` 和 Redis/JDBC 运行依赖；新增 Knowledge smoke 表初始化 SQL；新增 `application-smoke.yml`；新增 `KnowledgeEventSmokeController`、DTO、`KnowledgeSmokeEventService`、`KnowledgeSmokeEventHandler`；实现 `POST /api/v1/smoke/events`、按 `runId` 查询和按 `eventId` 查询；新增组件测试覆盖默认 profile 禁用和 smoke profile 启用。  
**非目标**：不创建 KnowledgeBase、Document 或真实业务事件；不允许任意 JSON payload；不回显完整 payload。  
**验收标准**：默认 profile 不注册 `/api/v1/smoke/events`；smoke profile 下可创建测试事件；`runId` 必填；`failMode=none` 可成功处理；`failMode=always` 返回 retryable failure；`failMode=non_retryable` 直接进入 DLQ；查询端点只返回诊断摘要。  
**验证方式**：运行 `./gradlew :crag-knowledge-service:test --tests '*KnowledgeEventSmokeControllerComponentTest' --tests '*KnowledgeEventSmokeProfileComponentTest' --tests '*KnowledgeSmokeEventHandlerTest'`。  
**涉及文件**：`crag-knowledge-service/build.gradle.kts`、`crag-knowledge-service/src/main/resources/**`、`crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/smoke/**`、`crag-knowledge-service/src/test/java/ai/cerbur/crag/knowledge/smoke/**`

## 17.5 补齐可观测性、约束文档、校验器与 Docker 回归

**目标**：完成事件基础设施的 health、metrics、logging、Docker smoke 运行形态、约束文档和 HTTP 回归脚本。  
**前置任务**：17.4  
**范围**：实现 `EventAutoConfiguration`、`EventProperties`、`EventHealthIndicator`、metrics/logging；新增 Spring auto-configuration imports；更新 `docker-compose.yml`，如需要新增 `knowledge-service-smoke` profile 服务；新增三个事件 smoke HTTP 回归脚本；更新 Docker/package/test/API/persistence 约束、README 和约束校验器。  
**非目标**：不实现告警平台、人工 DLQ 重放或生命周期补偿扫描。  
**验收标准**：publisher/consumer 默认不启动；显式开启后配置生效；开启 consumer 无 handler 时启动成功但不轮询；health 可关闭且开启后表缺失/Redis 不通有明确 detail；Docker 约束不再声明 Redis 只承载 Worker lease；默认 Docker 启动不暴露 Knowledge smoke endpoint；HTTP 脚本以 runId 隔离数据，不清空表或 Redis。  
**验证方式**：运行 `./gradlew :crag-event:test --tests '*EventAutoConfigurationComponentTest' --tests '*EventPublisherAutoConfigurationComponentTest' --tests '*EventConsumerAutoConfigurationComponentTest'`；运行 `python3 -m unittest scripts.tests.test_validate_module_dependencies scripts.tests.test_validate_constraints -v`；运行 `scripts/tests/http/event_smoke_success_test.sh`、`scripts/tests/http/event_smoke_dlq_test.sh`、`scripts/tests/http/event_smoke_default_disabled_test.sh`。  
**涉及文件**：`crag-event/src/main/java/ai/cerbur/crag/event/spring/**`、`crag-event/src/main/resources/META-INF/spring/**`、`crag-event/src/test/java/ai/cerbur/crag/event/spring/**`、`docker-compose.yml`、`constraints/*.md`、`scripts/validate_constraints.py`、`scripts/tests/test_validate_constraints.py`、`scripts/tests/http/event_smoke_*.sh`、`README.md`

## 17.6 完成全量验证、Plan 交接和索引同步

**目标**：完成本计划全量自测、格式、静态校验、Docker HTTP 回归和执行 session 交接记录。  
**前置任务**：17.5  
**范围**：运行必需 Gradle/Python/Docker 验证；修复验证发现的本计划范围内问题；回填 17.1-17.5 实现提交短 hash；将任务转为待验收；将 Plan 转为 `verifying`；同步 `plan/index/README.md` 验收队列。  
**非目标**：不做最终验收完成；最终完成必须由未参与实现的独立验收 session 执行。  
**验收标准**：`./gradlew spotlessCheck`、`./gradlew test`、`./gradlew check` 通过；Plan 校验通过；事件 Docker HTTP smoke 回归通过；每个实现任务提交栏有真实短 hash；Plan 和索引均处于待验收状态且互相一致。  
**验证方式**：运行 `./gradlew spotlessCheck`、`./gradlew test`、`./gradlew check`、`python3 scripts/validate_plans.py`、`python3 -m unittest scripts.tests.test_validate_module_dependencies scripts.tests.test_validate_constraints -v`、`scripts/tests/http/event_smoke_success_test.sh`、`scripts/tests/http/event_smoke_dlq_test.sh`、`scripts/tests/http/event_smoke_default_disabled_test.sh`。  
**涉及文件**：`plan/plan_17/plan_17.md`、`plan/index/README.md`、本计划范围内因验证失败需要修正的文件

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |

## 阻塞记录

无。发生阻塞时记录原因、当前进度、解除条件、解除方、下一步与日期。

## 废弃任务记录

无。任务废弃时记录原因、日期及替代任务或决策。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-25 | 创建计划 | 用户确认可靠事件基础设施设计，并要求实际创建 plan17 | 初始范围为 `crag-event`、本地 Outbox/processed_event、Redis Streams、Knowledge smoke 闭环、可观测性、约束和 Docker 回归 |
| 2026-06-25 | 允许与 plan16 并发 | 用户确认若无文件冲突，plan17 可与待验收的 plan16 并发执行 | plan17 状态保持待开始；新增并行执行与共享文件冲突保护 |
