# Reliable Event Infrastructure Design

日期：2026-06-25

状态：已确认

范围：plan17 的设计输入。该计划对应 `plan_main.md` 中原路线 `plan_16` 的“可靠事件基础设施”语义；由于仓库中实际 `plan_16` 已被 RAG Service Module 收口占用，执行计划应创建为 `plan_17`。

## 1. 背景

CRAG-Demo 已确定向多租户知识平台演进。后续 Knowledge、RAG 和 Access 之间会出现多类跨服务生命周期事件：

- Knowledge 上传 Document 后通知 RAG 建立 Ingestion Job。
- RAG 回传 Ingestion 处理状态。
- Knowledge 删除 Document 或 KnowledgeBase 后通知下游清理。
- Access API Key 状态变化后通知 Open API 缓存失效。

这些事件都要求至少一次投递、消费者幂等、失败可重试、长时间失败可诊断。当前仓库已经具备五个 Java 服务、独立 schema、gRPC runtime、Snowflake ID 和 Redis Worker lease 基线，但还没有领域无关的 Outbox、Redis Streams consumer group、ACK、Reclaim、DLQ 和消费幂等基础设施。

当前 `plan/plan_16/plan_16.md` 与 `plan/index/README.md` 仍可能显示 RAG Service Module 收口为待开始，但 git 历史和源码结构已经显示该工作大体进入实现后状态。plan17 以当前源码中的模块收口事实为背景，不回写 plan16 的验收状态。

## 2. 目标

新增独立 library module `crag-event`，为后续业务服务提供可靠事件基础设施：

1. 定义稳定事件信封和字段化 Redis Stream entry 编码。
2. 提供每服务本地 `outbox_event` 与 `processed_event` 表的 JDBC DAO、CAS claim、状态推进和幂等记录。
3. 提供 Outbox Publisher，将本地待发布事件可靠写入 Redis Streams。
4. 提供 Redis Streams Consumer Group、ACK、Pending Reclaim、DLQ 和 handler 幂等调用基础能力。
5. 提供 Spring Boot auto-configuration，使用 `crag.event.*` 显式开关启用 publisher 和 consumer。
6. 在 `crag-knowledge-service` 中实现 smoke-only 事件闭环，证明发布、消费、ACK、Reclaim、DLQ 和幂等路径可运行。
7. 补齐相关模块依赖、Docker、package 和测试约束，使 Redis 从“只承载 Worker lease”扩展为“Worker lease + Redis Streams 传输”。

## 3. 非目标

- 不实现 `DOC_UPLOADED`、Document、KnowledgeBase、RAG Ingestion Job 或 API Key 失效等真实业务事件。
- 不实现 Knowledge、RAG 或 Access 的完整业务表。
- 不引入 Kafka、RabbitMQ 或其他消息系统。
- 不建立全局 event schema，不跨 schema 查询事件表。
- 不把事件基础设施放入 `crag-common`。
- 不提前实现 Knowledge/RAG/Access 的版本化迁移体系。
- 不提供正式管理 API、人工 DLQ 重放界面、告警平台或补偿扫描平台。
- 不改变 plan16 的验收状态。

## 4. 方案选择

采用“独立 `crag-event` + Knowledge smoke 闭环”方案。

`crag-event` 作为 library module 承载领域无关的事件基础设施。业务数据和事件数据仍写入各服务自己的 schema。plan17 只在 Knowledge schema 创建 `outbox_event` 和 `processed_event` 用于 smoke 验证；后续 Access、RAG 接入时各自在自己的 schema 复用同一表结构和 DAO。

`crag-knowledge-service` 只在 `smoke` Profile 下暴露事件诊断 Controller，用于创建测试事件和查询处理状态。默认 profile 不暴露该 Controller，不形成正式 Knowledge API。

该方案比只定义接口更能验证可靠性核心风险，也比跨服务业务样例更克制，不提前侵入 Knowledge 垂直链路或 RAG 多知识库化。

## 5. 模块与包边界

新增 Gradle module：

```text
crag-event
```

建议依赖：

- Spring JDBC
- Spring Data Redis
- Spring Boot Actuator
- Jackson
- Micrometer API
- Spring Boot autoconfigure

