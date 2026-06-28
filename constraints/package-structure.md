# CRAG-Demo Java 模块与包结构约束

> 本文档是 CRAG-Demo Java 模块职责、依赖方向、跨模块公开 API 和包语义的唯一维护入口。`AGENTS.md`、`CLAUDE.md`、计划文档和实现代码不得另行定义冲突规则。

## 一、文档定位

本文档包含三类信息，效力不同：

1. **架构硬约束**：规定目标模块职责、依赖白名单、公开 API 和禁止事项，新增代码必须遵守。
2. **当前实现索引**：只描述仓库中已经存在的模块、包和关键实现，用于导航，不构成未来设计承诺。
3. **已知偏差**：记录当前实现与目标约束之间尚未消除的差异，并关联负责迁移的 Plan。

尚未实现的类、包或模块不得写入“当前实现索引”。未来设计统一写入对应 Plan；只有已经确定为项目级架构规则的内容才进入本文档正文。

## 二、术语

- **模块**：`settings.gradle.kts` 中声明的 Gradle subproject。
- **RAG 内部业务包**：`crag-rag-service` 内按领域划分的 `ai.cerbur.crag.storage`、`retrieval`、`query`、`ingestion` 包。它们原为独立 Gradle 模块，由 `plan_16` 收口进 `crag-rag-service`，包名保持稳定，内部边界由 ArchUnit 在包级别强制。
- **公开 API**：跨模块或跨 RAG 内部包允许引用的 Java 类型，统一位于被依赖模块/包的 `api` 包及其子包。
- **内部实现**：不属于公开 API 的类型。即使 Java 可见性为 `public`，也不得跨模块或跨 RAG 内部包引用。
- **组合根**：负责组装 Spring Bean 和生成唯一可启动 jar 的 Application 模块。当前包括 `crag-rag-service`、`crag-access-service`、`crag-knowledge-service`、`crag-console-api` 和 `crag-open-api`。
- **smoke 包**：`crag-rag-service` 内 `ai.cerbur.crag.smoke` 包，承载仅在 `smoke` Profile 下启用的 legacy RAG HTTP 验证端点与异常映射。

`api` 表示跨边界可见边界，不表示其中的类型必须是 Java `interface`。只有存在替换实现、远程调用或第三方适配边界时才抽象接口；禁止为单一实现机械创建 `XxxService` / `XxxServiceImpl`。

## 三、目标模块职责

Base package 统一为 `ai.cerbur.crag`。

| 模块 | 职责 | 禁止事项 |
| --- | --- | --- |
| `crag-id` | 分布式 Snowflake ID 基础设施：实体类型注册、编解码、时钟回拨处理、Worker 租约和 readiness | 禁止依赖业务模块；禁止承载 HTTP Controller；禁止暴露 Repository |
| `crag-common` | 真正跨多个模块、无明确业务归属的稳定基础类型 | 禁止收纳业务 DTO、Entity、Service、Client、单模块工具或为绕开依赖环而搬入的类型 |
| `crag-platform-contracts` | 跨领域通用 Protobuf 基础契约（Platform Probe） | 禁止 Spring、Runtime 或业务依赖 |
| `crag-knowledge-contracts` | Knowledge 领域 Protobuf 与 gRPC 生成代码（KnowledgeBase、Document） | 禁止 Spring、Runtime、业务依赖或依赖 platform/其他 contracts |
| `crag-access-contracts` | Access 领域 Protobuf 与 gRPC 生成代码（Identity、Membership、ApiKey） | 禁止 Spring、Runtime、业务依赖或依赖 platform/其他 contracts |
| `crag-grpc-runtime` | 协议无关 gRPC Server/Client 生命周期、认证、Health、deadline | 禁止依赖 Contracts 或任何 Application 组合根 |
| `crag-event` | 领域无关可靠事件基础设施：事件信封、Outbox/processed_event DAO、Redis Streams publisher/consumer、Reclaim/DLQ 与 Spring auto-configuration | 禁止依赖任何业务 application module；禁止承载 HTTP Controller 或业务事件类型 |
| `crag-rag-service` | RAG 业务组合根，唯一承载 storage/retrieval/query/ingestion 内部业务包、smoke 验证 HTTP、gRPC Server 与 Platform Probe | 禁止被 Access/Knowledge 依赖；legacy HTTP 仅限 smoke Profile |
| `crag-access-service` | Access 组合根：身份/会话/Membership/API Key Core、DAO、安全适配器、gRPC Provider、失效事件生产端、smoke 验证 HTTP、Schema readiness | 禁止依赖 Knowledge/RAG/Console/Open Service module |
| `crag-knowledge-service` | Knowledge 组合根、gRPC Server、Platform Probe、Schema readiness | 禁止 RAG 业务依赖 |
| `crag-console-api` | 正式 Console HTTP 入口、下游 Probe readiness | 禁止 DataSource、业务 Controller |
| `crag-open-api` | 正式 Open HTTP 入口、下游 Probe readiness | 禁止 DataSource、业务 Controller |

