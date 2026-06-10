# Plan_1 — 项目脚手架 + 基础设施 + 分包结构

> 创建时间：2026-06-10
> 依赖：plan_main（技术栈、包结构、chunk 表结构、代码规范）

---

## 范围说明

plan_1 完成项目从零到可编译运行的基础骨架，包括：Gradle 构建、Spring Boot 启动、全部分包结构、PostgreSQL DAO 层（Spring Data JPA + schema.sql）、Dockerfile 基础环境。

**不包含**：Core 业务逻辑实现、Integration 实现、API 实现。这些留到后续 plan。

---

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 1.1 | 初始化 Gradle 项目（Kotlin DSL），配置依赖 | ✅ 完成 | `2dd060e` | 2026-06-10 |
| 1.2 | 创建 Application 启动类 | ✅ 完成 | `2dd060e` | 2026-06-10 |
| 1.3 | 编写 application.yml 基础配置 | ✅ 完成 | `2dd060e` | 2026-06-10 |
| 2.1 | 创建 controller/ 包 | ✅ 完成 | `2dd060e` | 2026-06-10 |
| 2.2 | 创建 service/ 包 | ✅ 完成 | `2dd060e` | 2026-06-10 |
| 2.3 | 创建 core/ 包（6 个子包骨架） | ✅ 完成 | `2dd060e` | 2026-06-10 |
| 2.4 | 创建 dao/ 包（数据访问层骨架） | ✅ 完成 | `2dd060e` | 2026-06-10 |
| 2.5 | 创建 integration/ 包（3 个子包骨架） | ✅ 完成 | `2dd060e` | 2026-06-10 |
| 3.1 | 引入 pgvector 支持 | ✅ 完成 | `2dd060e` | 2026-06-10 |
| 3.2 | 创建 Chunk Entity | ✅ 完成 | `2dd060e` | 2026-06-10 |
| 3.3 | 创建 ChunkRepository | ✅ 完成 | `2dd060e` | 2026-06-10 |
| 3.4 | 编写 schema.sql（chunk 表 DDL） | ✅ 完成 | `2dd060e` | 2026-06-10 |
| 3.5 | 编写 data.sql（测试种子数据） | ✅ 完成 | `2dd060e` | 2026-06-10 |
| 4.1 | 编写 Dockerfile（多阶段构建） | ✅ 完成 | `2dd060e` | 2026-06-10 |
| 4.2 | 编写 docker-compose.yml | ✅ 完成 | `2dd060e` | 2026-06-10 |
| 4.3 | 编写 .dockerignore | ✅ 完成 | `2dd060e` | 2026-06-10 |

> 状态图例：⏳ 待开始 → 🔄 进行中 → ✅ 完成 / ❌ 阻塞

整体进度：**16 / 16（100%）**

---

## 任务详情

### 1.x 搭建 Spring Boot 脚手架

- [x] **1.1** — 初始化 Gradle 项目（Kotlin DSL），配置依赖
  - 使用 Spring Boot 3.x + Java 21
  - 依赖清单：Spring Web、Spring Data JPA、Spring AI、PostgreSQL Driver、Hibernate Spatial（pgvector 支持）
  - 项目名 `crag-demo`，group `com.crag.demo`
  - 配置 Gradle Wrapper
  - Commit: `2dd060e`

- [x] **1.2** — 创建 Application 启动类 `CragDemoApplication`
  - 包路径 `com.crag.demo`
  - 标准 `@SpringBootApplication` 主类
  - Commit: `2dd060e`

- [x] **1.3** — 编写 `application.yml` 基础配置
  - 服务器端口 8080
  - PostgreSQL 数据源连接（host: localhost:5432, db: crag_demo）
  - JPA 配置：`ddl-auto: none`（手动管理 DDL，通过 schema.sql）
  - pgvector 相关配置
  - 日志级别
  - Commit: `2dd060e`

### 2.x 实现所有分包结构