禁止依赖：

- `crag-access-service`
- `crag-knowledge-service`
- `crag-rag-service`
- `crag-console-api`
- `crag-open-api`
- 任何具体业务领域 package

`crag-event` 包结构建议：

```text
ai.cerbur.crag.event.api
├── EventEnvelope
├── EventPayload
├── EventHandler
├── EventHandlerResult
├── OutboxEventStatus
├── ProcessedEventStatus
└── EventInfrastructureException

ai.cerbur.crag.event.jdbc
├── JdbcOutboxEventDao
├── JdbcProcessedEventDao
├── OutboxEventRecord
├── ProcessedEventRecord
└── OutboxClaim

ai.cerbur.crag.event.redis
├── RedisStreamEventPublisher
├── RedisStreamEventConsumer
├── RedisPendingReclaimer
├── DeadLetterPublisher
└── RedisStreamEventMapper

ai.cerbur.crag.event.spring
├── EventAutoConfiguration
├── EventProperties
├── EventHealthIndicator
└── EventMetrics
```

`crag-knowledge-service` 在 plan17 中新增 smoke package：

```text
ai.cerbur.crag.knowledge.smoke
├── controller
├── dto
└── event
```

所有 smoke Controller 必须使用类级 `@Profile("smoke")`。默认 Knowledge 服务不得注册 `/api/v1/smoke/events`。

## 6. 数据模型

每个接入服务在自己的 schema 中创建两张基础设施表。plan17 只在 Knowledge schema 创建它们。

### 6.1 `outbox_event`

建议字段：

| 字段 | 说明 |
| --- | --- |
| `event_id BIGINT` | 事件 ID，主键。边界以十进制字符串表达。 |
| `event_type VARCHAR` | 事件类型。plan17 smoke 使用 `EVENT_SMOKE_CREATED`。 |
| `producer VARCHAR` | 生产服务，例如 `knowledge-service`。 |
| `resource_type VARCHAR` | 资源类型，smoke 可使用 `SMOKE_EVENT`。 |
| `resource_id BIGINT` | 资源 ID，smoke 使用测试资源 ID。 |
| `operation_version BIGINT` | 操作版本，用于业务幂等和迟到事件处理。 |
| `payload_version INT` | payload 版本。 |
| `payload_json TEXT` | JSON payload。plan17 不使用 JSONB，以降低方言耦合。 |
| `trace_id VARCHAR` | trace id。 |
| `occurred_at TIMESTAMPTZ` | 事件发生时间。 |
| `status VARCHAR` | `PENDING / PUBLISHING / PUBLISHED / RETRY_WAIT / DEAD`。 |
| `next_attempt_at TIMESTAMPTZ` | 下次可发布时间。 |
| `attempt_count INT` | 发布尝试次数。 |
| `last_error_code VARCHAR` | 最近错误码。 |
| `last_error_message VARCHAR` | 最近错误摘要。 |
| `published_at TIMESTAMPTZ` | 发布成功时间。 |
| `version BIGINT` | CAS 版本。 |
| `claimed_by VARCHAR` | 当前 publisher claim 标识。 |
| `claimed_until TIMESTAMPTZ` | claim 过期时间。 |
| `created_at TIMESTAMPTZ` | 创建时间。 |
| `updated_at TIMESTAMPTZ` | 更新时间。 |

状态机：

```text
PENDING -> PUBLISHING -> PUBLISHED
       \              -> RETRY_WAIT -> PUBLISHING
        \             -> DEAD
```

Publisher 通过 DAO 批量 claim `PENDING` 或到期的 `RETRY_WAIT`。claim 必须 CAS 更新 `status`、`version`、`claimed_by` 和 `claimed_until`。发布成功后标记 `PUBLISHED`。发布失败后根据退避策略进入 `RETRY_WAIT`；超过最大次数进入 `DEAD`。`PUBLISHING` claim 超时后可被下一轮重新抢占。

### 6.2 `processed_event`

建议字段：