五个 Application 组合根各自生成独立可启动 jar；`crag-id`、`crag-common`、`crag-platform-contracts`、`crag-knowledge-contracts`、`crag-grpc-runtime` 和 `crag-event` 为 library module。正式 HTTP 入口由 `crag-console-api` 与 `crag-open-api` 承担；现有 AdminRag 写入与 UserQuery 行为只在 `crag-rag-service` 的 `smoke` Profile 下作为验证端点。

### 3.1 `crag-rag-service` 内部包边界

`plan_16` 将 `crag-storage` / `crag-retrieval` / `crag-query` / `crag-ingestion` / `crag-api` / `crag-smoke` 六个 Gradle 模块收口进 `crag-rag-service`，包名保持稳定，包级边界由 `ModuleBoundaryArchitectureTest` 强制：

| 内部包 | 职责 | 包级禁止事项 |
| --- | --- | --- |
| `storage` | JPA Entity、Repository、DAO 和数据库投影 | `storage.repository` 只允许 storage 包内部访问；禁止承载检索、入库或问答业务编排 |
| `ingestion` | AdminRag 写入、ChunkSplit、Sparse/Dense 索引构建和 Cron 编排 | 禁止暴露 HTTP Controller；只能通过 `retrieval.api` 访问检索能力 |
| `retrieval` | Embedding、Sparse/Dense 召回、RRF、Rerank 和检索门面 | 禁止生成最终回答；禁止让普通调用方感知内部检索阶段 |
| `query` | UserQuery、Context、Prompt、LLM 调用和回答编排 | 禁止直接访问 Storage 内部或 Retrieval 内部阶段，只能通过 `retrieval.api` |
| `smoke` | 仅在 `smoke` Profile 下启用的 legacy RAG HTTP 验证端点、DTO 与异常映射 | 禁止承载正式业务能力、默认启用或生成独立启动 jar |

## 四、模块依赖白名单

模块依赖采用“默认禁止、显式放行”。每个模块只允许声明下表中的直接项目依赖；未列出的依赖一律禁止，不得借助传递依赖越界访问。RAG 内部业务包之间的依赖不再经过 Gradle project 边界，而是由 3.1 节包级规则约束。

| 调用模块 | 允许直接依赖 |
| --- | --- |
| `crag-id` | 无 |
| `crag-common` | 无 |
| `crag-platform-contracts` | 无 |
| `crag-knowledge-contracts` | 无 |
| `crag-access-contracts` | 无 |
| `crag-grpc-runtime` | 无 |
| `crag-event` | 无 |
| `crag-rag-service` | `crag-platform-contracts`、`crag-grpc-runtime`、`crag-common`、`crag-id`、`crag-event`、`crag-knowledge-contracts`、`crag-rag-contracts` |
| `crag-access-service` | `crag-access-contracts`、`crag-platform-contracts`、`crag-grpc-runtime`、`crag-common`、`crag-id`、`crag-event` |
| `crag-knowledge-service` | `crag-knowledge-contracts`、`crag-rag-contracts`、`crag-platform-contracts`、`crag-grpc-runtime`、`crag-common`、`crag-event` |
| `crag-console-api` | `crag-platform-contracts`、`crag-access-contracts`、`crag-knowledge-contracts`、`crag-rag-contracts`、`crag-grpc-runtime`、`crag-common` |
| `crag-open-api` | `crag-platform-contracts`、`crag-access-contracts`、`crag-rag-contracts`、`crag-grpc-runtime`、`crag-common` |

附加硬约束：

- 禁止任何模块循环依赖。
- Application 组合根的装配依赖不授予业务调用权限；`crag-grpc-runtime` 不得依赖任何 Application 组合根。
- RAG 内部业务包的跨包调用必须遵守 3.1 节，不得把包内实现当公开 API 跨包传播。
- 新增或调整项目依赖时，必须先更新对应 Plan 和本文档，再修改 `settings.gradle.kts` 或模块 `build.gradle.kts`。

## 五、跨模块与跨包公开 API

### 5.1 通用规则

- 跨模块调用以及 `crag-rag-service` 内部业务包之间的调用，只能引用被依赖模块/包的 `ai.cerbur.crag.<module>.api` 包及其子包类型。
- 非 `api` 包默认属于模块/包内部；Java `public` 只表示语言可见性，不等于架构公开。
- 公开 API 必须保持窄边界，只暴露调用方完成业务所需的门面、契约、请求和结果类型。
- 外部协议或供应商 SDK 类型不得穿透公开 API。
- 跨包结果优先使用所属包的 API DTO、业务对象或结果类型，不新增 Entity 泄漏。

