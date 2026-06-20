# CRAG-Demo 总体规划（plan_main）

> 创建时间：2026-06-10
> 最后更新：2026-06-14（按 DDD 领域边界重画项目架构）

---

## 一、项目定位

实现一个**开箱即用**的基于 RAG（Retrieval-Augmented Generation）的问答机器人后端服务。

目标不是一次性做完完整知识库产品，而是先做一个能清楚演示 RAG 后端核心链路的 Demo：从纯文本入库，到混合检索，再到重排与 LLM 生成，开发者可以用 Docker Compose 在本地快速跑通。

架构上按 DDD 领域边界设计，长期形态可以拆成多个微服务；当前阶段先以单个 Spring Boot 服务承载这些领域模块，所有异步监听统一用 Cron 定时扫表实现。

核心原则：

- 一键部署：Docker Compose 包含应用、PostgreSQL + pgvector、模型 sidecar。
- 零鉴权：Demo 阶段对外 API 直接可用。
- 接口简洁：围绕 `UserQuery` 与 `AdminRag` 两个核心接口推进。
- 检索可解释：保留 sources，让用户能看到回答依据。

当前阶段非目标：

- 不实现用户、租户、权限、计费、多知识库管理；但在架构边界中为 Access 与 KnowledgeBase 领域预留位置。
- 不做文件解析、OCR、网页抓取等复杂采集链路。
- 不做企业级消息队列、分布式调度或多副本一致性。
- 不为了抽象而抽象；Demo 阶段优先保持链路清楚、可运行、可验证。

---

## 二、产品边界

### 2.1 AdminRag

管理端只负责把一段纯文本放进 RAG 知识库：

```text
POST /api/v1/admin/rag
```

输入包含标题、正文和可选 metadata。接口同步完成 chunk 写入后返回；Embedding 和检索索引通过异步任务继续处理。

### 2.2 UserQuery

用户端只负责对知识库提问并拿到答案：

```text
POST /api/v1/query
```

输出包含 answer 和 sources。sources 用于解释答案依据，避免 Demo 变成一个不可追溯的黑盒问答接口。

---

## 三、技术方向

| 层级 | 方向 | 说明 |
| --- | --- | --- |
| 后端 | Java 21 + Spring Boot 4.1.0 (Framework 7) | Demo 主服务 |
| 构建 | Gradle Kotlin DSL | 统一构建入口 |
| 数据 | PostgreSQL + pgvector | 元数据、全文检索、向量检索 |
| Embedding | Python Sidecar `/embed` | gte 中文 embedding，768 维 |
| Rerank | Python Sidecar `/rerank` | bge-reranker-v2-m3 |
| LLM | DeepSeek API + Spring AI 2.0.0 | 一期接入 DeepSeek，integration 层保留扩展空间 |
| 部署 | Docker + Docker Compose | 本地一键启动 |

---

## 四、RAG 主链路

### 4.1 入库链路

```text
AdminRag HTTP
  -> ChunkSplit(parent + child)
  -> 写入 chunk 表
  -> HTTP 返回 PENDING
  -> Dense Cron 扫 child chunk
  -> Sidecar /embed
  -> 写入 chunk_embedding
  -> Sparse Cron / FTS 索引构建
```

方向约束：

- 一期只支持纯文本，不做文件解析。
- chunk 使用 parent + child 结构：parent 保存上下文窗口，child 作为检索粒度。
- parent chunk 不做 embedding，不参与 FTS；child chunk 才进入 Dense 和 Sparse 检索索引。
- Dense 与 Sparse 两条索引链路独立推进，状态独立、失败可重试。
- HTTP 请求不阻塞在 embedding 上；Demo 阶段用 Cron 处理异步任务。

### 4.2 查询链路

```text
UserQuery HTTP
  -> question embedding
  -> Dense Query(pgvector, child chunk)
  -> Sparse Query(PostgreSQL FTS, child chunk)
  -> RRF 融合(child chunk)
  -> top RRF child + 相邻 child 扩展
  -> Rerank(child chunk candidates)
  -> LLM 生成
  -> answer + sources
```

方向约束：

- Dense 检索与 Sparse 检索都以 child chunk 为命中粒度。
- RRF 保持 child chunk 维度，只负责融合双路检索排名，不直接做语义判断。
- Rerank 候选由 top RRF child chunk 及其同 parent 下相邻 child chunk 组成，避免孤立 child 截断上下文。
- Rerank 对 child chunk 候选重新排序后，再交给 LLM 生成答案。
- Demo 阶段不做流式返回和用户鉴权。

---

## 五、关键设计决策

| 决策 | 当前方向 |
| --- | --- |
| 检索方案 | BM25/FTS Sparse + pgvector Dense，然后 RRF 融合 |
| Chunk 策略 | parent + child；child 检索，parent 提供上下文 |
| Embedding 范围 | 仅 child chunk 存储向量 |
| Sparse 范围 | 仅 child chunk 进入 FTS |
| 异步方式 | Demo 阶段使用 Spring `@Scheduled` Cron |
| 架构形态 | DDD 领域模块优先，当前单服务承载，未来可按领域拆微服务 |
| Sidecar 模型 | Embedding 与 Rerank 放在 Python Sidecar |
| LLM 方案 | 一期 DeepSeek API，Spring AI 管理 |
| 数据迁移 | 一期使用 `schema.sql`，不引入迁移框架 |

---

## 六、架构边界

### 6.1 DDD 领域边界架构图

