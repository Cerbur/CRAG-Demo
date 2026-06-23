---
workflow_version: 3
plan_id: plan_15
type: main
status: ready
created: 2026-06-24
updated: 2026-06-24
---

# plan_15 — 分布式 Snowflake ID 与 RAG ID 切换

> **For agentic workers:** 执行本计划必须先读取 `skill/execute-crag-plan/SKILL.md`；实现步骤使用测试先行、任务级提交和独立验收交接。

**Goal**：建立服务域内唯一的 Snowflake ID 基础设施，并把当前 RAG `docId`、`chunkId`、`parentChunkId` 从 UUID/字符串切换为 `BIGINT`。

**Architecture**：新增 `crag-id` 库模块承载实体类型注册、Snowflake 编解码、时钟回拨处理、Redis Worker 租约和健康状态；RAG 组合根启用 `rag:LEGACY_DOCUMENT` 与 `rag:CHUNK` 两类发号器，HTTP 边界继续用 decimal string 表达 ID。Redis 在本计划只承担 Worker lease，不承担事件、缓存号段或业务状态。

**Tech Stack**：Java 21、Spring Boot 4.1.0、Spring Framework 7、Gradle 9.4.1、PostgreSQL 17、Redis、Docker Compose。

## 全局实现约束

- ID 唯一性只保证在 `服务域 + 实体类型` 内成立；不同服务域出现相同 numeric ID 可接受。
- Snowflake bit layout 固定为 `sign 1 | entity type 8 | timestamp 41 | worker 4 | sequence 10`，epoch 固定为 `2026-01-01T00:00:00Z`。
- Entity type 写入 ID 高位；service domain 不写入 ID，只进入 Redis lease namespace。
- Redis key namespace 固定为 `crag:id:{serviceDomain}:{entityType}`；默认 lease TTL `30s`，renew interval `10s`。
- 时钟回拨 `<= 5ms` 阻塞等待追平；回拨 `> 5ms` 停止发号并使 readiness `DOWN`。
- RAG ID 切换不做热迁移、双写、兼容查询或历史数据迁移；开发/demo 旧 RAG 数据允许被重建或移除。
- HTTP DTO 与请求响应边界中的业务 ID 使用 decimal string；Java 内部和数据库使用 `long` / `BIGINT`。
- `requestId`、`traceId` 等观测 ID 保持 UUID/字符串，不纳入 Snowflake 业务 ID。
- 新增 Java 代码遵守 `constraints/code-style.md`；持久化遵守 `constraints/persistence-style.md`；测试遵守 `constraints/test-workflow.md`。

## 背景与目标

`plan_14` 已完成五进程骨架、独立 Schema、gRPC Probe 与 Docker 拓扑，但明确未实现 Snowflake、Redis Worker 租约或 UUID 到 `long` 的迁移。后续 Knowledge、Access、事件和双 API 都需要稳定的业务 ID 语义；如果继续让 RAG 旧 UUID 链路存在太久，后续计划会反复携带兼容成本。

本计划采用 demo 友好的冷切换策略：先建立可复用 ID 基础设施，再立即把当前 RAG 持久化 ID 改为 numeric ID。因为当前项目是开发过程 demo，用户明确接受旧 RAG 数据直接丢弃，所以本计划不构建复杂迁移链路。

完成后，RAG 写入会为文档分配 `LEGACY_DOCUMENT` ID，为 parent/child chunk 分配 `CHUNK` ID；数据库以 `BIGINT` 保存；HTTP 仍以字符串展示这些数字，避免前端和外部调用方遇到 JavaScript number 精度问题。

## 范围

- 新增 `crag-id` Gradle library module。
- 在 `crag-id` 中实现：
  - `IdEntityType` 注册表，首批启用 `LEGACY_DOCUMENT` 与 `CHUNK`。
  - Snowflake bit layout 编解码、ID parser、实体类型校验。
  - 可测试的 clock 抽象、同毫秒 sequence 分配和 sequence 溢出等待。
  - Redis Worker lease、续约、丢租约停止发号和健康状态暴露。
  - Spring Boot 自动/显式配置，供 Application 组合根按需启用。