当前公开入口（均位于 `crag-rag-service` 内部业务包）：

```text
ai.cerbur.crag.ingestion.api
├── AdminRagService
└── AdminRagResult

ai.cerbur.crag.retrieval.api
├── RetrievalService
├── result/
│   └── ChunkSearchResult
└── embedding/
    ├── EmbeddingClient
    └── EmbeddingException

ai.cerbur.crag.query.api
├── UserQueryService
├── UserQueryResult
├── QuerySource
├── InvalidQueryException
└── LlmUnavailableException
```

`EmbeddingClient` 是 Retrieval 对外提供的能力契约。当前实现可以调用 HTTP Sidecar；未来可迁移为 RPC 或独立 SDK，但 `ingestion` 包只能依赖 `retrieval.api.embedding`，不得依赖具体传输实现。

### 5.2 Storage 的受控架构例外

`storage` 包尚未建立完整 `api` 子包，`ingestion` 通过根包 DAO 和必要的 `storage.result` / `storage.entity` 类型访问存储能力属于受控架构例外，必须满足：

- 例外只覆盖当前架构测试白名单列举的既有调用；禁止新增 Entity 跨包传播。
- `storage.repository` 永远只允许 storage 包内部访问。
- 上层不得修改 Entity 后自行持久化；状态变化必须通过 DAO 方法完成。
- 新增跨包返回类型优先使用投影或结果类型，不得扩大 Entity 传播范围。
- 是否收口 Storage API 由实际耦合问题驱动；只有跨包耦合恶化时才通过对应 Plan 收口，不为形式统一提前增加映射层。
- 新增需求不得扩大白名单。

## 六、固定包语义

项目只统一有明确架构含义的包名，不要求每个模块机械套用相同目录模板。

| 包名 | 语义 |
| --- | --- |
| `api` | 跨模块或跨 RAG 内部包的公开契约、门面及其输入输出 |
| `controller` | HTTP 入口；legacy 验证端点仅允许存在于 `crag-rag-service` 的 `ai.cerbur.crag.smoke.controller`，正式入口由 `crag-console-api` / `crag-open-api` 承担 |
| `repository` | Spring Data 数据映射；仅允许存在于 `storage` 包 |
| `entity` | 持久化模型；仅允许由 `storage` 包定义 |
| `result` | 某处理阶段已经产生的结果，不得复用尚未产生语义的外层大类型 |
| `internal` | 显式隐藏的实现；普通包即使未命名为 `internal` 也默认模块/包内部 |

禁止新增语义含混的 `util`、`helper`、`manager`、`misc` 包。通用代码应优先归入拥有该行为的业务模块；无法明确归属时先重新检查抽象是否必要，而不是直接放入 `crag-common`。

## 七、`crag-common` 收纳门槛

新增类型进入 `crag-common` 前必须同时满足：

- 至少有两个实际消费模块，而非假设中的未来调用方。
- 类型没有合理的业务模块归属。
- 类型稳定、无业务流程含义，且不会引入反向依赖。

不满足以上条件的类型留在其业务模块。禁止以“避免循环依赖”为理由把领域类型移动到 `crag-common`；应修正依赖方向或公开 API。

## 八、`smoke` 验证例外

`crag-rag-service` 的 `smoke` 包用于保留现有冒烟流程和内部阶段诊断能力，规则如下：

- Controller 和 `GlobalExceptionHandler` 必须统一受类级 `@Profile("smoke")` 限制。
- 默认应用启动不得暴露 `/api/v1/smoke/**`，所有 legacy RAG HTTP 验证 URI 统一使用该前缀。
- 只允许通过原服务的 `smoke` Profile 激活：`CRAG_SERVICE_PROFILES=smoke docker compose up -d --build rag-service`（plan_21/21.11 收敛单服务 Smoke 拓扑，原服务固定暴露本地诊断端口 8082）。
- 允许直接调用 DAO、Sparse/Dense/RRF/Rerank 等内部组件，但每个端点必须明确标注验证阶段。
- 禁止在冒烟端点中实现正式业务规则，禁止被正式 API 复用。
- 单元测试仍保留在各业务包；`smoke` 包不替代单元测试或正式 API 的端到端测试。

## 九、当前实现索引

本节只反映当前源码事实。完整文件列表以源码为准；这里只列包职责、公开调用点和有架构意义的关键实现。

### `crag-id`