| 字段 | 说明 |
| --- | --- |
| `consumer_name VARCHAR` | 消费者名称。 |
| `event_id BIGINT` | 事件 ID。与 `consumer_name` 组成唯一键。 |
| `idempotency_key VARCHAR` | 默认格式为 `eventType:resourceType:resourceId:operationVersion`。 |
| `event_type VARCHAR` | 事件类型。 |
| `resource_type VARCHAR` | 资源类型。 |
| `resource_id BIGINT` | 资源 ID。 |
| `operation_version BIGINT` | 操作版本。 |
| `stream_key VARCHAR` | Redis stream key。 |
| `stream_record_id VARCHAR` | Redis stream record id。 |
| `first_seen_at TIMESTAMPTZ` | 首次看到时间。 |
| `processed_at TIMESTAMPTZ` | 成功处理时间。 |
| `status VARCHAR` | `PROCESSED / FAILED / DEAD_LETTERED`。 |
| `handler_attempt_count INT` | handler 尝试次数。 |
| `last_error_code VARCHAR` | 最近错误码。 |
| `last_error_message VARCHAR` | 最近错误摘要。 |

约束：

- `consumer_name + event_id` 唯一，防止同一消息重复处理。
- `consumer_name + idempotency_key` 唯一，支持同一业务操作用新 `eventId` 重发时仍保持幂等。
- 已 `PROCESSED` 的事件再次投递时直接 ACK。
- `FAILED` 可在后续 reclaim 成功后更新为 `PROCESSED`。
- `DEAD_LETTERED` 不被普通成功路径覆盖。未来若需要人工重放，应由独立计划定义。

## 7. Redis Streams 编码

Redis Stream entry 使用字段化信封 + JSON payload。

字段：

```text
eventId
eventType
producer
resourceType
resourceId
operationVersion
occurredAt
traceId
payloadVersion
payload
```

`eventId`、`resourceId` 和 `operationVersion` 使用十进制字符串，避免跨语言整数精度问题。`payload` 是 JSON 字符串。基础设施只校验 payload 是合法 JSON，不理解业务字段。

默认 stream key 由接入服务配置。Knowledge smoke 使用：

```text
crag:event:knowledge
crag:event:knowledge:dlq
```

## 8. Publisher 流程

1. 调用方在本地事务中写入 `outbox_event(PENDING)`。plan17 的 smoke 事件由诊断 service 写入，不创建业务表。
2. `crag-event` publisher scheduler 在 `crag.event.publisher.enabled=true` 时启动。
3. Publisher 批量 claim 到期事件。
4. Publisher 将字段化信封写入 Redis Stream。
5. Redis 写入成功后，本地 outbox 标记 `PUBLISHED`。
6. Redis 写入失败时，本地 outbox 进入 `RETRY_WAIT` 或 `DEAD`。

如果 Redis 写入成功但 DB 标记 `PUBLISHED` 失败，事件会在 claim 超时后重发。Consumer 必须依赖 `event_id` 和 `idempotency_key` 幂等处理。

## 9. Consumer 流程

`crag-event` 提供 `EventHandler` 注册模型。handler 声明：

- `consumerName`
- `streamKey`
- `groupName`
- 支持的 `eventType`

Consumer 流程：

1. 在 `crag.event.consumer.enabled=true` 时启动。
2. 确保 Redis Consumer Group 存在。
3. 使用 `XREADGROUP` 批量读取消息。
4. 解析字段化信封并校验。
5. 非法消息写入 DLQ 并 ACK 原消息。
6. 调用 handler 前写入或检查 `processed_event` 幂等记录。
7. handler 成功后标记 `PROCESSED` 并 ACK。
8. handler 可重试失败时保留 pending，不 ACK，记录失败。
9. Reclaim worker 认领 idle 超过 `claimIdle` 的 pending message。
10. 投递超过 `maxDeliveries` 后写入 DLQ，标记 `DEAD_LETTERED`，ACK 原消息。

所有 handler 必须可重复调用。后续业务 handler 如调用外部服务，必须设置超时并保证重复执行安全。

## 10. Knowledge Smoke 闭环

`crag-knowledge-service` 在 `smoke` Profile 下暴露：

```text
POST /api/v1/smoke/events
GET  /api/v1/smoke/events?runId=...
GET  /api/v1/smoke/events/{eventId}
```

