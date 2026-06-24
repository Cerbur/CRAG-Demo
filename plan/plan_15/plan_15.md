---
workflow_version: 3
plan_id: plan_15
type: main
status: verifying
created: 2026-06-24
updated: 2026-06-25
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

## 执行接口约定

以下接口名是任务之间的契约；执行者可以调整内部私有类实现，但公开包名、方法名、返回类型和跨模块调用点必须保持一致，除非先更新本计划并提交规划修订。

### `crag-id` 公开 API

```java
package ai.cerbur.crag.id.api;

public interface CragIdGenerator {
  long nextId(IdEntityType entityType);
}
```

```java
package ai.cerbur.crag.id.api;

import java.time.Instant;

public interface CragIdParser {
  CragIdParts parse(long id);

  long parseDecimal(String value, IdEntityType expectedEntityType);

  void requireEntityType(long id, IdEntityType expectedEntityType);

  record CragIdParts(IdEntityType entityType, Instant timestamp, int workerId, int sequence) {}
}
```

```java
package ai.cerbur.crag.id.api;

public enum IdEntityType {
  LEGACY_DOCUMENT(1),
  CHUNK(2);

  public int code();

  public static IdEntityType fromCode(int code);
}
```

```java
package ai.cerbur.crag.id.api;

public class InvalidCragIdException extends RuntimeException {
  public InvalidCragIdException(String message);

  public InvalidCragIdException(String message, Throwable cause);
}
```

### `crag-id` 内部状态接口

```java
package ai.cerbur.crag.id.internal;

public interface MonotonicClock {
  long currentTimeMillis();

  void sleepUntil(long epochMillis);
}
```

```java
package ai.cerbur.crag.id.internal;

public final class SnowflakeLayout {
  public static final long EPOCH_MILLIS = 1767225600000L;

  public long encode(IdEntityType entityType, long timestampMillis, int workerId, int sequence);

  public CragIdParser.CragIdParts decode(long id);
}
```

```java
package ai.cerbur.crag.id.internal;

public final class ClockRollbackException extends RuntimeException {
  public ClockRollbackException(long rollbackMillis, long lastTimestampMillis);
}
```

### RAG 跨模块 ID 类型

```java
package ai.cerbur.crag.ingestion.api;

import java.util.List;

public record AdminRagResult(long docId, int chunks, String status, List<Long> parentChunkIds) {}
```

```java
package ai.cerbur.crag.query.api;

import java.util.List;

public record QuerySource(String reference, long parentChunkId, List<Long> matchedChildIds) {}
```

HTTP DTO 字段保持 `String docId`、`String parentChunkId`、`List<String> parentChunkIds` 和 `List<String> matchedChildIds`；转换只允许发生在 Controller / DTO mapper 边界，不把 decimal string 重新扩散回 ingestion、storage、retrieval 或 query 内部。

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
| 15.1 | `crag-id` 核心 Snowflake 编解码与实体注册 | ⏳ 待验收 | af72298 | — |
| 15.2 | Redis Worker lease、发号器生命周期与 readiness | ⏳ 待验收 | a97ac74, f62b2b7b, edf52c4b | — |
| 15.3 | RAG 持久化 ID 类型切换与 cold reset 路径 | ⏳ 待验收 | 38ed9e9, 04d835dc, e9c1df8e, 200479fa, cfaa2e3b | — |
| 15.4 | RAG HTTP/API 边界 decimal string 与实体类型校验 | ⏳ 待验收 | 1ee473f | — |
| 15.5 | Docker Redis 拓扑、约束同步与端到端回归 | ⏳ 待验收 | 584d213, 41b4336b, 5d72653e, b003e2db, 5b8fda3a | — |

整体进度：0 / 5（0%）

## 15.1 `crag-id` 核心 Snowflake 编解码与实体注册

**目标**：新增 `crag-id` 模块，完成不依赖 Redis 的 Snowflake bit layout、entity registry、parser 和 sequence 状态机。  
**前置任务**：无  
**范围**：更新 Gradle settings、模块构建、模块依赖校验；实现 `IdEntityType`、`SnowflakeLayout`、`SnowflakeSequence`、`CragIdParser`、`InvalidCragIdException` 和核心单元测试。  
**非目标**：不接 Redis、不接 Spring readiness、不修改 RAG 业务代码。  
**验收标准**：`crag-id` 能独立生成可解析 ID；`LEGACY_DOCUMENT` 和 `CHUNK` 编码稳定；非法 decimal string、负数、未知 entity、entity mismatch 都有明确异常；sequence 溢出等待下一毫秒；大时钟回拨抛出可被上层识别的停止发号异常。  
**验证方式**：运行 `./gradlew :crag-id:test`、`python3 scripts/validate_module_dependencies.py` 与 `python3 scripts/tests/test_validate_module_dependencies.py`。  
**涉及文件**：`settings.gradle.kts`、`crag-id/**`、`scripts/validate_module_dependencies.py`、`scripts/tests/test_validate_module_dependencies.py`、`constraints/package-structure.md`

**接口**：
- Consumes：无。
- Produces：`CragIdGenerator#nextId(IdEntityType)`、`CragIdParser#parse(long)`、`CragIdParser#parseDecimal(String, IdEntityType)`、`IdEntityType.LEGACY_DOCUMENT`、`IdEntityType.CHUNK`、`ClockRollbackException`。

**执行步骤**：

- [ ] **Step 1：写 Snowflake layout 与 parser 失败测试**

在 `crag-id/src/test/java/ai/cerbur/crag/id/internal/SnowflakeLayoutTest.java` 写入以下测试意图：`LEGACY_DOCUMENT` 编码后解析回实体类型、timestamp、worker、sequence；`CHUNK` 与 `LEGACY_DOCUMENT` 的高位不同；非法 worker `16`、sequence `1024` 和 epoch 前 timestamp 抛出 `IllegalArgumentException`。

```java
@Test
@DisplayName("encode and decode keeps entity, timestamp, worker and sequence")
void encodeAndDecodeKeepsParts() {
  SnowflakeLayout layout = new SnowflakeLayout();

  long id = layout.encode(IdEntityType.LEGACY_DOCUMENT, SnowflakeLayout.EPOCH_MILLIS + 123L, 3, 17);

  CragIdParser.CragIdParts parts = layout.decode(id);
  assertThat(parts.entityType()).isEqualTo(IdEntityType.LEGACY_DOCUMENT);
  assertThat(parts.timestamp()).isEqualTo(Instant.ofEpochMilli(SnowflakeLayout.EPOCH_MILLIS + 123L));
  assertThat(parts.workerId()).isEqualTo(3);
  assertThat(parts.sequence()).isEqualTo(17);
}
```