- 将 `crag-rag-service` 引入 Redis 连接配置和 ID readiness。
- 将 `crag-ingestion` / `crag-storage` / `crag-api` / `crag-query` 中现有 RAG ID 类型从 `String` 切到 `long` 或边界 decimal string。
- 将 `crag-rag-service/src/main/resources/schema.sql` 的 RAG 表字段从 UUID-style varchar 切到 `BIGINT`，parent sentinel 从空字符串切到 `0`。
- 更新 RAG 相关单元测试、组件测试、架构测试和 Docker HTTP 回归脚本。
- 更新 `docker-compose.yml`，新增 Redis 服务并让依赖发号的服务在 Redis 可用后启动或进入 readiness。
- 同步更新模块依赖白名单、Docker 结构约束、包结构约束、README 中与 Redis/ID 相关的当前事实。

## 非目标

- 不实现数据库号段取号器、DB → Redis → local buffer 的号段模式。
- 不实现在线热迁移、双写、历史 UUID 兼容、旧 ID 查询或数据迁移脚本。
- 不保证全平台裸 ID 全局唯一；跨服务契约必须带上所有者或实体语义。
- 不新增 Access、Tenant、Membership、KnowledgeBase、Document、API Key 等未来实体发号。
- 不把 `DOCUMENT` 分配给当前 RAG 旧入口；当前旧 AdminRag 文档 ID 使用 `LEGACY_DOCUMENT`，Plan 17 再由 Knowledge 使用 `DOCUMENT`。
- 不引入 Redis Streams、Outbox、Consumer Group、事件信封、消费幂等或补偿任务。
- 不改造 gRPC 业务契约，不新增领域 RPC。
- 不改造 LLM、Embedding、Sparse、Dense、RRF、Rerank 或 Query 语义。
- 不改变 `requestId`、`traceId` 等观测 ID 的 UUID/字符串语义。

## 前置依赖

- **执行前置 Plan**：`plan_14`
- `plan_14` 已完成五服务骨架、独立 Schema 和 Docker 平台拓扑。
- `docs/superpowers/specs/2026-06-24-distributed-snowflake-id-design.md` 已记录并覆盖 Plan 15 的 ID 设计决策。
- 进入实现前必须先提交本计划、计划索引和设计说明；未提交规划修订时不得开始 15.1。

## 文件边界

- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle/libs.versions.toml`
- `crag-id/**`
- `crag-rag-service/**`
- `crag-ingestion/**`
- `crag-storage/**`
- `crag-api/**`
- `crag-query/**`
- `crag-smoke/**`
- `docker-compose.yml`
- `constraints/package-structure.md`
- `constraints/docker-structure.md`
- `constraints/persistence-style.md`（仅 RAG ID 类型和 reset 事实需要补充时）
- `constraints/test-workflow.md`（仅 Docker 回归入口变化需要补充时）
- `scripts/validate_module_dependencies.py`
- `scripts/tests/test_validate_module_dependencies.py`
- `scripts/validate_constraints.py`
- `scripts/tests/test_validate_constraints.py`
- `scripts/tests/http/**`
- `README.md`
- `docs/superpowers/specs/2026-06-24-distributed-snowflake-id-design.md`
- `plan/plan_15/plan_15.md`
- `plan/index/README.md`

## 实现文件地图

### `crag-id` 模块

- `crag-id/build.gradle.kts`：声明 Java library、Spring Boot 配置绑定、Redis 客户端和测试依赖；不生成 Boot Jar。
- `crag-id/src/main/java/ai/cerbur/crag/id/api/CragIdGenerator.java`：公开发号入口，形如 `long nextId(IdEntityType entityType)`。
- `crag-id/src/main/java/ai/cerbur/crag/id/api/CragIdParser.java`：公开解析入口，返回 ID 中的 entity type、timestamp、worker、sequence。
- `crag-id/src/main/java/ai/cerbur/crag/id/api/IdEntityType.java`：集中注册实体类型编码，首批 `LEGACY_DOCUMENT`、`CHUNK`。
- `crag-id/src/main/java/ai/cerbur/crag/id/api/InvalidCragIdException.java`：请求解析和实体校验失败的业务异常。
- `crag-id/src/main/java/ai/cerbur/crag/id/internal/SnowflakeLayout.java`：bit shift、mask、epoch 和编解码。
- `crag-id/src/main/java/ai/cerbur/crag/id/internal/SnowflakeSequence.java`：同 worker 下 timestamp/sequence 状态机。
- `crag-id/src/main/java/ai/cerbur/crag/id/internal/MonotonicClock.java`：可测试时钟适配，不直接把 `System.currentTimeMillis()` 写死在核心算法。
- `crag-id/src/main/java/ai/cerbur/crag/id/redis/RedisWorkerLease.java`：Redis Worker lease 获取、续约、释放与丢租约判断。
- `crag-id/src/main/java/ai/cerbur/crag/id/redis/RedisWorkerLeaseRepository.java`：封装 Redis key/value 操作和 compare-and-release 语义。
- `crag-id/src/main/java/ai/cerbur/crag/id/spring/CragIdProperties.java`：绑定 service domain、required entities、lease TTL、renew interval 和 rollback threshold。
- `crag-id/src/main/java/ai/cerbur/crag/id/spring/CragIdConfiguration.java`：显式创建发号器、lease scheduler 和 health contributor。
- `crag-id/src/main/java/ai/cerbur/crag/id/spring/CragIdHealthIndicator.java`：把 lease/clock 状态映射到 Actuator readiness。

### RAG 写入、持久化和 HTTP 边界

- `crag-ingestion/src/main/java/ai/cerbur/crag/ingestion/api/AdminRagService.java`：注入 `CragIdGenerator`，生成 `LEGACY_DOCUMENT` 与 `CHUNK`。
- `crag-ingestion/src/main/java/ai/cerbur/crag/ingestion/api/AdminRagResult.java`：内部结果改为 `long docId`、`List<Long> parentChunkIds`。
- `crag-storage/src/main/java/ai/cerbur/crag/storage/entity/Chunk.java`：`chunkId`、`docId`、`parentChunkId` 改为 `long`；`NO_PARENT = 0L`。
- `crag-storage/src/main/java/ai/cerbur/crag/storage/ChunkDao.java`、`ChunkEmbeddingDao.java`、`ChunkFtsDao.java`：DAO 方法参数和投影类型同步切到 `long`。
- `crag-storage/src/main/java/ai/cerbur/crag/storage/repository/**`：Repository 查询参数、native SQL、JPQL 与投影同步切到 `BIGINT`。
- `crag-api/src/main/java/ai/cerbur/crag/api/dto/rag/AdminRagResponse.java`：HTTP 响应保持 `String docId`、`List<String> parentChunkIds`，由 decimal long 转换。
- `crag-api/src/main/java/ai/cerbur/crag/api/controller/AdminRagController.java`：映射内部 long 到 decimal string。
- `crag-api/src/main/java/ai/cerbur/crag/api/dto/query/QuerySourceResponse.java`、`UserQueryResponse.java`：source/chunk ID 边界按 decimal string 输出。
- `crag-query/src/main/java/ai/cerbur/crag/query/api/QuerySource.java`：内部 source ID 类型同步改为 long 或在边界集中转换。
- `crag-rag-service/src/main/resources/schema.sql`：RAG 三张表 ID 列切到 `BIGINT` 并更新索引、外键或 join 条件。
- `crag-rag-service/src/main/resources/data.sql`：如存在依赖旧 UUID 的种子数据，改为可重建 numeric ID 或删除。

### Redis、Docker、约束与回归

- `docker-compose.yml`：新增 `redis` 服务；`rag-service` 与 `rag-service-smoke` 注入 Redis 连接与 required ID entities。
- `constraints/docker-structure.md`：当前服务索引新增 Redis，并更新启动依赖和持久化说明。
- `constraints/package-structure.md`：新增 `crag-id` 模块职责和依赖白名单。
- `scripts/validate_module_dependencies.py`：允许 `crag-ingestion` 或 RAG 组合根依赖 `crag-id`，禁止不需要发号的 Console/Open 引入发号配置。
- `scripts/tests/http/admin_rag_contract_test.sh`：断言返回 ID 为 decimal string 且不再是 UUID。
- `scripts/tests/http/query_stub_success_test.sh`、`retrieval_evidence_test.sh`：同步断言 source ID 字段的 numeric string 形态。
- `scripts/tests/http/platform_topology_test.sh`、`docker_readiness_test.sh`：覆盖 Redis 存在、Redis 不可用时 RAG readiness 失败和恢复。

## 关键决策

### ID 语义

- ID 的业务唯一性边界是 `service domain + entity type`，不是全平台裸 ID。
- 服务域之间重复 numeric ID 可接受；跨服务调用和数据模型不得用裸 ID 推断资源所有者。
- ID 高位包含实体类型，因此 parser 可以在接口边界拒绝错误实体类型，例如 Document 查询拒绝 `CHUNK` ID。
- DB 仍只存 `BIGINT`，不为了类型校验额外增加 entity type 列。
- `LEGACY_DOCUMENT` 表示当前旧 AdminRag 兼容入口仍由 RAG Worker 负责；Plan 17 引入 Knowledge Document 后使用独立 `DOCUMENT` 实体。

### Redis Worker lease

- Redis 只用于 worker lease，不做号段 buffer。
- 发号器按需懒加载：第一次需要某个 `service domain + entity type` 时领取 worker。
- RAG 服务声明 required entities 为 `LEGACY_DOCUMENT` 与 `CHUNK`；Access、Knowledge、Console、Open 在本计划不启用 required issuer。
- 丢 lease、Redis 不可用、大时钟回拨都会停止发号并让 readiness `DOWN`。
- 恢复路径是重新确认时钟状态并重新领取 lease，不复用已经失效的 worker。

### RAG 切换

- 本计划是冷切换，不做热切换。
- 开发/demo 旧 RAG 数据允许被删除或重建；执行计划必须提供明确的 RAG schema reset 路径，但不得清空 Access/Knowledge/Console/Open 的业务数据。
- Parent chunk 的无父节点 sentinel 改为 `0L`，避免 `BIGINT` 列出现空字符串等不一致表示。
- HTTP 响应改为 numeric decimal string 后，字段名保持兼容，除值形态外不扩张 API。

## 未决问题

无。

## 风险与回滚

- **风险：Redis lease 实现不当导致同一 namespace worker 重复。** 预防：lease 获取必须使用 Redis 原子语义，释放必须比较 owner token，测试覆盖并发抢占、续约失败和过期重领。回滚：撤销 `crag-id` 与 Docker Redis 改动，恢复旧 UUID 发号实现和旧 RAG schema。
- **风险：时钟回拨处理造成请求阻塞或服务不可用。** 预防：小回拨等待阈值固定 5ms，大回拨立即 fail fast 并映射 readiness。回滚：关闭 RAG 发号器或回退到旧 UUID 提交。
- **风险：RAG schema cold reset 删除开发数据。** 处置：本计划明确接受旧 RAG 数据丢弃；执行时只允许作用于 RAG schema/table，不得删除 PostgreSQL volume 或其他服务 schema。若需要保留数据，必须中止并创建迁移 Plan。
- **风险：HTTP 边界 long 精度被前端误当 number。** 预防：DTO 使用 `String`，测试断言 JSON 类型为 string。回滚：恢复旧 DTO 映射或在调用方同步调整。
- **风险：跨模块 ID 类型改动遗漏导致编译或查询失败。** 预防：按任务先单元再组件再 Docker 回归，Repository/native SQL 投影必须有测试覆盖。回滚：使用任务级提交逐个 revert。

## 测试与验证计划

- 纯单元测试（`./gradlew test`，不依赖 Spring/Docker）：
  - `crag-id/src/test/java/ai/cerbur/crag/id/internal/SnowflakeLayoutTest.java`：覆盖 bit layout、entity type、timestamp、worker、sequence 编解码。
  - `crag-id/src/test/java/ai/cerbur/crag/id/internal/SnowflakeSequenceTest.java`：覆盖同毫秒递增、sequence 溢出等待、小回拨等待、大回拨失败。
  - `crag-id/src/test/java/ai/cerbur/crag/id/api/CragIdParserTest.java`：覆盖 decimal string 解析、非法 ID 和实体类型拒绝。
  - `crag-ingestion/src/test/java/ai/cerbur/crag/ingestion/service/AdminRagServiceTest.java`：覆盖 RAG 文档与 chunk ID 来自 generator。
- 轻量组件测试（`*ComponentTest`，`./gradlew test`）：
  - `crag-id/src/test/java/ai/cerbur/crag/id/spring/CragIdConfigurationComponentTest.java`：覆盖配置绑定、required entity 和 health 状态。
  - `crag-rag-service/src/test/java/ai/cerbur/crag/rag/app/RagIdReadinessComponentTest.java`：覆盖 RAG required issuer 未就绪时 readiness DOWN。
  - `crag-api/src/test/java/ai/cerbur/crag/api/controller/AdminRagControllerComponentTest.java`：覆盖响应 ID 是 JSON string。
- 架构测试（`*ArchitectureTest`，`./gradlew test`）：
  - 更新现有模块依赖校验，允许 `crag-id` 被需要发号的模块依赖，并禁止 `crag-id` 反向依赖业务模块。
- Docker HTTP 回归：
  - `docker compose up -d --build`
  - `scripts/tests/http/platform_topology_test.sh`
  - `scripts/tests/http/docker_readiness_test.sh`
  - `scripts/tests/http/admin_rag_contract_test.sh`
  - `scripts/tests/http/query_stub_success_test.sh`
  - `scripts/tests/http/retrieval_evidence_test.sh`
  - 预期：Redis 正常时 RAG readiness `UP`；AdminRag 返回 decimal string ID；Query/Retrieval source ID 仍可被消费；Redis 不可用场景能明确表现为 RAG readiness `DOWN`。
- 计划与约束校验：
  - `./gradlew check`
  - 预期：Plan 校验、约束校验、模块依赖校验、格式和全部非 Docker 测试通过。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 15.1 | `crag-id` 核心 Snowflake 编解码与实体注册 | ⏳ 待开始 | — | — |
| 15.2 | Redis Worker lease、发号器生命周期与 readiness | ⏳ 待开始 | — | — |
| 15.3 | RAG 持久化 ID 类型切换与 cold reset 路径 | ⏳ 待开始 | — | — |
| 15.4 | RAG HTTP/API 边界 decimal string 与实体类型校验 | ⏳ 待开始 | — | — |
| 15.5 | Docker Redis 拓扑、约束同步与端到端回归 | ⏳ 待开始 | — | — |

整体进度：0 / 5（0%）

## 15.1 `crag-id` 核心 Snowflake 编解码与实体注册

**目标**：新增 `crag-id` 模块，完成不依赖 Redis 的 Snowflake bit layout、entity registry、parser 和 sequence 状态机。  
**前置任务**：无  
**范围**：更新 Gradle settings、模块构建、模块依赖校验；实现 `IdEntityType`、`SnowflakeLayout`、`SnowflakeSequence`、`CragIdParser`、`InvalidCragIdException` 和核心单元测试。  
**非目标**：不接 Redis、不接 Spring readiness、不修改 RAG 业务代码。  
**验收标准**：`crag-id` 能独立生成可解析 ID；`LEGACY_DOCUMENT` 和 `CHUNK` 编码稳定；非法 decimal string、负数、未知 entity、entity mismatch 都有明确异常；sequence 溢出等待下一毫秒；大时钟回拨抛出可被上层识别的停止发号异常。  
**验证方式**：运行 `./gradlew :crag-id:test`、`python3 scripts/validate_module_dependencies.py` 与 `python3 scripts/tests/test_validate_module_dependencies.py`。  
**涉及文件**：`settings.gradle.kts`、`crag-id/**`、`scripts/validate_module_dependencies.py`、`scripts/tests/test_validate_module_dependencies.py`、`constraints/package-structure.md`

## 15.2 Redis Worker lease、发号器生命周期与 readiness

**目标**：在 `crag-id` 中加入 Redis worker lease、续约、丢租约停止发号和 Actuator health 集成。  
**前置任务**：15.1  
**范围**：实现 Redis lease repository、lease owner token、worker slot 选择、续约 scheduler、release compare-and-delete、`CragIdProperties`、`CragIdConfiguration`、`CragIdHealthIndicator` 和相关测试。生产代码使用 Spring 管理的 scheduler/executor，不直接创建线程。  
**非目标**：不新增 Redis Streams、不做号段缓存、不接 RAG schema。  
**验收标准**：同一 `serviceDomain:entityType` 内最多 16 个 worker slot；slot 被占用时不会重复领取；续约失败后 issuer 停止发号；Redis 启动不可用时 required issuer readiness `DOWN`；Redis 恢复后可重新领取 lease。  
**验证方式**：运行 `./gradlew :crag-id:test`，重点覆盖 lease acquire、renew、lost、reacquire 和 health mapping；检查日志与异常不得输出 Redis token 或完整 lease owner secret。  
**涉及文件**：`crag-id/**`、`gradle/libs.versions.toml`、`build.gradle.kts`

## 15.3 RAG 持久化 ID 类型切换与 cold reset 路径

**目标**：把 RAG storage 与 ingestion 的内部 ID 类型切到 `long` / `BIGINT`，并提供只作用于 RAG 表的冷重建路径。  
**前置任务**：15.1、15.2  
**范围**：`Chunk` entity、DAO、Repository、native SQL、投影、`AdminRagService`、`AdminRagResult` 和 RAG schema 同步切换；parent sentinel 改为 `0L`；`schema.sql` 支持开发/demo cold reset 或明确的 RAG-only reset 脚本；单元测试覆盖 parent/child ID 关系。  
**非目标**：不迁移旧 UUID 数据，不删除 PostgreSQL volume，不清理 Access/Knowledge schema，不改检索算法。  
**验收标准**：新写入 chunk 的 `doc_id`、`chunk_id`、`parent_chunk_id` 均为 numeric ID；parent chunk `parent_chunk_id = 0`；child chunk 指向真实 parent chunk ID；Dense/Sparse/CAS 查询仍能按 `BIGINT` 工作；旧 RAG 数据处理路径清晰且限定在 RAG 表。  
**验证方式**：运行 `./gradlew :crag-storage:test :crag-ingestion:test :crag-rag-service:test`；必要时运行 `./gradlew test --tests '*Chunk*Test' --tests '*AdminRagServiceTest'` 缩小定位。  
**涉及文件**：`crag-storage/**`、`crag-ingestion/**`、`crag-rag-service/src/main/resources/schema.sql`、`crag-rag-service/src/main/resources/data.sql`

## 15.4 RAG HTTP/API 边界 decimal string 与实体类型校验

**目标**：保持 HTTP 边界字段名稳定，同时把业务 ID 值改为 decimal string，并在请求解析处加入实体类型校验。  
**前置任务**：15.3  
**范围**：更新 AdminRag、UserQuery、QuerySource、Retrieval evidence 相关 DTO/映射；新增或复用 ID parser，把内部 long 转为 string 输出，把请求 string 解析为 long 并校验实体类型；组件测试断言 JSON ID 类型为 string。  
**非目标**：不新增新 API 版本，不迁移 Console/Open 业务入口，不改变 Response 统一包裹结构。  
**验收标准**：AdminRag 响应中的 `docId` 与 `parentChunkIds` 是 decimal string 且不是 UUID；Query/Retrieval source ID 字段保持可读且为 string；传入错误实体类型 ID 的文档/Chunk 语义接口返回明确 4xx 错误；错误响应不泄漏内部 bit layout 细节。  
**验证方式**：运行 `./gradlew :crag-api:test :crag-query:test :crag-rag-service:test`；组件测试覆盖 DTO JSON 类型和错误映射。  
**涉及文件**：`crag-api/**`、`crag-query/**`、`crag-retrieval/**`、`crag-smoke/**`

## 15.5 Docker Redis 拓扑、约束同步与端到端回归

**目标**：把 Redis 纳入默认 Docker 拓扑，完成约束/README 同步，并通过完整 Docker HTTP 回归证明 RAG ID 切换可运行。  
**前置任务**：15.4  
**范围**：更新 Compose Redis 服务、RAG 环境变量、health/readiness、Docker 文档、包结构文档、README、HTTP 回归脚本和约束校验；新增 Redis 不可用/恢复相关回归或诊断步骤。  
**非目标**：不为 Redis 增加持久化业务数据，不引入 Redis 集群或密码生产配置，不修改 Sidecar 模型流程。  
**验收标准**：`docker compose up -d --build` 后 Redis、RAG、Console、Open readiness 符合预期；AdminRag 真实 HTTP 调用返回 decimal string ID；Query 仍可基于新 ID 链路完成；停止 Redis 或使 lease 失败时 RAG readiness 明确下降；恢复 Redis 后可重新发号。  
**验证方式**：运行 `./gradlew check`；运行 `docker compose up -d --build`；执行 `scripts/tests/http/platform_topology_test.sh`、`scripts/tests/http/docker_readiness_test.sh`、`scripts/tests/http/admin_rag_contract_test.sh`、`scripts/tests/http/query_stub_success_test.sh`、`scripts/tests/http/retrieval_evidence_test.sh`。  
**涉及文件**：`docker-compose.yml`、`constraints/docker-structure.md`、`constraints/package-structure.md`、`constraints/test-workflow.md`、`README.md`、`scripts/validate_constraints.py`、`scripts/tests/test_validate_constraints.py`、`scripts/tests/http/**`

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
| 2026-06-24 | 创建计划 | 固定 Plan 15 Snowflake ID、Redis Worker lease 与 RAG ID cold switch 范围 | 进入 ready，等待计划提交后执行 |
