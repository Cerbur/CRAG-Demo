# CRAG-Demo 总体规划（plan_main）

> 创建时间：2026-06-10
> 最后更新：2026-06-10（child chunk 检索粒度 + embedding 范围明确 + chunk_index + 代码规范）

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
    status           VARCHAR(16) DEFAULT 'init',  -- init / processing / failed / success
    created_at       TIMESTAMP DEFAULT NOW(),
    updated_at       TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_chunk_status ON chunk(status);
CREATE INDEX idx_chunk_doc_id ON chunk(doc_id);
CREATE INDEX idx_chunk_parent ON chunk(parent_chunk_id);
```

> **chunk_index 说明**：`chunk_index` 记录 child chunk 在其 parent chunk 内的顺序位置（从 0 开始递增）。用于回表时保持原文片段顺序、相邻 chunk 扩展上下文等场景。Parent chunk 自身 `chunk_index = NULL`。
>
> **Status 语义说明**：`status` 字段仅对 child chunk 有意义（控制 embedding + 双写流程）。Parent chunk 入库时 `status` 可直接设为 `'success'`（无需处理），或新增 `'parent'` 状态以区分。Cron 扫表通过 `parent_chunk_id IS NOT NULL` 确保只处理 child chunk。

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

1. SELECT * FROM chunk WHERE status IN ('init', 'failed')
   AND parent_chunk_id IS NOT NULL  -- 仅处理 child chunk
   LIMIT 100
2. FOR EACH child chunk:
     UPDATE status = 'processing'
     TRY:
       vector = embeddingModel.embed(chunk.content)
       INSERT INTO pgvector (chunk_id, embedding) VALUES (?, ?)
       UPDATE fts_content = to_tsvector('chinese', chunk.content)
       UPDATE status = 'success'
     CATCH:
       UPDATE status = 'failed'
```

- 每次只取 100 条，防止一次扫描量过大
- `failed` 状态的 chunk 会被重新处理，实现自动重试
- 频率 10s 为 Demo 默认值，生产建议通过配置文件调整

### 5.1.5 双写目标

**仅 child chunk 参与双写**（parent chunk 不做向量化）：

每个 **child chunk** embedding 完成后同时写入：
- **pgvector 表**：`chunk_id, embedding (vector(1024))`，Dense 稠密向量检索
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
| Model Service（Sidecar） | Python FastAPI | 8001 | /embed（text2vec）+ /rerank（bge-reranker） |
| Spring Boot 应用 | 自建 Dockerfile | 8080 | 主服务 |