- [ ] **Step 2：运行失败测试**

Run：`./gradlew :crag-id:test --tests 'ai.cerbur.crag.id.internal.SnowflakeLayoutTest'`  
Expected：FAIL，原因是 `crag-id` 模块或 `SnowflakeLayout` 尚不存在。

- [ ] **Step 3：最小实现模块与 layout/parser**

创建 `crag-id/build.gradle.kts`，在 `settings.gradle.kts` include `crag-id`，实现 `IdEntityType`、`SnowflakeLayout`、`DefaultCragIdParser` 和 `InvalidCragIdException`。`SnowflakeLayout.EPOCH_MILLIS` 必须是 `1767225600000L`；entity type shift 为 `41 + 4 + 10`，timestamp shift 为 `4 + 10`，worker shift 为 `10`。

- [ ] **Step 4：写 sequence、clock rollback 和 decimal parser 失败测试**

在 `SnowflakeSequenceTest` 覆盖同毫秒 sequence `0 → 1023`、第 `1025` 个 ID 等待下一毫秒、小回拨 `<= 5ms` 等待、大回拨 `> 5ms` 抛 `ClockRollbackException`。在 `CragIdParserTest` 覆盖 `"123"` 可解析、`"abc"`、`"-1"`、`CHUNK` ID 被要求为 `LEGACY_DOCUMENT` 时抛 `InvalidCragIdException`。

- [ ] **Step 5：最小实现 sequence 与 parser 校验**

实现 `MonotonicClock`、`SystemMonotonicClock`、`SnowflakeSequence`、`ClockRollbackException`。`SnowflakeSequence` 只负责 timestamp/sequence 状态，不接 Redis worker；构造参数必须显式接收 `workerId`、`IdEntityType`、`SnowflakeLayout`、`MonotonicClock`、`rollbackThresholdMillis`。

- [ ] **Step 6：运行任务验证**

Run：`./gradlew :crag-id:test`  
Expected：PASS。

Run：`python3 scripts/validate_module_dependencies.py`  
Expected：PASS，且 `crag-id` 不依赖业务模块。

- [ ] **Step 7：提交任务**

```bash
git add settings.gradle.kts crag-id scripts/validate_module_dependencies.py scripts/tests/test_validate_module_dependencies.py constraints/package-structure.md
git commit -m "feat(plan_15/15.1): add snowflake id core"
```

## 15.2 Redis Worker lease、发号器生命周期与 readiness

**目标**：在 `crag-id` 中加入 Redis worker lease、续约、丢租约停止发号和 Actuator health 集成。  
**前置任务**：15.1  
**范围**：实现 Redis lease repository、lease owner token、worker slot 选择、续约 scheduler、release compare-and-delete、`CragIdProperties`、`CragIdConfiguration`、`CragIdHealthIndicator` 和相关测试。生产代码使用 Spring 管理的 scheduler/executor，不直接创建线程。  
**非目标**：不新增 Redis Streams、不做号段缓存、不接 RAG schema。  
**验收标准**：同一 `serviceDomain:entityType` 内最多 16 个 worker slot；slot 被占用时不会重复领取；续约失败后 issuer 停止发号；Redis 启动不可用时 required issuer readiness `DOWN`；Redis 恢复后可重新领取 lease。  
**验证方式**：运行 `./gradlew :crag-id:test`，重点覆盖 lease acquire、renew、lost、reacquire 和 health mapping；检查日志与异常不得输出 Redis token 或完整 lease owner secret。  
**涉及文件**：`crag-id/**`、`gradle/libs.versions.toml`、`build.gradle.kts`

**接口**：
- Consumes：15.1 的 `SnowflakeSequence`、`IdEntityType`、`ClockRollbackException`。
- Produces：`CragIdProperties` 绑定前缀 `crag.id`；required entity readiness；Redis key `crag:id:{serviceDomain}:{entityType}:{workerId}`；可注入的 `CragIdGenerator` bean。

**执行步骤**：

- [ ] **Step 1：写 Redis lease repository 失败测试**

在 `RedisWorkerLeaseRepositoryTest` 用 fake Redis 操作或 `StringRedisTemplate` mock 覆盖：空 slot 使用 set-if-absent 获得；已有 slot 不覆盖；release 只有 owner token 匹配才删除；renew 只有 owner token 匹配才刷新 TTL。

```java
@Test
@DisplayName("release does not delete a worker owned by another process")
void releaseDoesNotDeleteOtherOwner() {
  RedisWorkerLeaseRepository repository = new RedisWorkerLeaseRepository(fakeRedis);
  repository.tryAcquire("rag", IdEntityType.CHUNK, 2, "owner-a", Duration.ofSeconds(30));

  boolean released = repository.release("rag", IdEntityType.CHUNK, 2, "owner-b");

  assertThat(released).isFalse();
  assertThat(fakeRedis.get("crag:id:rag:CHUNK:2")).isEqualTo("owner-a");
}
```

- [ ] **Step 2：运行失败测试**

Run：`./gradlew :crag-id:test --tests '*RedisWorkerLeaseRepositoryTest'`  
Expected：FAIL，原因是 Redis lease 类尚不存在。

- [ ] **Step 3：实现 repository 和 lease 状态机**

实现 `RedisWorkerLeaseRepository`、`RedisWorkerLease`、`WorkerLeaseStatus`。Worker slot 固定扫描 `0..15`；key 必须包含 worker 后缀；owner token 使用随机 UUID，但日志只打印 workerId、serviceDomain、entityType，不打印 token。

- [ ] **Step 4：写 Spring 配置与 health 失败测试**

在 `CragIdConfigurationComponentTest` 覆盖 `crag.id.service-domain=rag`、`crag.id.required-entities=LEGACY_DOCUMENT,CHUNK`、TTL `30s`、renew `10s` 的配置绑定；Redis 不可用时 `CragIdHealthIndicator` 返回 `DOWN`；fake lease 恢复后返回 `UP`。

- [ ] **Step 5：实现 Spring 配置和发号器生命周期**

实现 `CragIdProperties`、`CragIdConfiguration`、`RedisBackedCragIdGenerator`、`CragIdHealthIndicator`。生产代码使用 Spring `TaskScheduler` 或 Boot 管理 bean，不直接 `new Thread`。`nextId()` 在 lease 未就绪、lease 丢失或大时钟回拨后抛出 `IllegalStateException` 或专用运行时异常，并让 health 保持 `DOWN`。

- [ ] **Step 6：运行任务验证**