```text
当前实现形态：Modular Monolith / 单 Spring Boot 服务
未来演进方向：按领域拆分为 Access、KnowledgeBase、Ingestion、Retrieval、Query 微服务

┌────────────────────────────────────────────────────────────────────────────┐
│ CRAG-Demo Application                                                     │
│ 单服务承载多个 DDD 领域模块；模块间通过应用服务/领域接口协作              │
└────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────┐       keychain / tenant context
│ Access（未规划）             │──────────────────────────────────────┐
│ - 用户、租户、成员关系        │                                      │
│ - 鉴权与访问控制              │                                      ▼
│ - KnowledgeBase keychain 创建 │                         ┌──────────────────────────────┐
│ - Query 侧 keychain 校验      │                         │ Query（已规划）              │
└──────────────┬───────────────┘                         │ - UserQuery API              │
               │ tenant ownership                         │ - Context 工程               │
               ▼                                          │ - LLM 调用                   │
┌──────────────────────────────┐                         │ - answer + sources           │
│ KnowledgeBase（未规划）      │                         └──────────────┬───────────────┘
│ - Tenant -> KnowledgeBase    │                                        │ recall request
│ - KnowledgeBase -> Document  │                                        ▼
│ - 文档归属关系维护           │                         ┌──────────────────────────────┐
└──────────────┬───────────────┘                         │ Retrieval（已规划）          │
               │ document changes                         │ - 查询 Sparse                │
               ▼                                          │ - 查询 Dense                 │
┌──────────────────────────────┐                         │ - RRF 融合                   │
│ Ingestion（已规划）          │                         │ - 回表召回 Chunk             │
│ - 监听 Doc 变更事件          │                         └──────────────┬───────────────┘
│ - Doc -> Chunk               │                                        │ read
│ - Chunk -> Sparse Index 写入 │                                        │
│ - Chunk -> Dense 写入        │                                        │
└──────────────┬───────────────┘                                        │
               │ write                                                  │
               ▼                                                        ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ PostgreSQL + pgvector                                                     │
│ tenant / user / keychain（未来）                                           │
│ knowledge_base / document（未来）                                          │
│ chunk / sparse index / dense embedding（当前主线）                         │
└──────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────┐
│ Cron Listeners                                                            │
│ 当前所有监听统一由 Spring @Scheduled 定时扫表实现                         │
│ - Doc 变更 -> Ingestion Chunk 处理                                         │
│ - Chunk 变更 -> Sparse 写入                                                │
│ - Chunk 变更 -> Dense 写入                                                 │
│ - 失败任务重试、状态推进、CAS 抢占                                         │
└──────────────────────────────────────────────────────────────────────────┘
```

责任边界：

- `Access` 管理用户、多租户、鉴权、租户成员关系，以及访问 KnowledgeBase 的 keychain。
- `KnowledgeBase` 管理租户与知识库关系、知识库与文档关系，不负责 Chunk、Embedding、检索索引。
- `Ingestion` 负责写链路：Doc 变更监听、Doc -> Chunk、Chunk -> Sparse/Dense 写入，以及 Chunk/Sparse/Dense 存储的写入状态推进。
- `Retrieval` 负责读链路：查询 Sparse/Dense、RRF 融合、召回 Chunk、Rerank，对外提供问题到 chunks 的检索门面，不负责生成最终回答。
- `Query` 负责问答链路：接收 UserQuery、调用 Retrieval 获取 chunks、组织 context、调用 LLM、生成 answer 与 sources。
- `Cron` 是当前阶段的事件监听实现方式；未来拆微服务后可以替换为 MQ/事件总线，但领域边界不随触发方式改变。

### 6.2 领域职责表

| 领域 | 状态 | 职责 |
| --- | --- |
| Access | 未规划 | 用户、多租户、租户成员、鉴权、KnowledgeBase keychain |
| KnowledgeBase | 未规划 | Tenant 与 KnowledgeBase 关系、KnowledgeBase 与 Document 关系 |
| Ingestion | 已规划 | Doc -> Chunk，Chunk 生成 Sparse/Dense 索引，维护写入状态 |
| Retrieval | 已规划 | 查询 Sparse/Dense，RRF 融合，召回 Chunk，Rerank 后返回 chunks 给 Query |
| Query | 已规划 | UserQuery、调用 Retrieval 获取 chunks、Context 工程、LLM 调用、答案生成 |
| Cron Listeners | 已规划 | 当前阶段所有监听、异步任务和状态推进的实现方式 |

### 6.3 模块职责约束

- 正式 HTTP Controller、请求 DTO、校验和异常转换统一属于 API 边界模块。
- Repository、Entity 与数据库投影统一属于 Storage；业务模块通过受控 DAO 或公开结果访问持久化能力。
- Ingestion、Retrieval 与 Query 只拥有各自业务编排、领域能力和外部适配，不机械复制 Controller、DAO 等横向分层。
- App 仅作为组合根；Smoke 仅作为显式启用的诊断例外。

模块职责、依赖白名单、公开 API 和迁移期偏差以 [`constraints/package-structure.md`](../constraints/package-structure.md) 为唯一事实来源。

---

## 七、阶段路线

产品主线按“入库 → 检索 → 问答 → 部署体验收口”推进。工程治理、模块迁移和测试工作流是服务产品主线的前置工作，不在这里复制执行状态。

当前唯一执行队列、计划状态、历史小数计划和 Hotfix 统一查看 [`plan/index/README.md`](./index/README.md)。

---

## 八、决策入口

- Plan 工作流、目录、命名、索引和进度规则：[`constraints/plan-workflow.md`](../constraints/plan-workflow.md)
- Java 代码风格：[`constraints/code-style.md`](../constraints/code-style.md)
- Java 包结构：[`constraints/package-structure.md`](../constraints/package-structure.md)
- Docker 部署结构：[`constraints/docker-structure.md`](../constraints/docker-structure.md)

`plan_main` 维护项目方向、产品边界、主链路和关键技术决策；不承载完整执行计划索引、任务进度表或 hotfix 明细。