> 注：Embedding 服务化方案已确认（Sidecar Python），见 [八、待决策事项](#八待决策事项)。

---

## 七、执行计划索引

| Plan | 内容 | 状态 |
|------|------|------|
| [plan_1](./plan_1.md) | 项目脚手架 + 基础设施 + 分包结构 + DAO + Dockerfile | 📋 已创建（2026-06-10） |
| plan_2 | Integration 层（LLM + Embedding + Rerank） | ⏳ 占位 |
| plan_3 | Docker Compose 编排 + Sidecar Python + 联调 | ⏳ 占位 |

### Plan 命名与任务编号规范

- Plan 文件使用数字编号：`plan_1.md`, `plan_2.md`, `plan_3.md` ...
- 每个 plan 内的小任务使用 `plan-id.task-id` 编号，如 `1.1`, `1.2`, `2.1`, `2.2`
- 每完成一个小任务，更新任务状态并记录对应 commit

### Plan_1 范围（基础设施）

plan_1 完成项目从零到可编译运行的基础骨架：
- 1.x 脚手架：Gradle + Spring Boot 3.x + application.yml
- 2.x 分包：controller / service / core / dao / integration 全包结构
- 3.x DAO 层：Spring Data JPA + Chunk Entity + schema.sql
- 4.x Docker：Dockerfile（多阶段构建）+ docker-compose.yml

> plan_1 完成后，项目可编译、启动、连接 PostgreSQL，chunk 表就绪。Core 业务逻辑和 API 实现留到 plan_2+。

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
- [x] 检索粒度：Sparse 和 Dense 检索均在 child chunk 维度进行 ✅ 已确认
- [x] Embedding 范围：仅 child chunk 存储向量，parent chunk 不参与向量化和 FTS 索引 ✅ 已确认

---

## 九、代码规范

> 所有 Java 代码必须遵守以下注释与设计规范。规范本身遵循第一性原理：每一条都是交付可维护代码的**最小必要约束**。

### 9.1 注释规范

#### Class 级别

每个类文件头部必须包含 Javadoc，写明：

```java
/**
 * <一句话功能概述>.
 *
 * <详细说明，2-3 句，描述该类在整体架构中的角色>
 *
 * @since 2026-06-10
 */
```

要求：

- `@since` 标注创建日期（YYYY-MM-DD）
- 必须说清楚该类**对应哪个功能模块**（与分层架构对应）

#### Method 级别

**重要 method**（public / 核心业务逻辑 / 算法步骤）必须写 Javadoc：

```java
/**
 * <一句话描述该方法做什么>.
 *
 * @param xxx <参数含义>
 * @return <返回值含义>
 */
```

不要求为 getter/setter / 简单委托方法写注释。

#### 行注释

复杂逻辑（>10 行或含多重条件/循环/位运算）必须加行内注释：

```java
// Step 1: 两路检索并行发出，每路取 Top-K
// Step 2: RRF 按 1/(k+rank) 融合
```

注释写**为什么这么做**而不是复述代码。

#### 成员变量

所有成员变量（field）必须注释含义和作用：

```java
/**
 * child chunk 在 parent chunk 中的序号，从 0 开始递增.
 * parent chunk 自身此值为 NULL.
 */
private Integer chunkIndex;
```

### 9.2 设计原则

#### 奥卡姆剃刀 — 如无必要，勿增实体

- 不引入当前不需要的抽象层、接口、工具类
- Demo 阶段不做"万一以后要用"的预留
- 一个接口只有一个实现时，不做 Interface → Impl 分离；直接写实现类

#### 第一性原理 — 满足功能的最小逻辑

- 每段代码必须回答：**最少需要做什么？** 只做那件事
- 拒绝过度工程：无状态 → 不用缓存、单线程够用 → 不加锁、数据量小 → 不做分页
- Demo 阶段硬编码优于配置文件、同步优于异步、手动优于自动化

### 9.3 示例

```java
/**
 * 混合检索融合器 —— 对 Sparse + Dense 两路 child chunk 结果做 RRF 融合并回表.
 *
 * 融合后通过 parent_chunk_id 回表获取完整 parent 上下文，交给下游 rerank.
 *
 * @since 2026-06-10
 */
public class RrfFusionService {

    /**
     * RRF 常数 k，防止单路 rank=1 导致分母过小.
     * 业界常用值 60.
     */
    private static final int RRF_K = 60;

    private final ChunkDao chunkDao;

    /**
     * 对两路 child chunk 结果执行 RRF 融合，回表取 parent chunk 内容.
     *
     * @param sparseResults BM25 检索结果（child chunk 维度）
     * @param denseResults  pgvector 检索结果（child chunk 维度）
     * @param topN          融合后保留数量
     * @return parent chunk 完整内容列表，按 RRF 分数降序
     */
    public List<ChunkContent> fuse(List<SearchHit> sparseResults,
                                   List<SearchHit> denseResults,
                                   int topN) {
        // 1. 以 chunk_id 为 key 计算 RRF 分数
        Map<UUID, Double> scores = new HashMap<>();
        for (int rank = 0; rank < sparseResults.size(); rank++) {
            UUID id = sparseResults.get(rank).chunkId();
            scores.merge(id, 1.0 / (RRF_K + rank + 1), Double::sum);
        }
        for (int rank = 0; rank < denseResults.size(); rank++) {
            UUID id = denseResults.get(rank).chunkId();
            scores.merge(id, 1.0 / (RRF_K + rank + 1), Double::sum);
        }

        // 2. 按 RRF 分数降序取 Top-N child chunk ID
        List<UUID> topChildIds = scores.entrySet().stream()
            .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
            .limit(topN)
            .map(Map.Entry::getKey)
            .toList();

        // 3. 回表：取 child → parent chunk 完整内容
        return chunkDao.findParentContentsByChildIds(topChildIds);
    }
}
```

> 示例展示了规范的全貌：class Javadoc + @since、field 注释、method Javadoc、关键步骤行注释。同时体现了奥卡姆剃刀（无额外抽象）和第一性原理（最小逻辑）。