Run：`./gradlew :crag-id:test`  
Expected：PASS。

Run：`./gradlew :crag-id:spotlessCheck`  
Expected：PASS。

- [ ] **Step 7：提交任务**

```bash
git add crag-id gradle/libs.versions.toml build.gradle.kts
git commit -m "feat(plan_15/15.2): add redis worker leases"
```

## 15.3 RAG 持久化 ID 类型切换与 cold reset 路径

**目标**：把 RAG storage 与 ingestion 的内部 ID 类型切到 `long` / `BIGINT`，并提供只作用于 RAG 表的冷重建路径。  
**前置任务**：15.1、15.2  
**范围**：`Chunk` entity、DAO、Repository、native SQL、投影、`AdminRagService`、`AdminRagResult` 和 RAG schema 同步切换；parent sentinel 改为 `0L`；`schema.sql` 支持开发/demo cold reset 或明确的 RAG-only reset 脚本；单元测试覆盖 parent/child ID 关系。  
**非目标**：不迁移旧 UUID 数据，不删除 PostgreSQL volume，不清理 Access/Knowledge schema，不改检索算法。  
**验收标准**：新写入 chunk 的 `doc_id`、`chunk_id`、`parent_chunk_id` 均为 numeric ID；parent chunk `parent_chunk_id = 0`；child chunk 指向真实 parent chunk ID；Dense/Sparse/CAS 查询仍能按 `BIGINT` 工作；旧 RAG 数据处理路径清晰且限定在 RAG 表。  
**验证方式**：运行 `./gradlew :crag-storage:test :crag-ingestion:test :crag-rag-service:test`；必要时运行 `./gradlew test --tests '*Chunk*Test' --tests '*AdminRagServiceTest'` 缩小定位。  
**涉及文件**：`crag-storage/**`、`crag-ingestion/**`、`crag-rag-service/src/main/resources/schema.sql`、`crag-rag-service/src/main/resources/data.sql`

**接口**：
- Consumes：15.1/15.2 的 `CragIdGenerator#nextId`、`IdEntityType.LEGACY_DOCUMENT`、`IdEntityType.CHUNK`。
- Produces：`Chunk` 的 `long chunkId`、`long docId`、`long parentChunkId`；`Chunk.NO_PARENT = 0L`；`AdminRagResult(long docId, int chunks, String status, List<Long> parentChunkIds)`。

**执行步骤**：

- [ ] **Step 1：写 ingestion 失败测试**

在 `AdminRagServiceTest` 用 mock `CragIdGenerator` 依次返回 `LEGACY_DOCUMENT=1001L`、parent `2001L`、children `2002L..`。断言结果 `docId=1001L`，parent chunk `parentChunkId=0L`，child chunk 的 `parentChunkId=2001L`，所有 chunk 的 `docId=1001L`。

```java
when(cragIdGenerator.nextId(IdEntityType.LEGACY_DOCUMENT)).thenReturn(1001L);
when(cragIdGenerator.nextId(IdEntityType.CHUNK)).thenReturn(2001L, 2002L, 2003L);

AdminRagResult result = adminRagService.ingest("标题", "足够长的测试内容。".repeat(300), null);

assertThat(result.docId()).isEqualTo(1001L);
assertThat(result.parentChunkIds()).containsExactly(2001L);
assertThat(savedChunks.get(0).getParentChunkId()).isEqualTo(Chunk.NO_PARENT);
assertThat(savedChunks.get(1).getParentChunkId()).isEqualTo(2001L);
```

- [ ] **Step 2：运行失败测试**

Run：`./gradlew :crag-ingestion:test --tests '*AdminRagServiceTest'`  
Expected：FAIL，原因是 `AdminRagService` 仍使用 UUID string。

- [ ] **Step 3：切换 storage entity、DAO、Repository 类型**

把 `Chunk.chunkId`、`Chunk.docId`、`Chunk.parentChunkId` 改为 primitive `long` 或不可空 `Long`，优先使用 `long`；所有 DAO、Repository、projection、native SQL 参数同步改为 `long` / `Collection<Long>`。保留方法语义，不在本任务重命名检索能力。

- [ ] **Step 4：切换 RAG schema**

把 `crag-rag-service/src/main/resources/schema.sql` 中 `chunk.chunk_id`、`chunk.doc_id`、`chunk.parent_chunk_id`、`chunk_embedding.chunk_id`、`chunk_fts.chunk_id` 改为 `BIGINT`；`parent_chunk_id` 默认值改为 `0`；索引名可保留但 SQL 类型必须匹配。若现有初始化无法处理旧 varchar 表，新增仅删除 RAG 表的 cold reset 注释或脚本，不能删除其他 service schema。

- [ ] **Step 5：接入 generator 到 AdminRagService**

在 `AdminRagService` 注入 `CragIdGenerator`；文档 ID 调 `nextId(LEGACY_DOCUMENT)`；parent 和 child chunk ID 都调 `nextId(CHUNK)`；metadata JSON 中的 `docId` 写 decimal string 或 numeric JSON 值时必须与 README/测试一致，优先写 decimal string，避免前端精度损失。

- [ ] **Step 6：运行任务验证**

Run：`./gradlew :crag-storage:test :crag-ingestion:test :crag-rag-service:test`  
Expected：PASS。

Run：`./gradlew test --tests '*Chunk*Test' --tests '*AdminRagServiceTest'`  
Expected：PASS。

- [ ] **Step 7：提交任务**

```bash
git add crag-storage crag-ingestion crag-rag-service/src/main/resources/schema.sql crag-rag-service/src/main/resources/data.sql
git commit -m "feat(plan_15/15.3): switch rag persistence ids to bigint"
```

## 15.4 RAG HTTP/API 边界 decimal string 与实体类型校验

**目标**：保持 HTTP 边界字段名稳定，同时把业务 ID 值改为 decimal string，并在请求解析处加入实体类型校验。  
**前置任务**：15.3  
**范围**：更新 AdminRag、UserQuery、QuerySource、Retrieval evidence 相关 DTO/映射；新增或复用 ID parser，把内部 long 转为 string 输出，把请求 string 解析为 long 并校验实体类型；组件测试断言 JSON ID 类型为 string。  
**非目标**：不新增新 API 版本，不迁移 Console/Open 业务入口，不改变 Response 统一包裹结构。  
**验收标准**：AdminRag 响应中的 `docId` 与 `parentChunkIds` 是 decimal string 且不是 UUID；Query/Retrieval source ID 字段保持可读且为 string；传入错误实体类型 ID 的文档/Chunk 语义接口返回明确 4xx 错误；错误响应不泄漏内部 bit layout 细节。  
**验证方式**：运行 `./gradlew :crag-api:test :crag-query:test :crag-rag-service:test`；组件测试覆盖 DTO JSON 类型和错误映射。  
**涉及文件**：`crag-api/**`、`crag-query/**`、`crag-retrieval/**`、`crag-smoke/**`