`POST` 请求只接受受控 smoke 字段：

- `runId`：必填，用于 Docker 回归数据隔离。
- `message`：短测试文本，不允许承载敏感内容。
- `failMode`：`none / always / non_retryable`。

响应包含：

- `eventId`
- `runId`
- `outboxStatus`

查询响应包含诊断摘要：

- outbox 状态
- processed 状态
- handler attempt count
- 是否进入 DLQ
- 最后错误码和错误摘要

Smoke 查询端点不得默认回显完整 payload。它只用于非敏感测试事件，不代表真实业务事件诊断 API。

`failMode=always` 用于验证 retry、reclaim 和 DLQ。`failMode=non_retryable` 用于验证不可重试失败直接进入 DLQ。

默认 profile 下这些端点不得注册。

## 11. 自动装配与配置

`crag-event` 提供 Spring Boot auto-configuration，但 publisher 和 consumer 需要显式开关。

配置项：

```yaml
crag:
  event:
    publisher:
      enabled: false
    consumer:
      enabled: false
    stream-key: crag:event:knowledge
    dlq-stream-key: crag:event:knowledge:dlq
    group-name: knowledge-smoke
    consumer-name: knowledge-smoke-1
    batch-size: 20
    claim-idle: 30s
    max-deliveries: 3
    poll-interval: 1s
    backoff:
      initial: 1s
      max: 30s
```

装配规则：

- 未开启 publisher 时不创建 publisher scheduler。
- 未开启 consumer 时不创建 consumer scheduler。
- 开启 publisher 但缺 DataSource 或 RedisTemplate 时，启动失败信息必须明确。
- 开启 consumer 但没有 handler 时，允许启动但不轮询，并记录清晰日志。
- Health indicator 可关闭。开启后检查 DAO 表可访问和 Redis 可 ping。

## 12. 错误处理

基础设施错误码：

| 错误码 | 处理 |
| --- | --- |
| `REDIS_UNAVAILABLE` | Publisher 进入 `RETRY_WAIT`；Consumer 本轮停止或不 ACK。 |
| `MESSAGE_MALFORMED` | 写入 DLQ 并 ACK 原消息。 |
| `HANDLER_FAILED` | 保留 pending，等待 reclaim。 |
| `HANDLER_NON_RETRYABLE` | 写入 DLQ，标记 `DEAD_LETTERED`，ACK。 |
| `OUTBOX_CAS_CONFLICT` | 并发 claim 冲突，记录 debug/info，不算错误。 |
| `OUTBOX_EXHAUSTED` | 发布失败超过最大次数，outbox 进入 `DEAD`。 |

可重试：

- Redis 暂时不可用。
- Redis 发布失败。
- handler 可重试异常。

不可无限重试：

- 信封缺字段。
- payload 非法 JSON。
- payload version 不支持。
- handler 明确返回 non-retryable failure。

## 13. 可观测性与安全

可观测性：

- 可选 Actuator health indicator 检查 Redis 与事件表。
- 指标包含 outbox pending 数、oldest age、published/dead 数、publish failure 数、consumer processed/failed/dlq 数、pending reclaim 次数。
- 结构化日志携带 `eventId`、`eventType`、`consumerName`、`streamKey`、`traceId`。

安全：

- 日志不得记录完整 payload。
- Smoke endpoint 不接收任意 JSON payload，不回显真实业务 payload。
- Redis Streams 是传输层，不是业务事实来源。
- `crag-event` 不跨 schema 查询，不提供全局事件管理 API。
- DLQ 不表示业务补偿完成。补偿扫描、告警和人工处理策略留给生命周期可靠性计划。

## 14. 测试设计

### 14.1 `crag-event` 纯单元测试

建议文件：

- `EventEnvelopeTest`
- `OutboxEventStatusTest`
- `OutboxBackoffPolicyTest`
- `RedisStreamEventMapperTest`
- `ProcessedEventIdempotencyKeyTest`
- `EventHandlerResultTest`

覆盖：

