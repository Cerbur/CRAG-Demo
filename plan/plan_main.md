# CRAG-Demo 总体规划（plan_main）

> 创建时间：2026-06-10
> 最后更新：2026-06-12（新增项目介绍文档与全链路架构 SVG）

---

## 一、项目定位

实现一个**开箱即用**的基于 RAG（Retrieval-Augmented Generation）的问答机器人后端服务。

**核心原则**：
- 一键部署（Docker Compose 包含所有中间件）
- 零鉴权（Demo 阶段对外 API 直接可用）
- 接口简洁（两个核心接口：UserQuery + AdminRag）

---

## 二、技术栈

| 层级 | 技术选型 | 说明 |
|------|----------|------|
| 语言 | Java 21 | LTS + 虚拟线程 |
| 框架 | Spring Boot 3.x + Spring AI | 后端框架 + LLM 接入管理 |
| 包管理 | Gradle（Kotlin DSL） | 构建工具 |
| LLM | DeepSeek API（Spring AI 适配） | 一期只接入 DeepSeek，兼容层预留多提供商扩展 |
| Embedding 模型 | gte-sentence-embedding_chinese-base（768 维） | Sidecar Python /embed（bi-encoder，ModelScope 实际可用模型） |
| Rerank 模型 | bge-reranker-v2-m3 | Sidecar Python /rerank（cross-encoder） |
| 容器化 | Docker + Docker Compose | 包含所有中间件 |
| 数据库初始化 | Spring schema.sql | 一期手动 DDL，不做迁移 |

---

## 三、对外 API 设计

### 3.1 UserQuery — 用户查询接口

```
POST /api/v1/query
Content-Type: application/json

Request:
{
  "question": "用户的问题内容"
}

Response:
{
  "answer": "基于 RAG 生成的回答",
  "sources": [
    {
      "docId": "文档ID",
      "content": "相关原文片段",
      "score": 0.95
    }
  ]
}
```

- Demo 阶段不做流式返回。
- 不做用户鉴权。
- 检索流程（混合检索 Hybrid Retrieval）：
  1. 问题 → Embedding 向量化
  2. 并行：BM25 Sparse 查询（PostgreSQL 全文检索） + Dense 查询（pgvector 向量相似度）
  3. RRF（Reciprocal Rank Fusion）融合双路结果
  4. 回表 PostgreSQL 获取完整 chunk 内容
  5. Rerank 重排序
  6. LLM 生成 → 返回

### 3.2 AdminRag — 管理端知识库上传接口

```
POST /api/v1/admin/rag
Content-Type: application/json

Request:
{
  "title": "文档标题",
  "content": "完整文本内容（纯文本，一期不做文件解析）",
  "metadata": {
    "tags": ["标签1", "标签2"]
  }
}

Response:
{
  "docId": "文档唯一标识",
  "chunks": 15,
  "status": "INDEXED"
}
```

- Demo 阶段不做鉴权，不做文件解析。
- 流程（知识入库）：
  1. 接收纯文本 → Chunk 分块（child chunk + parent chunk）
  2. Chunk 写入 PostgreSQL chunk 表，返回 success（同步）
  3. Cron 异步扫表 → Embedding 向量化 → 双写 pgvector + FTS 索引（异步）

---

## 四、分层架构

```
┌──────────────────────────────────────────────────┐
│  controller（API 入口）                           │
│  - UserQueryController                           │
│  - AdminRagController                            │
├──────────────────────────────────────────────────┤
│  service（业务编排）                              │
│  - UserQueryService / impl                       │
│  - AdminRagService / impl                        │
├──────────────────────────────────────────────────┤
│  core（RAG 核心逻辑）                             │
│  - chunk        → 文档分块策略（ChunkSplit）       │
│  - dense        → Dense 检索通道（Embedding + Query）│
│  - sparse       → Sparse 检索通道（BM25/FTS）      │
│  - rrf          → RRF 融合双路结果 + 回表          │
│  - rerank       → 结果重排序                      │
├──────────────────────────────────────────────────┤
│  dao（数据访问层）                                │
│  - pgvector 向量操作 + PostgreSQL FTS 索引操作     │
│  - 文档 / chunk 元数据 CRUD                       │
├──────────────────────────────────────────────────┤
│  integration（外部服务接入层）                     │
│  ├── llm/                                         │
│  │   - 基于 Spring AI 管理 LLM 调用                │
│  │   - 统一 ChatClient 接口（磨平不同提供商差异）    │
│  │   - 一期实现：DeepSeek API                      │
│  │   - 提示词模板管理（按模型/场景分目录）           │
│  ├── embedding/                                   │
│  │   - 统一 EmbeddingClient 接口                   │
│  │   - 一期实现：Sidecar Python /embed             │
│  └── rerank/                                      │
│      - 统一 RerankClient 接口                      │
│      - 一期实现：Sidecar Python /rerank            │
└──────────────────────────────────────────────────┘
```

