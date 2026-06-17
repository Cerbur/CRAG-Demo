# plan_5 — Java Module 拆分

> 创建日期：2026-06-14  
> 状态：✅ 已完成  
> 当前阶段：所有 module 拆分完毕，Gradle 编译与 37 个单元测试全部通过。

## 范围

本计划目标是把当前单 Spring Boot 工程拆成更清晰的 Java modules，为后续按微服务拆分做准备。

核心目标只有一个：

> 将 ingestion 逻辑迁移到独立 ingestion module，并为后续 retrieval module、query module 的实现预留清晰边界。

同时在本计划中完成 Java base package 调整：

> 将现有 Java 包名统一迁移为 `ai.cerbur.crag`。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 5.1 | 讨论并确定 module 边界和依赖方向 | ✅ | — | 2026-06-14 |
| 5.2 | 讨论并确定共享库表 entity / dao 维护方式 | ✅ | — | 2026-06-14 |
| 5.3 | 制定 Gradle multi-module 结构迁移步骤 | ✅ | — | 2026-06-14 |
| 5.4 | Java base package 迁移到 `ai.cerbur.crag` | ✅ | — | 2026-06-14 |
| 5.5 | 迁移 ingestion 代码到 ingestion module | ✅ | — | 2026-06-14 |
| 5.6 | 为 retrieval / query module 预留接口与包结构 | ✅ | — | 2026-06-14 |
| 5.7 | 更新包结构、测试工作流和 README 相关说明 | ✅ | — | 2026-06-14 |

整体进度：7 / 7（100%）

## 初始拆分方向

候选 Java module：

- `crag-common`：跨模块共享的基础类型、异常、响应结构、少量工具。
- `crag-storage`：数据库 entity、repository、dao、schema 访问适配。
- `crag-ingestion`：AdminRag 写入链路、ChunkSplit、Dense/Sparse 索引写入状态推进、Cron 编排。
- `crag-retrieval`：Sparse/Dense 查询召回、child chunk 维度 RRF、相邻 child 扩展、Rerank，对外提供问题到 chunks 的检索门面。
- `crag-query`：UserQuery 应用编排、调用 RetrievalService 获取 chunks、Context 工程、LLM 调用和 answer/sources 组装。
- `crag-admin`：五大领域之外的更高一层 API service，承载当前 Admin/Test HTTP 入口和跨领域编排入口。
- `crag-app`：Spring Boot 启动模块，负责装配各业务 module。

## 已确认决策

### 5.1 Module 运行形态

`plan_5` 只做 Java module 拆分，不拆成多个独立 Spring Boot 服务。

- `crag-app` 是唯一 Spring Boot 启动模块，负责装配 `crag-admin`、`crag-ingestion`、`crag-retrieval`、`crag-query`、`crag-storage` 等模块。
- `crag-admin` 是更高一层 API service module，承载当前 `AdminRagController`、`TestController` 等 HTTP 入口。
- `crag-ingestion`、`crag-retrieval`、`crag-query` 是普通 Java/Spring module，提供各自领域 Bean，不直接承载 HTTP controller。
- 当前运行时仍是一个进程、一个端口、一个 Docker service。
- 未来需要微服务化时，再把 module 提升为独立 Spring Boot 服务。

依赖方向：

```text
crag-app
├── crag-admin
├── crag-ingestion
├── crag-retrieval
├── crag-query
├── crag-storage
└── crag-common

crag-admin -> crag-ingestion, crag-retrieval, crag-query, crag-common
crag-ingestion -> crag-retrieval, crag-storage, crag-common
crag-retrieval -> crag-storage, crag-common
crag-query -> crag-retrieval, crag-common
crag-storage -> crag-common
```

关键决策：

- Embedding client 放在 `crag-retrieval`。
- 后续 ingestion 需要 embedding 时，通过 `crag-retrieval` 暴露的 embedding 能力调用，不在 ingestion 内单独维护一套模型 client。
- 当前 Admin/Test HTTP 入口抽到 `crag-admin`，避免 controller 污染 ingestion / retrieval / query 的领域分层。
- `crag-app` 只做启动、配置装配和资源承载，不放业务逻辑。

### 5.3 Gradle multi-module 迁移步骤

执行顺序：