```text
ai.cerbur.crag.id
├── api/
│   ├── CragIdGenerator        — 公开发号入口
│   ├── CragIdParser           — 公开解析入口与 CragIdParts
│   ├── IdEntityType           — 实体类型注册（LEGACY_DOCUMENT、CHUNK、USER、LOGIN_ACCOUNT、TENANT、TENANT_MEMBERSHIP、REFRESH_SESSION、API_KEY、ACCESS_EVENT）
│   └── InvalidCragIdException — 请求解析与实体校验失败异常
└── internal/
    ├── SnowflakeLayout        — bit shift/mask/epoch 编解码
    ├── SnowflakeSequence      — timestamp/sequence 状态机
    ├── MonotonicClock         — 可测试时钟抽象
    ├── SystemMonotonicClock   — 生产用 SystemClock 实现
    └── ClockRollbackException — 大时钟回拨停止发号异常
```

### `crag-common`

```text
ai.cerbur.crag.common.dto.result
├── Response
├── ResponseCode
│   └── 含 LLM_UNAVAILABLE = 50201；plan_21/21.6 追加正式 HTTP 入口业务码（40101/40102/40301/40901/40902/41301/41501/50301/50401）
ai.cerbur.crag.common.dto.error
├── ErrorDetail                       — plan_21/21.6 统一错误详情（message/traceId/reason/retryable/fieldErrors）
└── FieldErrorDetail                  — plan_21/21.6 字段级校验错误（不含敏感原值）
```

### `crag-rag-service`

`plan_16` 收口后，`crag-rag-service` 是唯一承载 RAG 业务与 smoke 验证的 Gradle 模块；下列包原为独立 subproject。

```text
ai.cerbur.crag.rag
├── app/                                — RagServiceApplication（组合根）
├── probe/                              — PlatformProbeGrpcService、ExpectedSchemaHealthIndicator
├── grpc/                               — 正式 RAG gRPC Provider（plan_21/21.4）
│   ├── provider/                       — RagQueryGrpcProvider、IngestionStatusGrpcProvider、DecimalId
│   ├── mapper/                         — RagQueryMapper（UserQueryResult → QueryResponse、ParentEvidence → Citation）
│   ├── error/                          — RagErrorMapper（稳定 gRPC Status）
│   └── security/                       — RagRpcAuthorizer（Open 可 Query，Knowledge 可 Status，其他拒绝）
└── app.arch/                           — ModuleBoundaryArchitectureTest（包边界与依赖校验）

ai.cerbur.crag.storage                  — 原 crag-storage
├── ChunkDao / ChunkEmbeddingDao / ChunkFtsDao / IngestionHeadDao / IngestionJobDao / IngestionJobConflictException
├── entity/                            — Chunk、ChunkEmbedding、ChunkFts、状态与 Converter、IngestionJob、DocumentIngestionHead（plan_21/21.4）
├── repository/                        — Spring Data Repository（仅包内访问）
└── result/                            — Dense / Sparse / Parent 投影；IngestionHead（plan_21/21.4）

ai.cerbur.crag.retrieval                — 原 crag-retrieval
├── api/                               — RetrievalService / result.ChunkSearchResult / result.ParentEvidenceResult / embedding.EmbeddingClient / embedding.EmbeddingException（跨包公开入口）
├── embedding/                         — SidecarEmbeddingClient 等内部实现
├── sparse/ / dense/                   — 双路召回
├── rrf/ / rerank/                     — 融合与重排
├── bo/                                — ChunkBO
└── result/                            — 各检索阶段结果（SparseSearchResult/DenseSearchResult/RrfFusionResult）

ai.cerbur.crag.query                    — 原 crag-query
├── api/                               — UserQueryService / UserQueryResult / QuerySource / InvalidQueryException / LlmUnavailableException（跨包公开入口）
├── context/                           — ContextBuilder / QueryContext / SourceBoundaryFactory
├── prompt/                            — PromptBuilder
├── reference/                         — ReferenceAnalyzer / ReferenceAnalysis
└── llm/
    ├── contract/                      — 供应商中立 LLM 契约
    ├── adapter/
    │   ├── stub/                      — StubLlmAdapter
    │   └── deepseek/                  — DeepSeekAnthropicLlmAdapter（Spring AI 2.0.0 Anthropic 协议）
    └── config/                        — QueryProperties / DeepSeekApiKey / QueryLlmConfiguration

ai.cerbur.crag.ingestion                — 原 crag-ingestion
├── api/                               — AdminRagService / AdminRagResult（跨包公开入口，固定 smoke KB）
├── chunk.split/                       — ChunkSplit 能力与数据类型
├── consumer/                          — DOC_UPLOADED EventHandler / payload 解析（Plan 19）
├── job/                               — IngestionJobService / Orchestrator / Resolution / FailureCategory
├── head/                              — IngestionHeadService / HeadAdvanceResult / HeadAdvanceOutcome / IngestionStatusResult（plan_21/21.4 head 单调推进与超时终态化支撑）；StaleIndexCleaner（plan_21/21.5 retry 清理旧失败残留）
├── knowledge/                         — Knowledge gRPC ReadDocumentFile client（只依赖 contracts）
├── producer/                          — INGESTION_PROCESSING/READY/FAILED 状态事件 Outbox 写入（Plan 19）
├── dense/                             — DenseEmbeddingService
└── cron/                              — Dense / Sparse 定时编排（索引完成后推进 Job READY）

ai.cerbur.crag.smoke                    — 原 crag-api + crag-smoke
├── controller/                        — AdminRagController / UserQueryController / TestController / RagIngestionSmokeController（@Profile("smoke")）
├── controller.advice/                 — GlobalExceptionHandler（@Profile("smoke")）
├── ingestion/                         — RagIngestionSmokeService（router2 诊断：job/事件/chunk 计数，@Profile("smoke")）
├── dto.rag/                           — AdminRagRequest / AdminRagResponse
└── dto.query/                         — UserQueryRequest（含可选 knowledgeBaseId） / UserQueryResponse / QuerySourceResponse
```