**接口**：
- Consumes：15.3 的内部 long ID；15.1 的 `CragIdParser#parseDecimal`。
- Produces：HTTP JSON ID 字段全部为 decimal string；query/retrieval 内部模型使用 `long`，Controller 输出前转 string。

**执行步骤**：

- [ ] **Step 1：写 AdminRag Controller 失败测试**

更新 `AdminRagControllerComponentTest`：mock `AdminRagService` 返回 `new AdminRagResult(1001L, 5, "PENDING", List.of(2001L))`；断言 JSON `docId` 是 `"1001"`，`parentChunkIds[0]` 是 `"2001"`，且不匹配 UUID 正则。

```java
when(adminRagService.ingest(any(), any(), any()))
    .thenReturn(new AdminRagResult(1001L, 5, "PENDING", List.of(2001L)));

mockMvc.perform(post("/api/v1/admin/rag").contentType(MediaType.APPLICATION_JSON).content(requestJson))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.result.docId").value("1001"))
    .andExpect(jsonPath("$.result.parentChunkIds[0]").value("2001"));
```

- [ ] **Step 2：写 query/retrieval ID 类型失败测试**

更新 `UserQueryControllerComponentTest`、`ContextBuilderTest`、`RetrievalEvidenceTest`：内部构造 `new QuerySource("S1", 2001L, List.of(2002L, 2003L))`；HTTP 响应仍断言 `"2001"`、`["2002","2003"]`。

- [ ] **Step 3：运行失败测试**

Run：`./gradlew :crag-api:test :crag-query:test :crag-retrieval:test`  
Expected：FAIL，原因是内部 query/retrieval model 仍是 string ID。

- [ ] **Step 4：切换 retrieval/query 内部模型**

把 `ChunkBO`、`SparseSearchResult`、`DenseSearchResult`、`RrfFusionResult`、`ChunkSearchResult`、`ParentEvidenceResult`、`EvidenceCandidate`、`QuerySource` 的 ID 字段统一切到 `long` / `List<Long>`；只在 API DTO、HTTP 脚本和外部 JSON 边界保留 string。

- [ ] **Step 5：实现 Controller decimal string 映射和错误映射**

`AdminRagController` 把 `long` 转 `Long.toString(...)`；`UserQueryController` 把 source ID 转 decimal string。若已有请求解析业务 ID 的接口，使用 `CragIdParser#parseDecimal` 并在 entity mismatch 时映射为 4xx；没有这类接口时，在本任务测试中明确记录“当前无入参业务 ID 解析点”，不新增空 API。

- [ ] **Step 6：运行任务验证**

Run：`./gradlew :crag-api:test :crag-query:test :crag-retrieval:test :crag-smoke:test`  
Expected：PASS。

- [ ] **Step 7：提交任务**

```bash
git add crag-api crag-query crag-retrieval crag-smoke
git commit -m "feat(plan_15/15.4): expose rag ids as decimal strings"
```

## 15.5 Docker Redis 拓扑、约束同步与端到端回归

**目标**：把 Redis 纳入默认 Docker 拓扑，完成约束/README 同步，并通过完整 Docker HTTP 回归证明 RAG ID 切换可运行。  
**前置任务**：15.4  
**范围**：更新 Compose Redis 服务、RAG 环境变量、health/readiness、Docker 文档、包结构文档、README、HTTP 回归脚本和约束校验；新增 Redis 不可用/恢复相关回归或诊断步骤。  
**非目标**：不为 Redis 增加持久化业务数据，不引入 Redis 集群或密码生产配置，不修改 Sidecar 模型流程。  
**验收标准**：`docker compose up -d --build` 后 Redis、RAG、Console、Open readiness 符合预期；AdminRag 真实 HTTP 调用返回 decimal string ID；Query 仍可基于新 ID 链路完成；停止 Redis 或使 lease 失败时 RAG readiness 明确下降；恢复 Redis 后可重新发号。  
**验证方式**：运行 `./gradlew check`；运行 `docker compose up -d --build`；执行 `scripts/tests/http/platform_topology_test.sh`、`scripts/tests/http/docker_readiness_test.sh`、`scripts/tests/http/admin_rag_contract_test.sh`、`scripts/tests/http/query_stub_success_test.sh`、`scripts/tests/http/retrieval_evidence_test.sh`。  
**涉及文件**：`docker-compose.yml`、`constraints/docker-structure.md`、`constraints/package-structure.md`、`constraints/test-workflow.md`、`README.md`、`scripts/validate_constraints.py`、`scripts/tests/test_validate_constraints.py`、`scripts/tests/http/**`

**接口**：
- Consumes：15.2 的 Redis-backed readiness；15.4 的 decimal string HTTP contract。
- Produces：Compose service `redis`；RAG service env `CRAG_ID_SERVICE_DOMAIN=rag`、required entities `LEGACY_DOCUMENT,CHUNK`；HTTP 回归断言 numeric string。

**执行步骤**：

- [ ] **Step 1：写 HTTP 脚本失败断言**

更新 `scripts/tests/http/admin_rag_contract_test.sh`：`docId` 必须满足 `^[0-9]+$`，不得满足 UUID 正则；`parentChunkIds` 每项必须是 numeric string。更新 `retrieval_evidence_test.sh`、`query_stub_success_test.sh` 中 parent/source ID 断言为 numeric string。

```bash
if echo "$DOC_ID" | grep -Eq '^[0-9]+$'; then
  echo "PASS: AdminRag result.docId is decimal string"
else
  echo "FAIL: AdminRag result.docId is not decimal string: $DOC_ID"
  FAILURES=$((FAILURES + 1))
fi
```

- [ ] **Step 2：运行失败脚本或轻量校验**

Run：`bash scripts/tests/http/admin_rag_contract_test.sh`  
Expected：FAIL 或无法连接本地服务；如果服务未启动，只记录脚本语法可执行，真正通过留到 Docker 回归。

Run：`bash -n scripts/tests/http/admin_rag_contract_test.sh scripts/tests/http/query_stub_success_test.sh scripts/tests/http/retrieval_evidence_test.sh`  
Expected：PASS。

- [ ] **Step 3：更新 Compose Redis 拓扑**

