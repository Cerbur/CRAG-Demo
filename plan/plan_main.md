# CRAG-Demo 总体规划（plan_main）

> 创建时间：2026-06-10
> 最后更新：2026-06-10

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
| Embedding 模型 | text2vec-large-chinese | Sidecar Python /embed（bi-encoder） |
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
│  - chunk        → 文档分块策略（入库时）           │
│  - embedding    → 向量化（调用 embedding 模型）     │
│  - sparseQuery  → BM25 稀疏查询（PostgreSQL FTS）  │
│  - denseQuery   → Dense 稠密查询（pgvector 向量）   │
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

- **Child Chunk**：小粒度的检索单元，用于与 query 做相似度匹配
- **Parent Chunk**：大粒度的上下文窗口，检索命中 child 后回表取 parent 获得更完整上下文
- 入库时两种 chunk 同时写入 chunk 表，`parent_chunk_id` 建立关联

### 5.1.2 Chunk 表结构

```sql
CREATE TABLE chunk (
    chunk_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id           UUID NOT NULL,              -- 关联文档
    parent_chunk_id  UUID,                        -- NULL=parent, 非NULL=child（指向parent）
    content          TEXT NOT NULL,               -- chunk 文本内容
    token_count      INTEGER,                     -- token 数量
    metadata         JSONB DEFAULT '{}',          -- 扩展元数据 {tags, ...}
    status           VARCHAR(16) DEFAULT 'init',  -- init / processing / failed / success
    created_at       TIMESTAMP DEFAULT NOW(),
    updated_at       TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_chunk_status ON chunk(status);
CREATE INDEX idx_chunk_doc_id ON chunk(doc_id);
CREATE INDEX idx_chunk_parent ON chunk(parent_chunk_id);
```

### 5.1.3 Chunk 状态机

```
  init ──→ processing ──→ success
   │            │
   │            └──────→ failed（标记失败，cron 可重试）
   │
   └──（cron 跳过 init，等待下次调度）
```

| 状态 | 含义 | 触发 |
|------|------|------|
| `init` | 刚分块完成，等待 embedding | AdminRag 写入时设置 |
| `processing` | 正在 embedding + 写向量库 | Cron 扫到 init/failed 时标记 |
| `success` | embedding + 双写完成 | Cron 处理成功后标记 |
| `failed` | embedding 或写入失败 | Cron 处理异常时标记（可被下轮 cron 重试） |

### 5.1.4 Cron 异步 Embedding 流程

```
@Scheduled(cron = "*/10 * * * * *")  -- Demo: 每10秒扫一次

1. SELECT * FROM chunk WHERE status IN ('init', 'failed') LIMIT 100
2. FOR EACH chunk:
     UPDATE status = 'processing'
     TRY:
       vector = embeddingModel.embed(chunk.content)
       INSERT INTO pgvector (chunk_id, embedding) VALUES (?, ?)
       UPDATE fts_column = to_tsvector('chinese', chunk.content)
       UPDATE status = 'success'
     CATCH:
       UPDATE status = 'failed'
```

- 每次只取 100 条，防止一次扫描量过大
- `failed` 状态的 chunk 会被重新处理，实现自动重试
- 频率 10s 为 Demo 默认值，生产建议通过配置文件调整

### 5.1.5 双写目标

每个 chunk embedding 完成后同时写入：
- **pgvector 表**：`chunk_id, embedding (vector(1024))`，Dense 稠密向量检索
- **PostgreSQL FTS**：chunk 表的 `fts_content tsvector` 列 + GIN 索引，Sparse BM25 检索

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
│  → Top-K        │  │  → Top-K             │
└────────┬────────┘  └──────────┬───────────┘
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

- 两路各自返回 Top-K（如 K=20）
- RRF 计算每个 chunk 的融合分数
- 按 RRF 分数降序排列，取 Top-N（如 N=10）
- 回表 PostgreSQL 获取这 N 个 chunk 的完整内容

### 5.4 Core 模块职责