### `crag-platform-contracts`

```text
ai.cerbur.crag.contracts.platform.v1
├── PlatformProbeServiceGrpc            — 生成的 gRPC Stub
├── PlatformProbeRequest                — 生成的 Protobuf 消息
└── PlatformProbeResponse               — 生成的 Protobuf 消息
```

### `crag-knowledge-contracts`

```text
ai.cerbur.crag.contracts.knowledge.v1
├── KnowledgeBaseServiceGrpc            — 生成的 KnowledgeBase gRPC Stub（Create/Get/List）
├── DocumentServiceGrpc                 — 生成的 Document gRPC Stub（Upload 流式/Get/List/Read 流式）
└── KnowledgeBase / Document 等消息     — 生成的 Protobuf 请求与响应消息
```

### `crag-access-contracts`

```text
ai.cerbur.crag.contracts.access.v1
├── IdentityServiceGrpc                 — 生成的 Identity gRPC Stub（Register/Login/Refresh/Logout/GetJwtVerificationKeys）
├── MembershipServiceGrpc               — 生成的 Membership gRPC Stub（Authorize/Add/ChangeRole/Remove/Get/List）
├── ApiKeyServiceGrpc                   — 生成的 ApiKey gRPC Stub（Scope/Key 生命周期与 Authenticate）
└── AccessErrorCode 等消息与枚举        — 生成的 Protobuf 稳定业务错误码、TenantAction、角色/状态枚举
```

### `crag-grpc-runtime`

```text
ai.cerbur.crag.grpc.runtime
├── identity/                           — GrpcCallerIdentity、GrpcCallerContext
├── server/                             — GrpcServerLifecycle、GrpcServiceAuthenticationInterceptor、GrpcServerProperties
├── client/                             — GrpcChannelFactory、DefaultGrpcChannelFactory、DeadlineGuardClientInterceptor、GrpcClientProperties
└── config/                             — GrpcServerConfiguration、GrpcClientConfiguration
```

### `crag-event`

```text
ai.cerbur.crag.event
├── api/                                — 领域无关事件契约
│   ├── EventEnvelope                   — 稳定事件信封，eventId/resourceId/operationVersion 十进制字符串边界
│   ├── EventHandler                    — 事件处理契约（必须可重复调用）
│   ├── EventHandlerResult              — success / retryableFailure / nonRetryableFailure 与 outcome 决策
│   ├── OutboxEventStatus               — PENDING/PUBLISHING/PUBLISHED/RETRY_WAIT/DEAD 状态机
│   ├── ProcessedEventStatus            — PROCESSED/FAILED/DEAD_LETTERED
│   ├── ProcessedEventIdempotencyKey    — eventType:resourceType:resourceId:operationVersion 稳定幂等键
│   └── EventErrorCode                  — REDIS_UNAVAILABLE/MESSAGE_MALFORMED/HANDLER_FAILED/HANDLER_NON_RETRYABLE/OUTBOX_CAS_CONFLICT/OUTBOX_EXHAUSTED
├── jdbc/                               — 本地 schema 内 Outbox/processed_event 数据访问与发布编排
│   ├── JdbcOutboxEventDao              — insert、claimBatch（逐条版本 CAS）、markPublished/markRetryWait/markDead
│   ├── JdbcProcessedEventDao           — insertPlaceholder 幂等占位、markProcessed/markFailed/markDeadLettered
│   ├── OutboxPublisherService          — claim→投递→标记编排（不绑定传输）
│   ├── OutboxBackoffPolicy             — 指数退避，封顶 max
│   ├── OutboxEventRecord/OutboxClaim/ProcessedEventRecord/PublishResult — 不可变行/结果快照
│   └── EventPublishAction              — 投递动作函数式契约（Redis 实现见 redis 包）
└── redis/                              — Redis Streams 传输层
    ├── RedisStreamOps / RedisTemplateStreamOps — Stream 命令抽象与 StringRedisTemplate 实现
    ├── RedisStreamEventMapper          — 字段化 Stream entry 与 EventEnvelope 互转
    ├── RedisStreamEventPublisher       — 实现 EventPublishAction，写入 Redis Stream
    ├── RedisStreamEventConsumer        — consumer group 读取、幂等、handler 调度与 ACK
    ├── EphemeralRedisStreamConsumer    — 天然幂等 handler 模式（无 JDBC processed_event，plan_21/21.10）：成功 ACK、retry 留 pending、malformed/nonretry DLQ
    ├── RedisPendingReclaimer           — pending idle 重领与超 delivery 的 DLQ
    └── DeadLetterPublisher             — 写入 DLQ stream
```