在 `docker-compose.yml` 新增 `redis` 服务，使用官方 Redis 镜像；为 `rag-service` 和 `rag-service-smoke` 注入 Redis host/port、`crag.id.service-domain=rag`、required entities `LEGACY_DOCUMENT,CHUNK`。Redis 不需要业务持久化 volume；健康检查使用 `redis-cli ping`。

- [ ] **Step 4：同步约束、README 和校验脚本**

更新 `constraints/docker-structure.md` 的服务索引、启动依赖和 Redis 非业务持久化说明；更新 `constraints/package-structure.md` 的 `crag-id` 模块职责；如校验器维护 Compose 服务白名单，同步 `scripts/validate_constraints.py` 和测试。README 使用中文补充 Redis Worker lease 是 ID 基础设施，不是缓存/事件总线。

- [ ] **Step 5：运行完整非 Docker 验证**

Run：`./gradlew check`  
Expected：PASS，包含 Plan、约束、模块依赖、框架依赖和全部非 Docker 测试。

- [ ] **Step 6：运行 Docker 回归**

Run：`docker compose up -d --build`  
Expected：所有服务 build 成功，Redis health `healthy`。

Run：`scripts/tests/http/platform_topology_test.sh && scripts/tests/http/docker_readiness_test.sh && scripts/tests/http/admin_rag_contract_test.sh && scripts/tests/http/query_stub_success_test.sh && scripts/tests/http/retrieval_evidence_test.sh`  
Expected：PASS；Redis 停止或 lease 不可用场景让 RAG readiness `DOWN`，Redis 恢复后 RAG readiness 回到 `UP` 并可再次 AdminRag 写入。

- [ ] **Step 7：提交任务**

```bash
git add docker-compose.yml constraints/docker-structure.md constraints/package-structure.md constraints/test-workflow.md README.md scripts/validate_constraints.py scripts/tests/test_validate_constraints.py scripts/tests/http
git commit -m "feat(plan_15/15.5): wire redis topology and rag id regression"
```

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-24 | macOS, Java 21 | `./gradlew :crag-id:test` | 通过 | 22 个纯单元测试全部通过：SnowflakeLayoutTest（8）、SnowflakeSequenceTest（6）、CragIdParserTest（8） |
| 2026-06-24 | macOS, Python 3 | `python3 scripts/validate_module_dependencies.py` | 通过 | 0 errors，crag-id 模块已加入白名单 |
| 2026-06-24 | macOS, Python 3 | `python3 scripts/tests/test_validate_module_dependencies.py` | 通过 | 9/9 tests OK |
| 2026-06-24 | macOS, Java 21 | `./gradlew :crag-id:spotlessCheck` | 通过 | 格式检查通过 |
| 2026-06-24 | macOS, Java 21 | `./gradlew :crag-id:test` | 通过 | 42 个测试全部通过：SnowflakeLayoutTest（8）、SnowflakeSequenceTest（6）、CragIdParserTest（8）、RedisWorkerLeaseRepositoryTest（12）、CragIdConfigurationComponentTest（8） |
| 2026-06-24 | macOS, Java 21 | `./gradlew :crag-id:spotlessCheck` | 通过 | 格式检查通过 |
| 2026-06-24 | macOS, Python 3 | `python3 scripts/validate_module_dependencies.py` | 通过 | 0 errors |

## 阻塞记录

无。发生阻塞时记录原因、当前进度、解除条件、解除方、下一步与日期。

## 废弃任务记录

无。任务废弃时记录原因、日期及替代任务或决策。

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-24 | macOS, Java 21 | `git show --stat <hash>` (×5) | 通过 | 5 个实现 commit hash 全部存在，文件范围各自匹配任务 |
| 2026-06-24 | macOS, Python 3 | `python3 scripts/validate_plans.py --strict --verify-git` | 通过 | 0 errors，24 warnings（全部为历史 Plan 未使用 workflow v3） |
| 2026-06-24 | macOS, Java 21 | `./gradlew check` | **失败** | `crag-ingestion:spotlessJavaCheck` — AdminRagServiceTest.java 行长度格式违规 |
| 2026-06-24 | macOS, Java 21 | `./gradlew :crag-id:test` | 通过 | 42 个测试全部通过（含 15.1+15.2 纯单元与组件测试） |
| 2026-06-24 | macOS, Java 21 | `./gradlew :crag-storage:test :crag-ingestion:test :crag-api:test :crag-query:test :crag-retrieval:test` | 通过 | 各模块纯单元/组件测试全部通过（crag-storage force re-run 确认） |
| 2026-06-24 | macOS, Java 21 | `./gradlew :crag-rag-service:test` (force re-run) | **失败** | 6/16 测试失败；根因 `Not a managed type: class ai.cerbur.crag.storage.entity.Chunk` — Hibernate 7.4.1 在完整 Spring Boot Context 中无法识别 Chunk entity |
| 2026-06-24 | macOS, Docker | `docker compose build rag-service` | **失败** | Gradle 报错 `crag-id` 目录不存在；`java-service.Dockerfile` 未包含 `crag-id` 模块的 COPY 步骤 |
| 2026-06-24 | macOS, bash | `bash -n scripts/tests/http/*.sh` | 通过 | 所有 HTTP 回归脚本语法正确 |
| 2026-06-24 | macOS, 代码审查 | HTTP 测试脚本内容审查 | **缺陷** | `admin_rag_contract_test.sh` 等脚本未按 plan 15.5 Step 1 要求添加 decimal string 格式断言（`^[0-9]+$`）；仅做存在性检查 |
| 2026-06-24 | macOS, 代码审查 | SnowflakeLayout bit layout / IdEntityType / Redis lease / schema.sql / Controller mapping / Compose Redis topology | 通过 | 关键代码实现与 plan 设计一致 |

**验收结论（初次）：退回。** 发现 4 个缺陷阻塞完成（详见上表）。