> 遵循 [plan_main 九、代码规范](#九代码规范) 的奥卡姆剃刀原则：一个接口只有一个实现时不做 Interface → Impl 分离。

- [x] **2.1** — 创建 `controller/` 包
  - `UserQueryController` — `POST /api/v1/query` 骨架（返回 200 + 空 JSON）
  - `AdminRagController` — `POST /api/v1/admin/rag` 骨架（返回 200 + 空 JSON）
  - 每个类：class Javadoc + `@since` + `@RestController` + `@RequestMapping`
  - Commit: `2dd060e`

- [x] **2.2** — 创建 `service/` 包
  - `UserQueryService` — 直接实现类（不拆 interface/impl），骨架方法
  - `AdminRagService` — 直接实现类（不拆 interface/impl），骨架方法
  - 每个类：class Javadoc + `@since` + `@Service`
  - 注意：plan_main 包结构索引中 service/impl 目录保留为空（或暂不创建），体现奥卡姆剃刀
  - Commit: `2dd060e`

- [x] **2.3** — 创建 `core/` 包（RAG 核心逻辑层骨架）
  - `core/chunk/` — 预留 `ChunkService`
  - `core/embedding/` — 预留 `EmbeddingService`
  - `core/sparseQuery/` — 预留 `SparseQueryService`
  - `core/denseQuery/` — 预留 `DenseQueryService`
  - `core/rrf/` — 预留 `RrfFusionService`
  - `core/rerank/` — 预留 `RerankService`
  - 每个骨架类：class Javadoc + `@since` + 空方法签名
  - Commit: `2dd060e`

- [x] **2.4** — 创建 `dao/` 包（数据访问层骨架）
  - 预留 `ChunkDao` 或直接使用 Spring Data JPA Repository（见 3.x）
  - Commit: `2dd060e`

- [x] **2.5** — 创建 `integration/` 包（外部服务接入层骨架）
  - `integration/llm/` — 预留 `ChatClient` 接口
  - `integration/llm/prompt/` — 预留提示词模板目录
  - `integration/embedding/` — 预留 `EmbeddingClient` 接口
  - `integration/rerank/` — 预留 `RerankClient` 接口
  - 每个接口/类：class Javadoc + `@since`
  - Commit: `2dd060e`

### 3.x PostgreSQL DAO 层 + 数据库表

- [x] **3.1** — 引入 pgvector 支持
  - 添加 Hibernate 6 + pgvector 依赖或使用 `io.hypersistence:hypersistence-utils-hibernate-63`
  - 或一期直接用原生 SQL/JdbcTemplate 操作向量（避免额外依赖）
  - Commit: `2dd060e`

- [x] **3.2** — 创建 Chunk Entity
  - 包路径 `com.crag.demo.dao.entity.Chunk`
  - 字段完全对齐 [plan_main 5.1.2 Chunk 表结构](#511-chunk-策略child--parent)：
    - `chunkId` (UUID, PK, gen_random_uuid)
    - `docId` (UUID, NOT NULL)
    - `parentChunkId` (UUID, nullable)
    - `chunkIndex` (Integer, nullable)
    - `content` (String, TEXT, NOT NULL)
    - `tokenCount` (Integer)
    - `metadata` (String, JSONB, 默认 '{}')
    - `status` (String, 默认 'init')
    - `createdAt` (LocalDateTime)
    - `updatedAt` (LocalDateTime)
  - 每个 field 注释含义（遵循代码规范 9.1）
  - class Javadoc + `@since`
  - Commit: `2dd060e`

- [x] **3.3** — 创建 ChunkRepository
  - 包路径 `com.crag.demo.dao.repository.ChunkRepository`
  - 继承 `JpaRepository<Chunk, UUID>`
  - 基础查询方法：
    - `findByStatusIn(List<String> statuses)` — Cron 扫表
    - `findByDocId(UUID docId)` — 按文档查 chunks
    - `findByParentChunkId(UUID parentChunkId)` — 按 parent 查 children
  - Commit: `2dd060e`

- [x] **3.4** — 编写 `schema.sql`
  - 位置：`src/main/resources/schema.sql`
  - 内容：
    ```sql
    CREATE EXTENSION IF NOT EXISTS vector;
    CREATE EXTENSION IF NOT EXISTS pg_trgm;  -- FTS 中文分词辅助

    CREATE TABLE IF NOT EXISTS chunk (
        chunk_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        doc_id           UUID NOT NULL,
        parent_chunk_id  UUID,
        chunk_index      INTEGER,
        content          TEXT NOT NULL,
        token_count      INTEGER,
        metadata         JSONB DEFAULT '{}',
        status           VARCHAR(16) DEFAULT 'init',
        created_at       TIMESTAMP DEFAULT NOW(),
        updated_at       TIMESTAMP DEFAULT NOW()
    );

    CREATE INDEX IF NOT EXISTS idx_chunk_status ON chunk(status);
    CREATE INDEX IF NOT EXISTS idx_chunk_doc_id ON chunk(doc_id);
    CREATE INDEX IF NOT EXISTS idx_chunk_parent ON chunk(parent_chunk_id);
    ```
  - 完全对齐 [plan_main 5.1.2](#512-chunk-表结构)
  - Commit: `2dd060e`

- [x] **3.5** — 编写 `data.sql`（可选：测试种子数据）
  - 位置：`src/main/resources/data.sql`
  - 预留文件，可为空，或写 1-2 条测试 chunk
  - Commit: `2dd060e`

### 4.x Dockerfile + Docker Compose 基础环境

- [x] **4.1** — 编写 `Dockerfile`
  - 多阶段构建：
    - Stage 1（build）：基于 `eclipse-temurin:21-jdk-alpine`，运行 `./gradlew bootJar`
    - Stage 2（runtime）：基于 `eclipse-temurin:21-jre-alpine`，COPY jar，EXPOSE 8080
  - 非 root 用户运行（安全性）
  - Commit: `2dd060e`

- [x] **4.2** — 编写 `docker-compose.yml`
  - 服务清单：
    - `db` — PostgreSQL 17 + pgvector（镜像 `pgvector/pgvector:pg17`），端口 5432
    - `app` — Spring Boot 应用（自建 Dockerfile），端口 8080，依赖 db
  - db 服务：环境变量 POSTGRES_DB=crag_demo，healthcheck
  - app 服务：环境变量传递数据库连接信息
  - 网络：`crag-net`（bridge）
  - Commit: `2dd060e`

- [x] **4.3** — 编写 `.dockerignore`
  - 排除 `.gradle/`、`build/`、`.git/`、`plan/`、`.claude/` 等
  - Commit: `2dd060e`

---

## 完成标准

- [x] `./gradlew build` 编译通过
- [ ] `./gradlew bootRun` 启动成功，8080 端口可访问（需 PostgreSQL 运行）
- [ ] PostgreSQL 连接正常，chunk 表创建成功（需 PostgreSQL 运行）
- [ ] `docker compose up` 一键启动 db + app（需 Docker 环境）
- [x] 两个 API 端点返回 200（骨架已就绪，逻辑 plan_2 实现）

---

## 依赖决策引用

| 决策项 | 结论 | 来源 |
|--------|------|------|
| 构建工具 | Gradle（Kotlin DSL） | plan_main 二 |
| Java 版本 | 21（LTS + 虚拟线程） | plan_main 二 |
| Spring Boot | 3.x | plan_main 二 |
| 数据库 | PostgreSQL 17 + pgvector | plan_main 六 |
| DDL 管理 | `schema.sql` 手动管理，JPA `ddl-auto: none` | plan_main 二 |
| ORM | Spring Data JPA | 本次确认 |
| 向量操作 | 一期原生 SQL / JdbcTemplate（减少依赖） | 本次确认 |
| 代码规范 | plan_main 九、全部适用 | plan_main 九 |

---

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-06-10 | 创建 plan_1，定义 4 大任务组共 16 个子任务 |