`ai.cerbur.crag.event.spring`（EventProperties、EventAutoConfiguration、EventHealthIndicator、EventMetrics 与 `META-INF/spring/...AutoConfiguration.imports`）提供 Spring Boot 自动装配，publisher/consumer 经 `crag.event.*` 显式启用。Knowledge smoke 接入由 `crag-knowledge-service` 的 smoke 包承载。

### `crag-access-service`

```text
ai.cerbur.crag.access
├── AccessServiceApplication            — 组合根主类（根包，JPA + gRPC + crag-id + crag-event）
├── probe/                              — PlatformProbeGrpcService、ExpectedSchemaHealthIndicator
├── core/                               — 业务用例与核心规则（plan_20）
│   ├── identity/                       — IdentityPolicy、IdentityService、注册/认证、统一凭据异常
│   ├── membership/                     — MembershipService、TenantAction、TenantPermissionPolicy、MembershipRole/Result、最后 OWNER 保护
│   ├── session/                        — AuthenticationService、RefreshSessionService、JwtIssuer 契约、TokenPair
│   └── apikey/                         — ApiKeyService、ApiKeyPolicy、Scope/Key 生命周期与鉴权
├── dao/                                — User/Account/Tenant/Membership/RefreshSession/ApiKeyScope/ApiKey DAO
│   ├── entity/                         — 七张 Access 业务表 Entity
│   └── repository/                     — Spring Data Repository（仅 DAO 调用，含版本 CAS 与悲观锁）
├── security/                           — PasswordHasher/SecretHmac/SecretGenerator、Argon2/HMAC/SecureRandom 实现、accessSecrets 就绪检查
│   └── jwt/                            — AccessJwtKeyMaterial、JwtIssuerImpl（RS256 签发）
├── grpc/                               — gRPC 协议暴露
│   ├── provider/                       — IdentityGrpcProvider、MembershipGrpcProvider、ApiKeyGrpcProvider、DecimalId
│   ├── mapper/                         — IdentityMapper、MembershipMapper、ApiKeyMapper
│   ├── error/                          — AccessErrorMapper（稳定 gRPC Status）
│   └── security/                       — AccessRpcAuthorizer（Console/Open API 调用方收紧）
├── producer/                           — ApiKeyInvalidationOutboxWriter、ApiKeyInvalidatedPayload、AccessEventTypes
├── metrics/                            — AccessMetrics（认证/复用/权限/鉴权/失效事件计数）
└── controller/smoke/                   — AccessSmokeController、AccessSmokeExceptionHandler、dto（@Profile("smoke")，/api/v1/smoke/access）
```

### `crag-knowledge-service`