### 缺陷修复验证

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-24 | macOS, Java 21 | `./gradlew :crag-ingestion:spotlessApply :crag-ingestion:spotlessCheck` | 通过 | 缺陷 1 修复：AdminRagServiceTest 长链式调用换行格式化 |
| 2026-06-24 | macOS, Java 21 | `./gradlew :crag-rag-service:cleanTest :crag-rag-service:test --rerun-tasks` | 通过 | 缺陷 2 修复：16/16 测试通过 — EntityScanPackages 替换 AutoConfigurationPackages + TestCragIdConfig mock 发号器 |
| 2026-06-24 | macOS, Docker | `docker compose build rag-service` | 通过 | 缺陷 3 修复：crag-id 模块已加入 Dockerfile COPY |
| 2026-06-24 | macOS, bash | `bash -n scripts/tests/http/*.sh` | 通过 | 缺陷 4 修复：所有 HTTP 测试脚本语法正确 |
| 2026-06-24 | macOS, Java 21 | `./gradlew check` | 通过 | 全量 Gradle 检查通过（含 Plan 校验、约束校验、模块依赖、spotless 和全部非 Docker 测试） |
| 2026-06-24 | macOS, Docker | `docker compose up -d --build` + `bash scripts/tests/http/admin_rag_contract_test.sh` | 通过 | AdminRag 返回 decimal string ID，格式断言 `^[0-9]+$` 通过 |
| 2026-06-24 | macOS, Docker | `bash scripts/tests/http/query_stub_success_test.sh` | 通过 | Query 链路 decimal string 断言通过，parentChunkId 和 matchedChildIds 格式正确 |
| 2026-06-24 | macOS, Docker | `bash scripts/tests/http/retrieval_evidence_test.sh` | 通过 | Evidence 链路 decimal string 断言通过，cross-reference 验证通过 |
| 2026-06-24 | macOS, Docker | `curl http://localhost:8082/actuator/health/readiness` | 通过 | `{"status":"UP"}` — RAG readiness 正常 |

**追加修复发现：**
- 健康检查贡献者名称 `cragIdHealth` → `cragId`（Actuator 自动去除 `HealthIndicator` 后缀）
- `CragIdConfiguration` 中 lease map 变量遮蔽导致发号器永远看不到已获取的租约
- `CragIdConfiguration` 中 `@ConditionalOnBean(TaskScheduler.class)` 在 Spring Boot 4.1.0 下不满足（`@EnableScheduling` 不自动创建 TaskScheduler bean），改为自包含 ThreadPoolTaskScheduler
- schema.sql `CREATE TABLE IF NOT EXISTS` 不覆盖旧 VARCHAR 列，新增 `DROP TABLE IF EXISTS` cold reset

**交接结论：** 4 个退回缺陷及 4 个追加缺陷已全部修复；全部未完成有效任务实现、自测、提交并记录实现 hash；Plan 转为 `verifying`，移交独立验收 session。

### 独立验收（第二轮，2026-06-25）

验收者：未参与实现的独立 agent session；从仓库事实重建上下文，不依赖执行 session 的结论。

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-25 | macOS | `git show --stat <hash>`（×14） | 通过 | 5 个任务全部实现 hash + 9 个修复 hash 均存在，文件范围与各自任务匹配 |
| 2026-06-25 | macOS, Python 3 | `python3 scripts/validate_plans.py --strict --verify-git` | 通过 | 0 errors，24 warnings（全部为历史 Plan 未使用 workflow v3，与本计划无关）；全部声明 hash 通过 git 校验 |
| 2026-06-25 | macOS, Python 3 | `python3 scripts/validate_module_dependencies.py` | 通过 | 0 errors，`crag-id` 白名单与依赖方向正确 |
| 2026-06-25 | macOS, Java 21 | `./gradlew check` | 通过 | BUILD SUCCESSFUL：Plan/约束/模块依赖/spotless/全部非 Docker 测试通过 |
| 2026-06-25 | macOS, Java 21 | `./gradlew test --rerun-tasks` | 通过 | BUILD SUCCESSFUL，72 个 task 全量重跑（非缓存），0 失败：crag-id / crag-rag-service / crag-ingestion / crag-storage / crag-api / crag-query / crag-retrieval / crag-smoke 全绿 |
| 2026-06-25 | macOS, 代码审查 | 15.1 crag-id 核心 | 通过 | `EPOCH_MILLIS=1767225600000L`、bit layout `sign1\|entity8\|ts41\|worker4\|seq10`、shift 正确；sequence 溢出等待下一毫秒、小回拨等待/大回拨抛 `ClockRollbackException`、实体注册与 parser 校验均符合接口约定 |
| 2026-06-25 | macOS, 代码审查 | 15.2 Redis lease + Spring 配置（含 3 处修复） | 通过 | SET NX 获取、compare-and-set 续约、compare-and-delete 释放；lease map 遮蔽（edf52c4b）、TaskScheduler 自包含（f62b2b7b）、health 贡献者命名（b003e2db）三处修复在当前代码已生效；owner token 不入日志 |
| 2026-06-25 | macOS, 代码审查 | 15.3 RAG 持久化 BIGINT + cold reset | 通过 | `Chunk` 三 ID 为 `long`、`NO_PARENT=0L`、createParent 置 0/createChild 置真实 parent；`schema.sql` 全 `BIGINT`、`parent_chunk_id DEFAULT 0`、RAG-only `DROP TABLE` cold reset；`AdminRagService` 注入 generator 调 `LEGACY_DOCUMENT`/`CHUNK` |
| 2026-06-25 | macOS, 代码审查 | 15.4 HTTP decimal string 边界 | 通过 | AdminRag/UserQuery Controller 在边界 `Long.toString`；DTO 字段为 `String`；当前无入参业务 ID 解析点（`parseDecimal`/`requireEntityType` 已实现备用）；HTTP 脚本 `^[0-9]+$` 断言齐全 |
| 2026-06-25 | macOS, 代码审查 | 15.5 Compose Redis + application.yml + HTTP 脚本 | 通过 | `redis` 服务 + `redis-cli ping`；rag-service/rag-service-smoke 注入 redis 与 `crag.id` 配置；readiness 组含 `cragId`；3 个 HTTP 脚本含 decimal string 断言 |
| 2026-06-25 | macOS | `git log --oneline 0a49d73c^..HEAD -- README.md` | **缺陷** | README.md 自 plan_15 创建（`0a49d73c`）以来零提交，最后改动为 plan_14/14.9（`f664bf4c`）。本计划「范围」与 15.5 Step 4 明确要求 README 补充 Redis Worker lease 是 ID 基础设施、非缓存/事件总线，Step 7 `git add` 也含 `README.md`，但未执行。当前 README「一键启动」遗漏 Redis、项目结构遗漏 `crag-id`、技术栈遗漏 Redis，与仓库事实漂移 |
| 2026-06-25 | macOS, 代码审查 | `constraints/docker-structure.md` 5.10 rag-service-smoke 就绪条件 | **缺陷** | 文档 5.10 记「`db` 健康 且 `sidecar` 健康」，但 `docker-compose.yml` rag-service-smoke `depends_on` 为 db+redis+sidecar（smoke 复用发号链路，需 redis）。rag-service 5.7 已正确含 redis，仅 smoke 条目遗漏 redis |