---

## 五、混合检索流水线详解（Plan A 核心）

### 5.1 知识入库流程（AdminRag）

```
HTTP 纯文本
   │
   ▼
┌──────────────────────────────┐
│  Chunk 分块（同步）            │  ← Spring AI TokenTextSplitter（暂定）
│  child chunk + parent chunk  │
│  写入 PostgreSQL chunk 表     │
│  status = init                │
└────────────┬─────────────────┘
             │
             ▼
┌──────────────────────────────┐
│  HTTP 返回 success            │  ← 请求结束，后续异步处理
└──────────────────────────────┘

          ═══════════ 异步边界 ═══════════

             ▼
┌──────────────────────────────┐
│  Cron 定时扫表                │  ← Spring @Scheduled
│  WHERE status IN ('init','failed')
│  标记 status = processing    │
│  → Embedding 向量化           │
│  → 写入 pgvector + FTS 索引   │
│  → 标记 status = success/failed│
└──────────────────────────────┘
```

**设计说明**：
- HTTP 请求只做 chunk + 写表，立即返回，不阻塞在 embedding 上
- 异步处理通过 Spring `@Scheduled` cron 扫表实现（Demo 方案）
- 企业级替代方案：监听 binlog / Debezium → 消息队列 → 消费，目前不做

### 5.1.1 Chunk 策略：Child + Parent

```
原始文本
   │
   ▼
┌──────────────────────────────────────┐
│  Parent Chunk（大窗口，保留上下文）     │  ← 例：1024 token
│  ┌──────────────────────────────────┐│
│  │ Child Chunk 1 (256 token)        ││  ← 细粒度检索
│  │ Child Chunk 2 (256 token)        ││
│  │ Child Chunk 3 (256 token)        ││
│  │ Child Chunk 4 (256 token)        ││
│  └──────────────────────────────────┘│
└──────────────────────────────────────┘
```

- **Child Chunk**：小粒度的检索单元，用于与 query 做相似度匹配。**Child chunk 是唯一会被 Embedding 向量化的粒度**。
- **Parent Chunk**：大粒度的上下文窗口，检索命中 child 后回表取 parent 获得更完整上下文。**Parent chunk 不做向量化，只存储纯文本**。
- 入库时两种 chunk 同时写入 chunk 表，`parent_chunk_id` 建立关联
- **核心约束**：Sparse（BM25）和 Dense（pgvector）检索均在 child chunk 维度进行。Embedding 只存储 child chunk 的 vector，FTS 索引也只建在 child chunk 上。

### 5.1.2 Chunk 表结构

```sql
CREATE TABLE chunk (
    chunk_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id           UUID NOT NULL,              -- 关联文档
    parent_chunk_id  UUID,                        -- NULL=parent, 非NULL=child（指向parent）
    chunk_index      INTEGER,                     -- child 在 parent 中的序号（从0开始；parent 为 NULL）
    content          TEXT NOT NULL,               -- chunk 文本内容
    token_count      INTEGER,                     -- token 数量
    metadata         JSONB DEFAULT '{}',          -- 扩展元数据 {tags, ...}
    dense_status     SMALLINT DEFAULT 0,  -- Dense/Embedding 链路: 0=INIT 1=PROCESSING 2=SUCCESS 3=FAILED 4=SKIPPED
    sparse_status    SMALLINT DEFAULT 0,  -- Sparse/FTS 链路:   0=INIT 1=PROCESSING 2=SUCCESS 3=FAILED 4=SKIPPED
    version          INTEGER DEFAULT 0 NOT NULL,  -- 乐观锁版本号，每次 UPDATE 自动 +1（JPA @Version）
    created_at       TIMESTAMP DEFAULT NOW(),
    updated_at       TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_chunk_dense_status ON chunk(dense_status);
CREATE INDEX idx_chunk_sparse_status ON chunk(sparse_status);
CREATE INDEX idx_chunk_doc_id ON chunk(doc_id);
CREATE INDEX idx_chunk_parent ON chunk(parent_chunk_id);
```