```text
ai.cerbur.crag.knowledge
├── KnowledgeServiceApplication         — 组合根主类（根包，JPA + gRPC）
├── probe/                              — PlatformProbeGrpcService、ExpectedSchemaHealthIndicator
├── core/                               — 业务用例与核心规则
│   ├── knowledgebase/                  — KnowledgeBaseService、KnowledgeBaseResult、KnowledgeBaseNotFoundException
│   ├── document/                       — DocumentUploadService/Command/Policy、DocumentResult、DocumentQueryService、FileType、UploadHandle
│   ├── file/                           — FileReadService、FileRead
│   └── ingestion/                      — IngestionStatus、IngestionStateMachine、IngestionApplyService、IngestionProjection、IngestionStatusEvent、IngestionApplyResult、IngestionTransitionDecision/Outcome（plan_21/21.3）；RetryPolicy、RetryDecision、RetryNotAllowedException、IngestionRetryService、IngestionRetryConfiguration（plan_21/21.5）
├── dao/                                — KnowledgeBaseDao/DocumentDao/FileObjectDao、VersionConflictException
│   ├── entity/                         — KnowledgeBase/Document/FileObjectEntity
│   └── repository/                     — Spring Data Repository（仅 DAO 调用）
├── filestore/                          — FileStore/LocalFileStore、StorageKeyGenerator、TempFileSink、CompletedUpload
├── grpc/                               — gRPC 协议暴露
│   ├── provider/                       — KnowledgeBaseGrpcProvider、DocumentGrpcProvider、DecimalId
│   ├── mapper/                         — KnowledgeBaseMapper、DocumentMapper
│   └── error/                          — GrpcErrorMapper
├── reconcile/                          — IngestionReconcileService、IngestionReconcilerScheduler、ReconcilerProperties、RagIngestionStatusClient/GrpcRagIngestionStatusClient、ReconcileSummary/ItemResult/Outcome（plan_21/21.5）
├── metrics/                            — IngestionRecoveryMetrics（plan_21/21.5 retry/timeout/reconcile 计数器）
├── consumer/                           — IngestionStatusEventHandler、IngestionStatusPayload、InvalidIngestionStatusPayloadException（plan_21/21.3，@Profile("smoke")）
├── producer/                           — DocUploadedOutboxWriter、DocumentUploadedPayload、KnowledgeEventTypes、KnowledgeBaseCreatedOutboxWriter、KnowledgeBaseCreatedPayload
├── controller/
│   └── smoke/                          — KnowledgeSmokeController、KnowledgeSmokeExceptionHandler、dto（@Profile("smoke")，/api/v1/smoke/knowledge）
└── smoke/                              — 仅 smoke Profile 启用的事件诊断闭环（plan_17）
    ├── controller/                     — KnowledgeEventSmokeController（/api/v1/smoke/events）
    ├── dto/                            — KnowledgeSmokeEvent* DTO、KnowledgeSmokeFailMode
    └── event/                          — KnowledgeSmokeEventService、KnowledgeSmokeEventHandler
```

### `crag-console-api`

```text
ai.cerbur.crag.console
├── app/                                — ConsoleApiApplication
├── probe/                              — DownstreamConnectivityHealthIndicator
├── config/                             — ProbeExecutorConfiguration
├── auth/                               — Console Auth HTTP 切片（plan_21/21.6）
│   ├── controller/                     — AuthController（register/login/refresh/logout/me）
│   ├── dto/                            — AuthResponse、UserResponse、TenantSummaryResponse、RegisterRequest、LoginRequest
│   └── service/                        — AccessIdentityClient（gRPC adapter，含 listTenantsPage plan_21/21.7）、RefreshCookieService、OriginGuard
├── tenant/                             — Console Tenant HTTP 切片（plan_21/21.7）
│   ├── controller/                     — TenantController（GET /api/v1/tenants）
│   └── dto/                            — TenantListResponse
├── membership/                         — Console Membership HTTP 切片（plan_21/21.7）
│   ├── controller/                     — MembershipController（list/add/change-role/remove）
│   ├── dto/                            — MemberResponse、AddMemberRequest、ChangeMemberRoleRequest、MembersListResponse
│   └── service/                        — AccessMembershipClient（gRPC adapter，actor 只来自 ConsolePrincipal）
├── knowledge/                          — Console KnowledgeBase HTTP 切片（plan_21/21.8）
│   ├── controller/                     — KnowledgeBaseController（list/create/get）
│   ├── dto/                            — KnowledgeBaseResponse、KnowledgeBaseListResponse、CreateKnowledgeBaseRequest
│   └── service/                        — KnowledgeBaseOrchestrator（Authorize→Create→EnsureScope，部分成功 apiKeyReady=false）
├── document/                           — Console Document HTTP 切片（plan_21/21.8）
│   ├── controller/                     — DocumentController（list/upload/get/retry）
│   ├── dto/                            — DocumentResponse、DocumentListResponse
│   └── service/                        — KnowledgeDocumentClient（multipart→gRPC streaming）、UploadValidation（10MiB/类型/UTF-8 校验）
├── apikey/                             — Console API Key HTTP 切片（plan_21/21.9）
│   ├── controller/                     — ApiKeyController（list/get/create/disable/enable/rotate/revoke）
│   ├── dto/                            — ApiKeyResponse、ApiKeyListResponse、CreatedApiKeyResponse（一次性 completeKey，toString 屏蔽）、CreateApiKeyRequest
│   └── service/                        — ApiKeyOrchestrator（Authorize MANAGE_API_KEY→EnsureScope→Access Key，OWNER-only，状态冲突 409）
├── security/                           — Console JWT 本地验签与 Bearer filter（plan_21/21.6）
│   ├── jwt/                            — JwtVerificationKeyCache、AccessJwtVerifier、AccessJwtKeyRefresher
│   └── filter/                         — BearerTokenAuthenticationFilter
├── advice/                             — GlobalExceptionHandler（Console 专属，共享 crag-common 的 ErrorDetail/ResponseCode）
├── config/multipart/                   — ConsoleUploadConfiguration、ConsoleUploadProperties（multipart 大小上限，plan_21/21.8）
└── grpc/                               — Console 下游 gRPC client 配置（plan_21/21.6）
    └── config/                         — ConsoleGrpcClientConfiguration（Access/Knowledge/RAG channel + stub Bean + per-use-case deadline）
```