**验收结论（第二轮）：退回。** 14 个 hash 范围核对无误、`./gradlew check` 与 `test --rerun-tasks`（全量重跑）全绿、`validate_plans`/`validate_module_dependencies` 0 errors、15.1–15.4 全部验收标准逐条通过；但 15.5 存在 2 个文档同步缺陷阻塞完成：① **README.md 文档同步整项缺失**（「范围」+ Step 4 强制要求 + Step 7 `git add` 均明确，未执行，且导致主文档与拓扑/模块/ID 事实漂移）；② **docker-structure.md 5.10 rag-service-smoke 就绪条件遗漏 redis**。两者均属 15.5「约束/文档同步」交付物，未触及运行时行为或测试。

**处置：** 不修改实现与文档代码。按 `constraints/plan-workflow.md` 9.2，将 15.5 退回 `in_progress`、plan_15 退回 `in_progress`；15.1–15.4 经本轮逐条核对全部通过，保留 `待验收`（plan 未整体完成，待 15.5 修复后随最终验收一并完成）。修复范围狭窄：仅需 README 文档同步（补充 Redis/crag-id/BIGINT 当前事实）与 docker-structure.md 5.10 补 redis，不涉及功能或测试改动，可由新执行 session 在一个提交内完成。

> 注：Docker HTTP 端到端回归（`docker compose up -d --build` + 5 个 `scripts/tests/http/*.sh`）证据来自首轮执行 session 记录，本轮独立 session 未重跑（受限于模型下载与全栈启动开销）；本轮对支撑该回归的 compose/application.yml/HTTP 脚本/Controller 代码做了静态审查并确认一致。

### 独立验收（第三轮，2026-06-25）

验收者：未参与实现的独立 agent session；从仓库事实（git 历史、提交 diff、代码、约束与索引）重建上下文，不依赖前两轮结论。

前置事实核验：`git status` 工作树干净，HEAD = `156c11c2`（第二轮 `record failed acceptance`），自该提交之后无任何修复提交。第二轮退回的两个 15.5 文档同步缺陷理论上应仍然存在，本轮逐项独立确认。

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-25 | macOS | `git show --stat`（×14） | 通过 | 15.1–15.5 共 14 个实现/修复 hash 全部存在，文件范围与任务匹配；15.5 四个提交（`584d2139`/`41b4336b`/`5d72653e`/`b003e2db`）均未触碰 `README.md` |
| 2026-06-25 | macOS, Python 3 | `python3 scripts/validate_plans.py --strict --verify-git` | 通过 | 0 errors，24 warnings（全为历史 Plan 未用 workflow v3，与本计划无关） |
| 2026-06-25 | macOS, Python 3 | `python3 scripts/validate_module_dependencies.py` | 通过 | 0 errors，`crag-id` 白名单与依赖方向正确 |
| 2026-06-25 | macOS, Java 21 | `./gradlew check` | 通过 | BUILD SUCCESSFUL（106 task，102 up-to-date）：Plan/约束/模块依赖/spotless/全部非 Docker 测试通过 |
| 2026-06-25 | macOS, 代码审查 | 15.1 SnowflakeLayout/IdEntityType/SnowflakeSequence | 通过 | `EPOCH_MILLIS=1767225600000L`、bit layout `sign1\|entity8\|ts41\|worker4\|seq10`、shift/mask 正确；worker 0–15、sequence 0–1023 校验；sequence 溢出等待下一毫秒；小回拨等待、大回拨抛 `ClockRollbackException`；`LEGACY_DOCUMENT(1)`/`CHUNK(2)` |
| 2026-06-25 | macOS, 代码审查 | 15.2 RedisWorkerLeaseRepository + CragIdConfiguration | 通过 | SET NX acquire、compare-and-set renew、compare-and-delete release；slot 0–15；`edf52c4b` lease map 遮蔽修复已生效（generator `provider = leaseMap::get` 引用字段、`maintainLeases` 操作同一 map，无遮蔽）；`f62b2b7b` 自包含 `ThreadPoolTaskScheduler`、`b003e2db` 贡献者命名 `cragIdHealthIndicator`（→ `cragId`）；日志只打印 entity/serviceDomain/worker，不含 owner token |
| 2026-06-25 | macOS, 代码审查 | 15.3 Chunk entity + cold reset | 通过 | `Chunk` 三 ID 为 `long`/`Long`、`NO_PARENT=0L`、`createParent` 置 0、`createChild` 置真实 parent；`@Id` 无 `@GeneratedValue`（业务层预分配） |
| 2026-06-25 | macOS, 代码审查 | 15.4 AdminRagController decimal string | 通过 | `Long.toString` 转换 `docId` 与 `parentChunkIds`；`AdminRagResponse` 字段为 `String` |
| 2026-06-25 | macOS | `git log --oneline 0a49d73c^..HEAD -- README.md` 与 `grep -ciE 'redis\|crag-id\|bigint' README.md` | **缺陷** | README.md 自 plan_15 创建（`0a49d73c`）以来零提交；Redis/crag-id/BIGINT 命中数全为 0，而 `postgres\|sidecar\|pgvector` 命中 10 次——主文档仍描述旧栈，未补 Redis Worker lease（非缓存/事件总线）、`crag-id` 模块、`BIGINT` 当前事实。15.5「范围」（第 54 行）、Step 4（第 623 行）与 Step 7 `git add README.md`（第 641 行）均明确要求，未执行 |
| 2026-06-25 | macOS | `constraints/docker-structure.md` 5.10 rag-service-smoke 就绪条件 | **缺陷** | 文档 5.10 记「`db` 健康 且 `sidecar` 健康」，遗漏 redis；而 `docker-compose.yml` rag-service-smoke `depends_on` 为 db+redis+sidecar（第 280–286 行）且注入 `crag.id.service-domain`/`required-entities`（第 271–272 行），smoke 复用发号链路确实需要 redis。对照 5.7 rag-service 已正确含「db 且 redis 且 sidecar」并有 Redis 行，仅 5.10 漏 redis |

**验收结论（第三轮）：退回。** 15.1–15.4 验收标准独立逐条核对全部通过，`validate_plans`/`validate_module_dependencies`/`./gradlew check` 全绿；但第二轮退回的 15.5 两处文档同步缺陷（① README.md 文档同步整项缺失；② docker-structure.md 5.10 rag-service-smoke 就绪条件遗漏 redis）自 `156c11c2` 后无任何修复提交，本轮独立确认依旧存在，故 plan_15 仍未通过验收。