> **chunk_index 说明**：`chunk_index` 记录 child chunk 在其 parent chunk 内的顺序位置（从 0 开始递增）。用于回表时保持原文片段顺序、相邻 chunk 扩展上下文等场景。Parent chunk 自身 `chunk_index = NULL`。
>
> **Status 语义说明**：Dense 和 Sparse 两条异步链路各自独立状态机，互不阻塞。
> - `dense_status`: 控制 Embedding 向量化流程，仅对 child chunk 有意义。Parent chunk 设为 `'skipped'`。
> - `sparse_status`: 控制 FTS 全文索引流程，仅对 child chunk 有意义。Parent chunk 设为 `'skipped'`。
> - Cron 扫表各自扫各自的：Dense Cron 扫 `dense_status IN ('init','failed')`，Sparse Cron 扫 `sparse_status IN ('init','failed')`。均通过 `parent_chunk_id IS NOT NULL` 过滤 child chunk。

### 5.1.3 Chunk 双状态机（Dense + Sparse 独立）

Dense 链路（Embedding 向量化）：

```
  init ──→ processing ──→ success
   │            │
   │            └──────→ failed（标记失败，cron 可重试）
   │
   └── skipped（parent chunk 无需 embedding）
```

Sparse 链路（FTS 全文索引）：

```
  init ──→ processing ──→ success
   │            │
   │            └──────→ failed（标记失败，cron 可重试）
   │
   └── skipped（parent chunk 无需 FTS）
```

| 状态 | 含义 | 触发 |
|------|------|------|
| `init` | 刚分块完成，等待处理 | AdminRag 写入时设置 |
| `processing` | 正在处理中 | Cron 扫到 init/failed 时标记 |
| `success` | 处理完成 | Cron 处理成功后标记 |
| `failed` | 处理失败 | Cron 处理异常时标记（可被下轮 cron 重试） |
| `skipped` | 跳过（parent chunk 不需此链路） | AdminRag 写入 parent chunk 时设置 |

> 两条链路独立运作，互不阻塞。两条都 `success`（或 `skipped`）即 chunk 就绪。

### 5.1.4 Cron 异步处理流程（Dense + Sparse 独立 Cron）

```
@Scheduled(cron = "*/10 * * * * *")  -- Dense Cron: 每10秒扫一次

1. SELECT * FROM chunk WHERE dense_status IN ('init', 'failed')
   AND parent_chunk_id IS NOT NULL  -- 仅处理 child chunk
   LIMIT 100
2. FOR EACH child chunk:
     UPDATE dense_status = 'processing'
     TRY:
       vector = embeddingModel.embed(chunk.content)
       INSERT INTO chunk_embedding (chunk_id, embedding) VALUES (?, ?)
       UPDATE dense_status = 'success'
     CATCH:
       UPDATE dense_status = 'failed'

---

@Scheduled(cron = "*/10 * * * * *")  -- Sparse Cron: 每10秒扫一次

1. SELECT * FROM chunk WHERE sparse_status IN ('init', 'failed')
   AND parent_chunk_id IS NOT NULL  -- 仅处理 child chunk
   LIMIT 100
2. FOR EACH child chunk:
     UPDATE sparse_status = 'processing'
     TRY:
       fts = to_tsvector('chinese', chunk.content)
       INSERT INTO chunk_fts (chunk_id, fts_content) VALUES (?, ?)
       UPDATE sparse_status = 'success'
     CATCH:
       UPDATE sparse_status = 'failed'
```

- Dense 和 Sparse 各自独立 Cron，互不阻塞
- 每次只取 100 条，防止一次扫描量过大
- `failed` 状态的 chunk 会被重新处理，实现自动重试
- 频率 10s 为 Demo 默认值，生产建议通过配置文件调整

### 5.1.5 双写目标

**仅 child chunk 参与双写**（parent chunk 不做向量化）：

每个 **child chunk** embedding 完成后同时写入：
- **pgvector 表**：`chunk_id, embedding (vector(768))`，Dense 稠密向量检索
- **PostgreSQL FTS**：chunk 表的 `fts_content tsvector` 列 + GIN 索引，Sparse BM25 检索

> Parent chunk 不存储 embedding，不参与 FTS 索引，仅保留纯文本用于回表获取上下文。

### 5.2 混合检索流程（UserQuery）

