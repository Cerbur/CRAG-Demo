# CRAG-Demo 包结构约束

> 本文档是 CRAG-Demo Java 包结构索引的唯一维护入口。`AGENTS.md`、`CLAUDE.md` 和计划文档只保留到本文档的路由。

---

## 一、包结构索引

```text
com.crag.demo
├── CragDemoApplication              — Spring Boot 启动类
├── controller/                       — API 入口层
│   ├── UserQueryController          — 用户查询接口
│   ├── AdminRagController           — 管理端 RAG 知识库上传接口
│   └── advice/                       — 全局异常处理（AOP 层）
│       └── GlobalExceptionHandler   — 统一异常 → Response 转换
├── dto/                               — 数据传输对象（与 controller/service 同级）
│   ├── request/                       — 请求 DTO（入参结构）
│   │   ├── AdminRagRequest          — AdminRag 上传请求
│   │   └── UserQueryRequest         — 用户查询请求
│   └── result/                        — 统一响应封装
│       ├── Response                 — RESTful 统一响应泛型包装类
│       └── ResponseCode             — 统一响应码枚举
├── service/                          — 业务服务层
│   ├── UserQueryService             — 用户查询服务
│   ├── AdminRagService              — 管理端 RAG 服务
│   └── AdminRagResult               — AdminRag 入库结果记录
├── cron/                             — 定时任务触发层（编排层，核心逻辑在 service/core）
│   ├── DenseEmbeddingCron           — Dense Embedding 定时扫表 + CAS 抢占 + 流程编排
│   └── SparseEmbeddingCron          — Sparse Embedding 定时扫表 + CAS 抢占 + FTS 写入
├── core/                             — RAG 核心逻辑层
│   ├── chunk/                        — 文档分块领域
│   │   └── split/                     — 文档切分（ChunkSplit）
│   ├── dense/                        — Dense 检索通道（Embedding + Query）
│   ├── sparse/                       — Sparse 检索通道（BM25/FTS）
│   ├── rrf/                          — RRF 融合
│   └── rerank/                       — 重排序
├── dao/                              — 数据访问层（pgvector 向量数据库操作）
│   ├── entity/                       — JPA 实体
│   │   ├── Chunk / ChunkEmbedding / ChunkFts
│   │   ├── ChunkStatus              — 异步处理状态枚举
│   │   └── ChunkStatusConverter     — JPA AttributeConverter
│   ├── repository/                   — Spring Data JPA Repository（纯 DB 类型映射）
│   │   ├── ChunkRepository          — chunk 表 CAS 查询 + 更新
│   │   ├── ChunkEmbeddingRepository  — chunk_embedding 表基础 CRUD + existsByChunkId + native INSERT
│   │   └── ChunkFtsRepository       — chunk_fts 表
│   ├── ChunkDao                      — chunk 表业务数据访问（扫表 + CAS 抢占 + saveAll/count），供 Cron/Service 调用
│   ├── ChunkEmbeddingDao            — chunk_embedding 表业务数据访问（幂等检查 + pgvector 格式转换 + count），供 Cron 调用
│   └── ChunkFtsDao                   — chunk_fts 表业务数据访问（幂等检查 + FTS 记录写入 + count），供 Cron 调用
└── integration/                      — 外部服务接入层
    ├── llm/                          — LLM 调用（Spring AI，一期 DeepSeek）
    │   └── prompt/                   — 提示词模板管理
    ├── dense/                        — Dense Embedding 调用（一期 Sidecar /embed）
    └── rerank/                       — Rerank 调用（一期 Sidecar /rerank）
```

---

## 二、维护规则

- 新增、移动或重命名 Java 包时，必须同步更新本文档。
- `AGENTS.md` 与 `CLAUDE.md` 不直接展开包结构树，只链接到本文档。
- 包结构变更如果会影响计划范围，必须同步更新对应 `plan_N.md` 或 `plan_N.hotfix_M.md`。