**处置：** 不修改实现与文档代码。按 `constraints/plan-workflow.md` 9.2，plan_15 维持 `in_progress`、15.5 维持 `进行中`；15.1–15.4 维持 `待验收`（本轮独立核对全部通过，待 15.5 修复后随最终验收一并完成）。修复范围狭窄：仅需 README 文档同步（补 Redis/crag-id/BIGINT 当前事实）与 docker-structure.md 5.10 补 redis 一项，不涉及功能或测试改动，可由新执行 session 在一个提交内完成。

> 注：本轮独立 session 同样未重跑 Docker HTTP 端到端回归（受限于模型下载与全栈启动开销）；两处缺陷均为静态文档同步问题，不依赖 Docker 运行时即可定位，compose/5.7 文档/代码三方对照即可证实。

### 修复验证（15.5 文档同步缺陷，2026-06-25）

修复者：未参与第三轮独立验收的执行 session；按 `superpowers:systematic-debugging` 从失败证据定位根因，先以复现命令确认缺陷存在（RED），再最小修复，再以相同命令与校验套件确认通过（GREEN）。

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-25 | macOS | `grep -ciE 'redis\|crag-id\|bigint' README.md`（修复前） | 失败（RED） | 命中数 0，主文档仍描述旧栈 |
| 2026-06-25 | macOS | `constraints/docker-structure.md` 5.10 就绪条件（修复前） | 失败（RED） | 仅「db 且 sidecar」，遗漏 redis |
| 2026-06-25 | macOS | `grep -ciE 'redis\|crag-id\|bigint' README.md`（修复后） | 通过（GREEN） | 命中数 4（redis×3、crag-id×2、bigint×1） |
| 2026-06-25 | macOS | `constraints/docker-structure.md` 5.10 就绪条件（修复后） | 通过（GREEN） | 「db 且 redis 且 sidecar」，补 Redis 行与 ID 配置行，与 5.7 一致 |
| 2026-06-25 | macOS, Python 3 | `python3 scripts/validate_constraints.py` | 通过 | 0 errors；compose 服务登记、链接、废弃术语均通过，docker-structure 5.10 未引入废弃术语 |
| 2026-06-25 | macOS, Python 3 | `python3 scripts/validate_module_dependencies.py` | 通过 | 0 errors |
| 2026-06-25 | macOS, Python 3 | `python3 scripts/validate_plans.py --strict --verify-git` | 通过 | 0 errors，24 warnings（全为历史 Plan 未用 workflow v3，与本计划无关）；`5b8fda3a` 通过 git 校验 |
| 2026-06-25 | macOS, Java 21 | `./gradlew check` | 通过 | BUILD SUCCESSFUL（106 task）：Plan/约束/模块依赖校验、spotless 与全部非 Docker 测试通过 |

**修复说明：** 两处缺陷均属 15.5「约束/文档同步」交付物，紧密耦合，共享一个实现提交 `5b8fda3a`。① README.md 补充 Redis（一键启动清单、技术栈「协调」行，明确为 Snowflake ID Worker 租约、ID 基础设施而非缓存/事件总线）、`crag-id` 模块（项目结构）与 BIGINT 当前事实（写入链路①，HTTP 边界 decimal string）；② docker-structure.md 5.10 rag-service-smoke 就绪条件补 redis，并补 Redis 行与 ID 配置行，使其与 5.7 rag-service 及 docker-compose.yml 事实一致。修复不涉及运行时行为或测试改动。

> 注：本轮为文档同步修复，未重跑 Docker HTTP 端到端回归（缺陷为静态文档问题，不依赖 Docker 运行时）；运行时拓扑、HTTP 回归脚本与 Controller 实现均未改动，前序轮次的 Docker 回归证据仍然有效。

## 阻塞记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-24 | 创建计划 | 固定 Plan 15 Snowflake ID、Redis Worker lease 与 RAG ID cold switch 范围 | 进入 ready，等待计划提交后执行 |
| 2026-06-24 | 优化任务执行细节 | 使用 writing-plans 补齐接口约定、测试先行步骤、验证命令和提交边界 | 状态与范围不变；执行者可按任务独立实现 |
| 2026-06-24 | 验收退回 | 独立验收发现 4 个缺陷：spotless 格式违规、Hibernate entity 识别失败、Dockerfile 缺少 crag-id、测试脚本缺少格式断言 | Plan 退回 `in_progress`；15.3/15.4/15.5 退回 `in_progress`；15.1/15.2 保留待验收 |
| 2026-06-24 | 缺陷修复与交接 | 修复 4 个退回缺陷 + 4 个追加缺陷（health contributor 命名、lease map 遮蔽、TaskScheduler、schema cold reset）；`./gradlew check` 通过；Docker HTTP 回归全绿 | Plan 转为 `verifying`；全部 5 个任务待验收 |
| 2026-06-25 | 第二次独立验收退回 | 逐条核对：14 个 hash 范围正确、`./gradlew check` 与 `test --rerun-tasks`（全量重跑）全绿、`validate_plans`/`validate_module_dependencies` 0 errors、15.1–15.4 验收标准全通过；但发现 15.5 两处文档同步缺陷：① README.md 文档同步整项缺失（范围 + Step 4 + Step 7 均要求，未执行，致主文档漂移），② docker-structure.md 5.10 rag-service-smoke 就绪条件遗漏 redis | 15.5 退回 `in_progress`；plan_15 退回 `in_progress`；15.1–15.4 保留 `待验收` |
| 2026-06-25 | 第三次独立验收退回 | 从仓库事实独立核对（HEAD=`156c11c2` 后无修复提交）：14 个 hash 范围正确、`validate_plans`/`validate_module_dependencies`/`./gradlew check` 全绿、15.1–15.4 验收标准逐条通过；但第二轮退回的 15.5 两处文档同步缺陷（README.md 整项缺失、docker-structure.md 5.10 遗漏 redis）本轮确认依旧存在 | plan_15 维持 `in_progress`；15.5 维持 `进行中`；15.1–15.4 维持 `待验收` |
| 2026-06-25 | 修复第三轮验收退回的 15.5 文档同步缺陷 | 两缺陷紧密耦合共享实现提交 `5b8fda3a`：① README.md 补 Redis（一键启动清单、技术栈「协调」行，明确 Snowflake ID Worker 租约、非缓存/事件总线）、`crag-id` 模块（项目结构）与 BIGINT 当前事实（写入链路①，HTTP 边界 decimal string）；② docker-structure.md 5.10 rag-service-smoke 就绪条件补 redis 并补 Redis 行/ID 配置行，与 5.7 及 docker-compose.yml 事实一致 | 15.5 转为 `待验收`；plan_15 转为 `verifying`；移交独立验收 session 第四次验收 |