| 模块 | 入库职责 | 检索职责 |
|------|----------|----------|
| chunk | 文本 → child chunk + parent chunk（TokenTextSplitter） | — |
| embedding | Cron 扫表 → chunk.embed() → vector | question → vector |
| sparseQuery | 写入 FTS 索引（tsvector + GIN） | BM25 关键词检索 |
| denseQuery | 写入 pgvector（embedding vector） | 向量相似度检索 |
| rrf | — | RRF 融合 + 回表取 parent chunk |
| rerank | — | 对融合结果语义重排序 |

> **回表策略**：Dense + Sparse 检索返回 child chunk ID → RRF 融合 → 回表时通过 `parent_chunk_id` 取 parent chunk 完整内容，作为 LLM 上下文。

---

## 六、中间件清单（Docker Compose 编排）

| 服务 | 镜像 | 端口 | 说明 |
|------|------|------|------|
| PostgreSQL + pgvector | `pgvector/pgvector:pg17` | 5432 | 向量数据库 |
| Model Service（Sidecar） | Python FastAPI | 8001 | /embed（text2vec）+ /rerank（bge-reranker） |
| Spring Boot 应用 | 自建 Dockerfile | 8080 | 主服务 |

> 注：Embedding 服务化方案已确认（Sidecar Python），见 [八、待决策事项](#八待决策事项)。

---

## 七、执行计划索引

| Plan | 内容 | 状态 |
|------|------|------|
| [plan_1](./plan_1.md) | 项目脚手架 + 基础设施 + Core 全链路 | 🔜 待创建 |
| plan_2 | Integration 层（LLM + Embedding + Rerank） | ⏳ 占位 |
| plan_3 | Docker Compose 编排 + Sidecar Python + 联调 | ⏳ 占位 |

### Plan 命名与任务编号规范

- Plan 文件使用数字编号：`plan_1.md`, `plan_2.md`, `plan_3.md` ...
- 每个 plan 内的小任务使用 `plan-id.task-id` 编号，如 `1.1`, `1.2`, `2.1`, `2.2`
- 每完成一个小任务，更新任务状态并记录对应 commit

### Plan_1 范围（最小 Demo）

plan_1 完成项目从零到可运行的 Core 全链路：
- 1.x 脚手架：Gradle + Spring Boot + PostgreSQL 配置 + schema.sql
- 2.x Core — chunk：child/parent chunk 分块（TokenTextSplitter）
- 3.x Core — sparseQuery：PostgreSQL FTS BM25 查询
- 4.x Core — denseQuery：pgvector 向量查询
- 5.x Core — rrf：RRF 融合 + 回表
- 6.x Core — rerank：重排序
- 7.x Integration 骨架：EmbeddingClient / RerankClient 接口 + Sidecar HTTP 实现
- 8.x API：UserQuery + AdminRag controller → service → core 串联

> plan_1 完成后，`POST /api/v1/query` 可返回检索到的 Top-N chunks（不含 LLM 生成），`POST /api/v1/admin/rag` 可完成文档入库全链路。LLM 生成留到 plan_2。

### Plan_2 范围
- 2.x Integration — llm：ChatClient 接口 + DeepSeek ChatClient 实现 + 提示词管理
- 2.x API — UserQuery 接入 LLM 生成最终回答

### Plan_3 范围
- 3.x Docker Compose：PostgreSQL + Sidecar Python + Spring Boot 一键启动
- 3.x 联调验证

---

## 八、待决策事项

- [x] 检索方案：BM25 Sparse（PostgreSQL FTS） + Dense（pgvector） → RRF 融合 ✅ 已确认
- [x] Embedding 模型方案：text2vec-large-chinese ✅ 已确认
- [x] Embedding 服务化方案：Sidecar Python 容器（FastAPI + sentence-transformers） ✅ 已确认
- [x] LLM 方案：一期 DeepSeek API + Spring AI 管理，integration 层预留多提供商兼容 ✅ 已确认
- [x] 文档解析：一期纯文本直传（HTTP body 塞完整文本），不做文件解析 ✅ 已确认
- [x] Chunk 策略：child chunk（256 token 细粒度检索） + parent chunk（1024 token 大窗口上下文），工具暂定 Spring AI TokenTextSplitter ✅ 已确认
- [x] 数据库迁移：一期不做，DDL 直接手动管理 / Spring 启动初始化 ✅ 已确认
