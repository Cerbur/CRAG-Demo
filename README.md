# CRAG-Demo

开箱即用的 RAG 全链路学习项目。Clone → `docker compose up` → 5 分钟看懂检索增强生成的每一步。

![Java](https://img.shields.io/badge/Java-21-red?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6db33f?logo=springboot)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17%20%2B%20pgvector-4169e1?logo=postgresql)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ed?logo=docker)
![License](https://img.shields.io/badge/License-MIT-green)

## ⚡ 5 分钟快速开始

### 前置条件

- Docker Desktop 或 Docker Engine
- Git

### 一键启动

```bash
git clone <repo-url> && cd CRAG-Demo
docker compose up -d --build
```

等待健康检查通过（约 90 秒，首次需下载模型约 2 分钟）。

> 💡 Compose 启动五个 Java 服务（console-api、open-api、access-service、knowledge-service、rag-service）、PostgreSQL、Redis 和 Sidecar。`rag-service`（8082）承载 RAG 运行时与 gRPC，默认不暴露业务 HTTP；RAG 写入与问答的 HTTP 验证入口统一在 `smoke` Profile 下的 `rag-service-smoke`（8083），使用 `/api/v1/smoke/**` 前缀。

### 写入知识 + 发起问答

默认 `docker compose up` 的 `rag-service`（8082）只暴露健康检查与 gRPC；RAG 写入与问答的 HTTP 验证入口在 `smoke` Profile 下。先启动 smoke 实例再调用 `/api/v1/smoke/**`：

```bash
# 启动 RAG smoke 验证实例（端口 8083，承载 /api/v1/smoke/** 验证端点）
docker compose --profile smoke up -d --build rag-service-smoke

# 1. 写入一篇中文知识
curl -X POST http://localhost:8083/api/v1/smoke/admin/rag \
  -H "Content-Type: application/json" \
  -d '{"title":"RAG 介绍","content":"RAG（检索增强生成）是一种结合信息检索与文本生成的技术架构。它先从知识库中检索相关文档片段，再将这些片段作为上下文交给大语言模型生成答案，从而有效减少幻觉、提升回答的事实准确性。"}'

# 2. 等待索引构建完成（约 10 秒）
sleep 10

# 3. 发起问答
curl -X POST http://localhost:8083/api/v1/smoke/query \
  -H "Content-Type: application/json" \
  -d '{"question":"什么是 RAG？"}'
```

> 💡 默认使用 **Stub 模式**，无需任何 API Key 即可跑通全链路。想接入真实 LLM？设置 `CRAG_QUERY_LLM_PROVIDER=deepseek` 并配置相关环境变量即可（详见 `.env.example`）。

### 服务端口

| 服务 | HTTP 端口 | 说明 |
| --- | --- | --- |
| `console-api` | 8080 | Console HTTP 入口 |
| `open-api` | 8081 | Open HTTP 入口 |
| `rag-service` | 8082 | RAG 运行时（健康检查 + gRPC；默认不暴露业务 HTTP） |
| `rag-service-smoke` | 8083 | RAG smoke 验证（`/api/v1/smoke/**`，需 smoke Profile） |

### Smoke 诊断模式

Smoke 模式提供分阶段诊断端点，需显式 Profile 激活：

```bash
# 启动 Smoke（端口 8083）
docker compose --profile smoke up -d --build rag-service-smoke

# 验证全链路
curl http://localhost:8083/api/v1/smoke/test/smoke
```

## 🗺️ 平台架构

![CRAG-Demo 多租户知识平台架构](./docs/assets/crag-demo-architecture.svg)

> 上图反映当前平台方向：五个 Java 进程、gRPC 服务通信、PostgreSQL 独立 Schema、Redis Streams 可靠事件基础设施，以及仍作为核心的 RAG 主链路。实线表示已落地基础能力，虚线表示后续 router 阶段继续实现的业务链路。

## 🔢 RAG 管道：7 步走通检索增强生成

### 写入链路

**① 文档入库** `POST /api/v1/smoke/admin/rag` → `crag-rag-service`

你上传的文本存入 `document` 表，状态标记 `PENDING_CHUNK`。文档与 Chunk 的业务 ID 由 `crag-id` 以 Snowflake `BIGINT` 分配（HTTP 边界以 decimal string 输出，避免前端精度损失）。
👉 代码入口：`crag-rag-service` Controller → `crag-rag-service` AdminRagService

**② 文档分块** Cron: `DocChunkSplitListener`

定时扫描待处理的文档，按句子边界切分为 Chunk，写入 `chunk` 表。
👉 代码入口：`crag-rag-service` / Cron / DocChunkSplitListener

**③ 索引构建** Cron: `SparseIndexListener` + `DenseIndexListener`

Chunk 变更后，分别构建：
- **Dense 向量索引**：调用 Python Sidecar `/embed`（gte 中文模型）→ 写入 pgvector
- **Sparse 全文索引**：分词后写入 `sparse_index` 表
👉 代码入口：`crag-rag-service` / Cron，Sidecar 交互见 `crag-rag-service` EmbeddingClient

### 查询链路

**④ 双路召回** `POST /api/v1/smoke/query` → `crag-rag-service`

用户问题同时走两路：
- **Dense 语义召回**：问题向量化 → pgvector 余弦相似度 Top-K
- **Sparse 关键词召回**：分词 → 全文检索 Top-K
👉 代码入口：`crag-rag-service` / SparseRetrievalService、DenseRetrievalService

**⑤ RRF 融合** `crag-rag-service` / RRF

Reciprocal Rank Fusion 合并双路结果，去重后重排。
👉 代码入口：`crag-rag-service` / RrfService

**⑥ Rerank 重排序** `crag-rag-service` → Sidecar `/rerank`

用 bge-reranker-v2-m3 模型对候选 Chunk 精排，取 Top-N。
👉 代码入口：`crag-rag-service` / RerankClient

**⑦ LLM 生成** `crag-rag-service` → LLM

精排后的 Chunk 作为 Context 拼入 Prompt，调用 LLM 生成最终答案 + 来源引用。
👉 代码入口：`crag-rag-service` / QueryService → LLM Client

### 一张图总结

| 步骤 | 阶段 | 模块 | 关键动作 |
|------|------|------|----------|
| ① | 写入 | ingestion | 文档入库 |
| ② | 写入 | ingestion | 分块（Cron） |
| ③ | 写入 | ingestion + retrieval | Dense / Sparse 索引 |
| ④ | 查询 | retrieval | 双路召回 |
| ⑤ | 查询 | retrieval | RRF 融合 |
| ⑥ | 查询 | retrieval | Rerank 重排 |
| ⑦ | 查询 | query | LLM 生成答案 |

## 📦 项目结构

```
├── crag-common/              # 跨模块共享：统一响应结构、基础异常类型
├── crag-id/                  # 分布式 Snowflake ID 基础设施：发号、Redis Worker 租约、时钟回拨处理
├── crag-platform-contracts/  # 跨领域通用 Protobuf 基础契约（Platform Probe）
├── crag-knowledge-contracts/ # Knowledge 领域 Protobuf/gRPC 契约（KnowledgeBase、Document）
├── crag-grpc-runtime/        # 协议无关 gRPC Server/Client 生命周期、认证、Health
├── crag-event/               # 领域无关可靠事件基础设施：Outbox、processed_event、Redis Streams、ACK/Reclaim/DLQ
├── crag-rag-service/         # RAG 业务组合根：storage/retrieval/query/ingestion 内部包 + smoke 验证 HTTP + gRPC Server
├── crag-access-service/      # Access 组合根、gRPC Server、Schema readiness
├── crag-knowledge-service/   # Knowledge 组合根：KnowledgeBase/Document/文件上传/DOC_UPLOADED + gRPC Server + smoke 验证 HTTP
├── crag-console-api/         # Console HTTP 入口、下游 Probe readiness
├── crag-open-api/            # Open HTTP 入口、下游 Probe readiness
├── sidecar/                  # Python 模型 Sidecar：/embed（gte）、/rerank（bge-reranker）
├── plan/                     # 项目规划文档与设计决策
├── constraints/              # 编码规范与架构约束
└── scripts/                  # 运维与验收脚本
```

> 💡 **学习路径**：建议从 `crag-rag-service` 的 smoke Controller（HTTP 验证入口）→ query 包（核心编排）→ retrieval 包（检索细节）→ ingestion 包（写入链路）的顺序阅读代码。

## 🛠️ 技术栈

| 层次 | 技术 | 说明 |
|------|------|------|
| 语言 | Java 21 | 虚拟线程、Record、密封类 |
| 框架 | Spring Boot 4.1 | Spring Framework 7 + Spring AI 2.0 |
| 构建 | Gradle (Kotlin DSL) | 多模块项目统一管理 |
| ORM | Spring Data JPA | 基于 Hibernate 的声明式持久层 |
| 向量库 | PostgreSQL 17 + pgvector | 向量存储、余弦相似度检索 |
| 协调 | Redis 7.4 | Snowflake ID Worker 租约 + Redis Streams 可靠事件传输（传输层，非业务事实来源） |
| 嵌入模型 | gte (GTE Chinese) | 中文句向量，Python Sidecar 托管 |
| 重排模型 | bge-reranker-v2-m3 | 跨语言重排序，Python Sidecar 托管 |
| LLM | Stub / DeepSeek | Stub 免 Key 调试；DeepSeek 生产可用 |
| 容器 | Docker + Compose | 五进程拓扑一键编排 |

## 📄 许可证

MIT License — 详见 [LICENSE](./LICENSE)

## ⚠️ 安全提示

DEBUG 日志会记录用户问题与模型回答。**禁止在生产环境开启。** 默认 `INFO` 级别不记录任何问答内容。Context、Prompt、认证信息在任何日志级别下均不记录。