```
用户问题
   │
   ▼
┌──────────────────┐
│  Embedding 向量化  │  ← 问题 → vector
└────────┬─────────┘
         │
         ├──────────────────────┐
         ▼                      ▼
┌─────────────────┐  ┌──────────────────────┐
│  Dense 查询      │  │  Sparse 查询          │
│  (pgvector)      │  │  (PostgreSQL FTS)     │
│  cosine/ip 距离  │  │  ts_rank / BM25 分数  │
│  → Top-K child  │  │  → Top-K child       │
└────────┬────────┘  └──────────┬───────────┘
         │                      │
         │   两路均在 child       │
         │   chunk 维度检索      │
         │                      │
         └──────────┬───────────┘
                    ▼
         ┌──────────────────┐
         │  RRF 融合         │  ← Reciprocal Rank Fusion
         │  合并 + 重排序     │
         └────────┬─────────┘
                  ▼
         ┌──────────────────┐
         │  回表 PostgreSQL   │  ← 按 chunk_id 回表查完整内容
         └────────┬─────────┘
                  ▼
         ┌──────────────────┐
         │  Rerank 重排序     │  ← 基于语义相关度再次排序
         └────────┬─────────┘
                  ▼
         ┌──────────────────┐
         │  LLM 生成回答      │  ← Top-N chunks → prompt → LLM
         └──────────────────┘
```

### 5.3 RRF（Reciprocal Rank Fusion）

```
RRF_score(d) = Σ 1 / (k + rank_i(d))

- k: 常数（通常 60），防止单路排名过高导致分母过小
- rank_i(d): 文档 d 在第 i 路检索结果中的排名
```

- 两路各自返回 Top-K **child chunk**（如 K=20）
- RRF 计算每个 **child chunk** 的融合分数
- 按 RRF 分数降序排列，取 Top-N（如 N=10）
- 回表 PostgreSQL：通过 `parent_chunk_id` 取这 N 个 child chunk 对应的 parent chunk 完整内容，作为后续 rerank 和 LLM 的输入

### 5.4 Core 模块职责

| 模块 | 入库职责 | 检索职责 |
|------|----------|----------|
| chunk | 文本 → child chunk + parent chunk（TokenTextSplitter） | — |
| embedding | Cron 扫表 → **仅 child chunk**.embed() → vector | question → vector |
| sparseQuery | 写入 FTS 索引（tsvector + GIN）→ **仅 child chunk** | BM25 关键词检索 → **child chunk 维度** |
| denseQuery | 写入 pgvector（embedding vector）→ **仅 child chunk** | 向量相似度检索 → **child chunk 维度** |
| rrf | — | RRF 融合 child chunk 结果 + 回表取 parent chunk |
| rerank | — | 对 parent chunk 完整内容语义重排序 |

> **回表策略**：Dense + Sparse 检索均在 child chunk 维度执行，返回 child chunk ID → RRF 融合 → 回表时通过 `parent_chunk_id` 取 parent chunk 完整内容，作为 LLM 上下文。

---

## 六、中间件清单（Docker Compose 编排）

| 服务 | 镜像 | 端口 | 说明 |
|------|------|------|------|
| PostgreSQL + pgvector | `pgvector/pgvector:pg17` | 5432 | 向量数据库 |
| Model Service（Sidecar） | 自建 Dockerfile（Python 3.12 + FastAPI） | 8001 | `/embed`（text2vec-base-chinese，768 维）+ `/rerank`（bge-reranker-v2-m3） |
| Spring Boot 应用 | 自建 Dockerfile（Java 21 + Spring Boot） | 8080 | 主服务 |

> Sidecar 模型在 Docker build-time 下载并烤进镜像，实现真正的"一键部署"。详见 [plan_2.1](./plan_2.1.md)。

---

## 七、执行计划索引