- 信封必填字段校验。
- Snowflake `long` 到十进制字符串转换。
- payload 必须是合法 JSON 字符串。
- Outbox 合法和非法状态迁移。
- backoff 计算和最大退避上限。
- Redis entry 字段化映射。
- 幂等键默认格式稳定。
- handler 结果到 ACK/retry/DLQ 决策的映射。

### 14.2 JDBC/DAO 测试

建议文件：

- `JdbcOutboxEventDaoComponentTest`
- `JdbcProcessedEventDaoComponentTest`
- `OutboxPublisherServiceTest`

覆盖：

- `PENDING` 按 `nextAttemptAt <= now` 批量 claim。
- 同一事件并发 claim 只有一个成功。
- claim 后状态、version、claimed 字段正确。
- 未超时 `PUBLISHING` 不可被抢占。
- 超时 `PUBLISHING` 可重新 claim。
- 发布成功标记 `PUBLISHED`。
- 发布失败进入 `RETRY_WAIT` 并记录错误。
- attempt 达到上限进入 `DEAD`。
- `consumerName + eventId` 去重。
- `consumerName + idempotencyKey` 去重。
- `FAILED` 可在后续成功后更新为 `PROCESSED`。
- `DEAD_LETTERED` 不被普通成功路径覆盖。

H2 可用于轻量组件验证 DAO 行为，但不证明 PostgreSQL 方言兼容。真实 PostgreSQL 与 Redis 行为由 Docker smoke 回归证明。

### 14.3 Redis Streams 适配测试

建议文件：

- `RedisStreamPublisherTest`
- `RedisStreamConsumerTest`
- `RedisPendingReclaimerTest`
- `DeadLetterPublisherTest`

覆盖：

- publisher 将 `EventEnvelope` 转为 Redis fields。
- consumer 按 stream/group/consumer/batch 读取。
- malformed message 写 DLQ 并 ACK。
- handler success 写 `PROCESSED` 并 ACK。
- retryable failure 不 ACK，等待 reclaim。
- non-retryable failure 写 DLQ 并 ACK。
- reclaimer 只处理超过 `claimIdle` 的 pending message。
- delivery count 超过 `maxDeliveries` 后进入 DLQ。
- Redis 不可用时 publisher 回写 `RETRY_WAIT`，consumer 不误 ACK。

### 14.4 Spring 自动装配测试

建议文件：

- `EventAutoConfigurationComponentTest`
- `EventPublisherAutoConfigurationComponentTest`
- `EventConsumerAutoConfigurationComponentTest`

覆盖：

- publisher 未开启时不创建 scheduler。
- consumer 未开启时不创建 scheduler。
- 开启 publisher 但缺 DataSource/RedisTemplate 时失败信息明确。
- 开启 consumer 但无 handler 时启动成功但不轮询。
- 多 handler 按 event type 路由。
- 默认配置值稳定。
- health indicator 可关闭；开启后 Redis 不通或表缺失返回明确 detail。

### 14.5 Knowledge smoke 测试

建议文件：

- `KnowledgeEventSmokeControllerComponentTest`
- `KnowledgeEventSmokeProfileComponentTest`
- `KnowledgeSmokeEventHandlerTest`

覆盖：

- 默认 profile 下 `/api/v1/smoke/events` 不注册。
- smoke profile 下 `POST /api/v1/smoke/events` 创建测试事件。
- Controller 只接受受控 smoke 请求字段。
- `runId` 必填。
- 查询端点返回 outbox、processed、DLQ 摘要。
- smoke handler 成功时写入 processed 状态。
- `failMode=always` 返回 retryable failure。
- `failMode=non_retryable` 直接进入 DLQ。

### 14.6 Docker HTTP 冒烟回归

新增脚本：

- `scripts/tests/http/event_smoke_success_test.sh`
- `scripts/tests/http/event_smoke_dlq_test.sh`
- `scripts/tests/http/event_smoke_default_disabled_test.sh`

`event_smoke_success_test.sh`：