### `crag-open-api`

```text
ai.cerbur.crag.open
├── app/                                — OpenApiApplication（排除 DataSource/JPA autoconfig，无数据库）
├── probe/                              — DownstreamConnectivityHealthIndicator
├── config/                             — ProbeExecutorConfiguration、OpenAuthCacheConfiguration（缓存 Bean + Ephemeral 消费调度器 + Micrometer Metrics，plan_21/21.10）
├── authcache/                          — Open API Key 本地缓存（plan_21/21.10）
│   ├── ApiKeyAuthCache                 — SHA-256 指纹键、TTL/容量、Key/Scope 定向 eviction、版本水位
│   ├── CachedApiKey                    — 缓存值（定位 + 版本水位，不含完整 Key）
│   └── OpenAuthCacheProperties         — ttl/capacity 配置（默认 30s/10000）
├── auth/                               — Open 鉴权切片（plan_21/21.10）
│   └── service/                        — AccessApiKeyClient（gRPC AuthenticateApiKey）、OpenApiKeyAuthService（缓存→Access 编排）、BearerApiKeyExtractor
├── consumer/                           — Open 失效消费切片（plan_21/21.10，天然幂等 + EphemeralRedisStreamConsumer，无 DB）
│   ├── ApiKeyInvalidationEventHandler  — API_KEY_INVALIDATED handler（定向 evict + 版本水位）
│   ├── ApiKeyInvalidationPayload       — payload 解析（镜像 Access 生产端）
│   └── InvalidApiKeyInvalidationPayloadException
├── query/                              — Open Query HTTP 切片（plan_21/21.10）
│   ├── controller/                     — QueryController（POST /api/v1/query）
│   ├── dto/                            — QueryRequest、QueryResponse、CitationResponse
│   └── service/                        — RagQueryClient（gRPC Query + excerpt 500 截断）、OpenQueryService（auth→query 编排）
├── grpc/                               — Open 下游 gRPC client 配置（plan_21/21.10）
│   └── config/                         — OpenGrpcClientConfiguration（Access/RAG channel Bean）
└── advice/                             — GlobalExceptionHandler（Open 专属，共享 crag-common ErrorDetail/ResponseCode）
```

模块与包边界由 `ModuleBoundaryArchitectureTest`、Gradle 模块依赖校验器和框架依赖校验器共同验证。

## 十、已知偏差

| 偏差 | 受控边界 | 退出条件 |
| --- | --- | --- |
| `storage` 包尚无统一 `api` 子包，`ingestion` 仍通过根包 DAO 和少量 storage 类型访问 | 只允许 5.2 节定义的现有调用；禁止 Repository 外泄和新增 Entity 传播 | 实际跨包耦合需要独立 Storage API 时，通过对应 Plan 收口并删除例外 |

## 十一、维护与自动校验

- 新增、移动或重命名模块和公开 API 时，必须同步更新本文档。
- 内部实现类的普通增删无需逐项更新；只有包职责或关键架构实现变化时才更新索引。
- 模块边界变化必须同步更新 `settings.gradle.kts`、相关 `build.gradle.kts`、Plan 和必要的架构决策记录。
- smoke Controller 位置与 `@Profile("smoke")`、Repository 内聚、`ingestion` / `query` 只通过 `retrieval.api` 访问、代码依赖无环和 Access/Knowledge 禁止依赖 RAG 必须由 ArchUnit 验证。
- Gradle project dependency 声明白名单必须由独立轻量校验器验证；不得假设 ArchUnit 能发现未被代码引用的多余 Gradle 依赖。
- 架构测试中的临时例外必须关联未完成任务；禁止无期限保留宽泛豁免。
- 涉及测试运行方式时同时遵守 [`test-workflow.md`](./test-workflow.md)；涉及 Java 代码写法时同时遵守 [`code-style.md`](./code-style.md)。