| Plan | 内容 | 状态 |
|------|------|------|
| [plan_1](./plan_1.md) | 项目脚手架 + 基础设施 + 分包结构 + DAO + Dockerfile | ✅ 全部完成（2026-06-10） |
| [plan_1.hotfix_1](./plan_1.hotfix_1.md) | 计划命名约束修正（禁止新增小数 plan，统一 hotfix 规则） | ✅ 完成（2026-06-12） |
| [plan_2](./plan_2.md) | AdminRag 写入链路 + Cron Dense 异步处理 | ⏳ 待开始 |
| [plan_3](./plan_3.md) | 项目介绍文档 + 架构 SVG + README 插图 | ✅ 完成（2026-06-12） |
| [plan_3.hotfix_1](./plan_3.hotfix_1.md) | 代码风格约束文档抽取 + Agent 路由 | ✅ 完成（2026-06-12） |
| [plan_3.hotfix_2](./plan_3.hotfix_2.md) | 约束文档目录收敛 + 包结构抽取 | ✅ 完成（2026-06-12） |
| [plan_3.hotfix_3](./plan_3.hotfix_3.md) | Docker 部署结构抽取 + Agent 路由 | ✅ 完成（2026-06-13） |
| [plan_3.hotfix_4](./plan_3.hotfix_4.md) | Plan 工作流约束抽取 + Agent 路由 | ✅ 完成（2026-06-13） |
| plan_4 | RRF 融合 + Rerank + UserQuery 查询链路 + 全链路联调 | ⏳ 占位 |

### Plan 命名与任务编号规范

Plan 工作流、命名、任务编号和进度追踪约束统一维护在 [constraints/plan-workflow.md](../constraints/plan-workflow.md)。

### Plan_1 范围（基础设施）

plan_1 完成项目从零到可编译运行的基础骨架：
- 1.x 脚手架：Gradle + Spring Boot 3.x + application.yml
- 2.x 分包：controller / service / core / dao / integration 全包结构
- 3.x DAO 层：Spring Data JPA + Chunk Entity + schema.sql
- 4.x Docker：Dockerfile（多阶段构建）+ docker-compose.yml

> plan_1 完成后，项目可编译、启动、连接 PostgreSQL，chunk 表就绪。Core 业务逻辑和 API 实现留到 plan_2+。

### 历史前置计划说明

历史遗留计划文件与后续 hotfix 归属规则统一维护在 [constraints/plan-workflow.md](../constraints/plan-workflow.md)。

### Plan_2 范围

- 2.x AdminRag 写入链路：ChunkSplitService 分块 + AdminRagService 编排 + Controller 接线
- 2.x Cron Dense 异步处理：EmbeddingClient（HTTP 调用 Sidecar）+ DenseEmbeddingService（幂等状态机 + 写 chunk_embedding）

### Plan_3 范围

- 3.x 文档资产：项目介绍文档骨架 + 全链路架构 SVG + README 插图

### Plan_4 范围（占位）

- 4.x Core 全链路：SparseQuery + DenseQuery + RRF 融合 + Rerank + LLM 生成
- 4.x UserQuery 查询链路 + 全链路联调验证

---

## 八、待决策事项

- [x] 检索方案：BM25 Sparse（PostgreSQL FTS） + Dense（pgvector） → RRF 融合 ✅ 已确认
- [x] Embedding 模型方案：text2vec-large-chinese ✅ 已确认
- [x] Embedding 服务化方案：Sidecar Python 容器（FastAPI + sentence-transformers） ✅ 已确认
- [x] LLM 方案：一期 DeepSeek API + Spring AI 管理，integration 层预留多提供商兼容 ✅ 已确认
- [x] 文档解析：一期纯文本直传（HTTP body 塞完整文本），不做文件解析 ✅ 已确认
- [x] Chunk 策略：child chunk（256 token 细粒度检索） + parent chunk（1024 token 大窗口上下文），工具暂定 Spring AI TokenTextSplitter ✅ 已确认
- [x] 数据库迁移：一期不做，DDL 直接手动管理 / Spring 启动初始化 ✅ 已确认
- [x] 检索粒度：Sparse 和 Dense 检索均在 child chunk 维度进行 ✅ 已确认
- [x] Embedding 范围：仅 child chunk 存储向量，parent chunk 不参与向量化和 FTS 索引 ✅ 已确认

---

## 九、代码规范

所有 Java 代码风格、注释规范和设计约束统一维护在 [constraints/code-style.md](../constraints/code-style.md)。

当前必须遵守的重点约束包括：

- 禁止出现 `import *` 或任何通配符导入。
- 优先使用 `@Autowired` 字段注入，不优先使用构造器注入。
- 保持 class Javadoc、重要 method Javadoc、成员变量注释和必要行注释。
- 遵循奥卡姆剃刀与第一性原理，避免 Demo 阶段过度抽象。

## 十、包结构索引

Java 包结构索引统一维护在 [constraints/package-structure.md](../constraints/package-structure.md)。

## 十一、Docker 部署结构

Docker 部署结构索引统一维护在 [constraints/docker-structure.md](../constraints/docker-structure.md)。