1. 生成唯一 `runId`。
2. 启动 smoke profile 下的 Knowledge 事件诊断实例、db 和 redis。
3. `POST /api/v1/smoke/events`，`failMode=none`。
4. 断言响应包含 `eventId` 和 `outboxStatus=PENDING`。
5. 轮询查询端点直到 outbox 为 `PUBLISHED`、processed 为 `PROCESSED`、dlq 为 false。
6. 超时输出诊断摘要和相关容器日志尾部。
7. 不清空表，不清 Redis，只按 `runId` 查询断言。

`event_smoke_dlq_test.sh`：

1. 生成唯一 `runId`。
2. `POST /api/v1/smoke/events`，`failMode=always`。
3. 轮询直到 outbox 为 `PUBLISHED`、processed 为 `DEAD_LETTERED`、dlq 为 true。
4. 断言 handler attempt count 不小于 `maxDeliveries`。
5. 通过 smoke endpoint 间接确认 DLQ 记录，不用 `redis-cli` 作为主断言。

`event_smoke_default_disabled_test.sh`：

1. 默认 Compose 启动下请求 Knowledge 正式服务 `/api/v1/smoke/events`。
2. 断言不可访问。
3. 证明 smoke Controller 没有进入默认服务。

## 15. 验收命令

plan17 最终验证至少包含：

```bash
./gradlew :crag-event:test
./gradlew :crag-knowledge-service:test --tests '*Smoke*' --tests '*Event*'
./gradlew test
./gradlew check
python3 scripts/validate_plans.py
python3 -m unittest scripts.tests.test_validate_module_dependencies scripts.tests.test_validate_constraints -v
scripts/tests/http/event_smoke_success_test.sh
scripts/tests/http/event_smoke_dlq_test.sh
scripts/tests/http/event_smoke_default_disabled_test.sh
```

如果新增 `knowledge-service-smoke` Compose profile 服务，必须同步更新 `docker-compose.yml`、`constraints/docker-structure.md` 和 HTTP 回归脚本。

## 16. plan17 任务切分建议

1. 创建 `crag-event` 模块与架构约束：Gradle、依赖白名单、package/docker 约束、基础 API 类型。
2. 实现 Outbox 与 processed_event JDBC 基础设施：表结构、DAO、CAS、状态机、单元/JDBC 测试。
3. 实现 Redis Streams publisher/consumer：字段化信封、publisher scheduler、consumer group、ACK、Reclaim、DLQ、配置。
4. 接入 Knowledge smoke 闭环：Knowledge schema 初始化、smoke Controller、test handler、默认 profile 隔离。
5. 补齐可观测性与校验器：health、metrics、logging 接入、ArchUnit/Python 校验器、约束文档同步。
6. 完成验证与交接：Gradle、Python、Plan 校验、Docker smoke 回归脚本和验收记录。

## 17. 风险与回滚

风险：

- 事件基础设施过度膨胀，提前侵入业务语义。
- H2 组件测试误导为 PostgreSQL 兼容证明。
- Redis fake 测试无法覆盖真实 `XREADGROUP`、`XPENDING`、`XCLAIM` 行为。
- Smoke Controller 若 profile 限制遗漏，会暴露诊断入口。
- Redis 约束文档若不同步，会继续声明 Redis 不承载事件传输。

回滚：

- plan17 不包含不可逆业务数据迁移。
- 可通过 revert `crag-event` 模块、Knowledge smoke 接入、Compose smoke 服务和约束文档改动回退。
- Knowledge smoke 表只承载测试事件；回滚时可保留无害残留，或由后续数据库初始化脚本版本处理。

## 18. 已确认决策

| 主题 | 决策 |
| --- | --- |
| 范围 | 基础设施闭环，不接真实业务事件 |
| 模块归属 | 新增 `crag-event` library module |
| 持久化 | 每服务本地 `outbox_event` 和 `processed_event` |
| 验证宿主 | `crag-knowledge-service` smoke-only 事件闭环 |
| Smoke Controller | 仅 `smoke` Profile 启用 |
| Redis 编码 | 字段化 Stream entry + JSON payload |
| Reclaim/DLQ | plan17 实现轻量完整语义 |
| 自动装配 | Spring Boot auto-configuration + 显式开关 |
| 测试 | 单元、JDBC、Redis 适配、auto-config、Knowledge smoke、Docker HTTP 回归分层覆盖 |
