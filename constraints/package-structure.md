# CRAG-Demo 包结构约束

> 本文档是 CRAG-Demo Java 包结构索引的唯一维护入口。`AGENTS.md`、`CLAUDE.md` 和计划文档只保留到本文档的路由。

---

## 一、项目模块结构

项目采用 Gradle multi-module 架构，base package 为 `ai.cerbur.crag`，`crag-app` 为唯一 Spring Boot 启动模块。

```text
crag-demo/
├── crag-common/       — 跨模块共享的基础类型、响应结构
├── crag-storage/       — 数据库 entity、repository、dao
├── crag-ingestion/     — AdminRag 写入链路、ChunkSplit、Cron 编排
├── crag-retrieval/     — Sparse/Dense 查询召回、RRF 融合、Embedding client
├── crag-query/         — UserQuery 编排、Rerank、LLM 调用
├── crag-admin/         — HTTP API service（Controller + 跨领域编排入口）
└── crag-app/           — Spring Boot 启动模块（唯一可启动 jar）
```

依赖方向：

```text
crag-app ──→ crag-admin, crag-ingestion, crag-retrieval, crag-query, crag-storage, crag-common
crag-admin ──→ crag-ingestion, crag-query, crag-common
crag-query ──→ crag-retrieval, crag-common
crag-ingestion ──→ crag-retrieval, crag-storage, crag-common
crag-retrieval ──→ crag-storage, crag-common
crag-storage ──→ crag-common
```

---

## 二、包结构索引

### crag-common（`ai.cerbur.crag.common`）

```text
ai.cerbur.crag.common/
└── dto/
    └── result/                       — 统一响应封装
        ├── Response                 — RESTful 统一响应泛型包装类
        └── ResponseCode             — 统一响应码枚举
```

### crag-storage（`ai.cerbur.crag.storage`）

```text
ai.cerbur.crag.storage/
├── entity/                           — JPA 实体
│   ├── Chunk / ChunkEmbedding / ChunkFts
│   ├── ChunkStatus                  — 异步处理状态枚举
│   └── ChunkStatusConverter         — JPA AttributeConverter
├── repository/                       — Spring Data JPA Repository（纯 DB 类型映射）
│   ├── ChunkRepository              — chunk 表 CAS 查询 + 更新
│   ├── ChunkEmbeddingRepository     — chunk_embedding 表基础 CRUD + native INSERT + 向量相似度查询
│   └── ChunkFtsRepository           — chunk_fts 表基础 CRUD + native INSERT + FTS 全文检索查询
├── ChunkDao                          — chunk 表业务数据访问（扫表 + CAS 抢占 + saveAll/count）
├── ChunkEmbeddingDao                — chunk_embedding 表业务数据访问（幂等检查 + pgvector 格式转换 + 向量相似度检索 + count）
├── ChunkFtsDao                       — chunk_fts 表业务数据访问（幂等检查 + FTS 记录写入 + FTS 全文检索 + count）
└── ChunkSearchResult                 — Sparse/Dense/RRF 统一检索结果类型
```

### crag-ingestion（`ai.cerbur.crag.ingestion`）

```text
ai.cerbur.crag.ingestion/
├── service/                          — 入库业务服务
│   ├── AdminRagService              — 管理端 RAG 服务（入库编排）
│   └── AdminRagResult               — AdminRag 入库结果记录
├── cron/                             — 定时任务触发层
│   ├── DenseEmbeddingCron           — Dense Embedding 定时扫表 + CAS 抢占 + 流程编排
│   └── SparseEmbeddingCron          — Sparse Embedding 定时扫表 + CAS 抢占 + FTS 写入
├── chunk/
│   └── split/                        — 文档切分
│       ├── ChunkSplitService        — 基于 TokenTextSplitter 的分块服务
│       ├── ChunkSplitData           — 单个 chunk 数据载体
│       ├── ChunkSplitGroup          — parent + children 分组
│       └── ChunkSplitResult         — 文档分块结果
└── dense/                            — Dense Embedding 向量化服务
    └── DenseEmbeddingService        — 调用 retrieval/embedding/EmbeddingClient 做核心向量化
```

### crag-retrieval（`ai.cerbur.crag.retrieval`）

```text
ai.cerbur.crag.retrieval/
├── dense/                            — Dense 稠密查询
│   └── DenseQueryService            — 基于 pgvector 向量相似度语义检索
├── sparse/                           — Sparse 稀疏查询
│   └── SparseQueryService           — 基于 PostgreSQL FTS 关键词检索
├── rrf/                              — RRF 融合
│   └── RrfFusionService             — Reciprocal Rank Fusion 两路融合
└── embedding/                        — Embedding HTTP 客户端
    ├── EmbeddingClient               — Embedding 接口定义
    ├── SidecarEmbeddingClient        — Sidecar /embed 端点实现
    └── EmbeddingException            — Embedding 调用异常
```

### crag-query（`ai.cerbur.crag.query`）

```text
ai.cerbur.crag.query/
├── service/                          — 用户查询服务
│   └── UserQueryService             — 用户查询应答编排
├── rerank/                           — 重排序
│   ├── RerankService                — 语义重排序服务
│   └── client/
│       └── RerankClient              — Rerank 接口定义（Sidecar /rerank）
└── llm/                              — LLM 调用
    └── ChatClient                    — LLM Chat 接口定义
```

### crag-admin（`ai.cerbur.crag.admin`）

```text
ai.cerbur.crag.admin/
├── controller/                       — API 入口层
│   ├── AdminRagController           — 管理端 RAG 知识库上传接口
│   ├── UserQueryController          — 用户查询接口
│   └── advice/                       — 全局异常处理（AOP 层）
│       └── GlobalExceptionHandler   — 统一异常 → Response 转换
└── dto/
    └── request/                       — 请求 DTO（入参结构）
        ├── AdminRagRequest           — AdminRag 上传请求
        └── UserQueryRequest          — 用户查询请求
```

### crag-app（`ai.cerbur.crag.app`）

```text
ai.cerbur.crag.app/
├── CragDemoApplication              — Spring Boot 启动类（唯一可启动 jar）
└── controller/
    └── TestController               — 冒烟测试接口
```

---

## 三、维护规则

- 新增、移动或重命名 Java 包时，必须同步更新本文档。
- `AGENTS.md` 与 `CLAUDE.md` 不直接展开包结构树，只链接到本文档。
- 包结构变更如果会影响计划范围，必须同步更新对应 `plan_N.md` 或 `plan_N.hotfix_M.md`。
- Module 边界变更必须同步更新 Gradle `settings.gradle.kts` 和各模块 `build.gradle.kts` 依赖关系。