1. 创建 multi-module 目录：`crag-common`、`crag-storage`、`crag-retrieval`、`crag-ingestion`、`crag-query`、`crag-admin`、`crag-app`。
2. 更新 `settings.gradle.kts`，连续 include 上述 modules。
3. 调整 root `build.gradle.kts`：保留公共 Java 21、repositories、测试配置；Spring Boot 插件不在 root 全局启用。
4. `crag-app` 应用 `org.springframework.boot` 和 `io.spring.dependency-management`，作为唯一可启动 jar。
5. 其他 module 使用 `java-library`，按需引入 Spring Web / JPA / Validation / Test 依赖，不生成 boot jar。
6. 先完成 `com.crag.demo` -> `ai.cerbur.crag` 包名迁移，再按 module 边界移动源码。
7. `schema.sql`、`data.sql`、`application.yml` 暂时放在 `crag-app/src/main/resources`，保持单服务启动形态。
8. 测试按 module 归属移动：ChunkSplit / AdminRagService 测试进入 ingestion 相关模块，Spring context 测试进入 `crag-app`。
9. 更新 `constraints/package-structure.md`、`constraints/test-workflow.md` 和 README 中受影响的路径说明。
10. 运行 Gradle 编译和测试，确认 multi-module 依赖、Spring 扫描、JPA 扫描、资源加载都正常。

验收：

- `./gradlew test` 通过。
- 只有 `crag-app` 生成可启动 Spring Boot jar。
- `crag-admin` 承载 HTTP API service，领域 modules 不直接承载 controller。
- `crag-ingestion` 可以通过 `crag-retrieval` 使用 embedding 能力。
- `constraints/package-structure.md` 与实际 module / package 结构一致。

### 5.4 Java base package 迁移

本计划执行 module 拆分时，同步将 Java base package 迁移为：

```text
ai.cerbur.crag
```

迁移范围：

- `src/main/java` 下所有生产代码 package 声明和 import。
- `src/test/java` 下所有测试代码 package 声明和 import。
- Spring Boot 主类、组件扫描、JPA entity/repository 扫描路径。
- 文档中的包结构说明，尤其是 `constraints/package-structure.md`。

验收：

- 项目中不再保留旧 base package。
- Gradle 编译和测试通过。
- 包结构文档与实际目录一致。

### 5.2 共享库表 entity / dao 维护方式

背景：

- ingestion module 会写 `chunk`、`chunk_embedding`、`chunk_fts`。
- retrieval module 会读同一组表，召回 child chunk，并在 Rerank 前扩展同 parent 下的相邻 child chunk。
- 两个 module 读写的是同一套数据库模型，但职责不同：ingestion 是写模型与状态推进，retrieval 是读模型与排序召回。

已讨论问题：

- `Chunk`、`ChunkEmbedding`、`ChunkFts` 这类 JPA entity 应该放在共享 storage module，还是在 ingestion / retrieval 各自维护独立读写模型？
- `ChunkDao` 是否继续作为共享 Dao，还是拆成 `IngestionChunkDao` 与 `RetrievalChunkDao`，分别暴露写入状态推进和查询召回所需能力？
结论：

- 当前 modular monolith 阶段，采用 `crag-storage` 共享 persistence model。
- `Chunk`、`ChunkEmbedding`、`ChunkFts` 等 JPA entity 统一放在 `crag-storage`。
- Spring Data repository 也放在 `crag-storage`，作为底层数据库访问适配。
- 不继续扩大共享的“大而全 Dao”；按业务方向拆成窄接口。
- ingestion module 只使用 `IngestionChunkDao` / `IngestionChunkGateway` 这类写侧接口，暴露 chunk 写入、Dense/Sparse 状态推进、索引写入幂等能力。
- retrieval module 只使用 `RetrievalChunkDao` / `RetrievalChunkGateway` 这类读侧接口，暴露 Sparse/Dense 查询召回、parent chunk 回表能力。
- service/core 层不直接传递 JPA entity，统一转换成用例 DTO 或领域结果类型，例如 `ChunkSearchResult`、`IndexedChunkView`、`ChunkWriteCommand`。

取舍：

- 共享 entity/repository 能减少当前阶段的重复映射、schema 同步和测试成本。
- 拆窄 Dao/Gateway 能避免 ingestion 与 retrieval 在应用层互相知道对方能力。
- `crag-storage` 在当前阶段是技术适配模块，不代表未来微服务之间共享 Java entity jar。

未来拆微服务时：

- 允许 ingestion 与 retrieval 共享同一套数据库 schema 约定，但不再强制共享 Java entity。
- 各服务可以按自己的 bounded context 维护 persistence model。
- 跨服务协作优先通过事件、API 或明确的数据契约完成，而不是通过共享 Dao 或共享 JPA entity 完成。

## 暂不执行事项

- 暂不拆成多个独立 Spring Boot 服务。
- 暂不调整 Docker 部署结构；仍由一个应用服务启动。
- 暂不引入 MQ / 服务间 RPC；module 之间仍为进程内调用。
- 暂不新增 `crag-model-client`；Embedding client 先归属 `crag-retrieval`。
