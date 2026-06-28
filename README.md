# CRAG-Demo

开箱即用的多租户 RAG 全链路学习项目。Clone → `docker compose up` → 从正式 API、可靠摄取到混合检索与答案引用，一套环境看懂完整知识平台。

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

Compose 会启动五个 Java 进程（Console API、Open API、Access、Knowledge、RAG）、PostgreSQL、Redis 和 Python Sidecar。首次启动需要下载嵌入与重排模型，请等待健康检查通过。

### 写入知识 + 发起问答

默认 Profile 提供正式 Console/Open API；下面的最短演示使用 RAG 原进程内的条件 Smoke Controller。启用 `smoke` Profile 后，仍使用同一个 `rag-service:8082`：

```bash
# 在原服务上启用 /api/v1/smoke/** 诊断端点
CRAG_SERVICE_PROFILES=smoke docker compose up -d --build rag-service

# 1. 写入一篇中文知识
curl -X POST http://localhost:8082/api/v1/smoke/admin/rag \
  -H "Content-Type: application/json" \
  -d '{"title":"RAG 介绍","content":"RAG（检索增强生成）先检索相关文档片段，再将片段作为上下文交给大语言模型生成答案，从而减少幻觉并提升事实准确性。"}'

# 2. 等待异步索引完成
sleep 10

# 3. 发起问答
curl -X POST http://localhost:8082/api/v1/smoke/query \
  -H "Content-Type: application/json" \
  -d '{"question":"什么是 RAG？"}'
```

默认使用确定性的 **LLM Stub**，无需 API Key。接入 DeepSeek 时设置 `CRAG_QUERY_LLM_PROVIDER=deepseek` 并配置 `.env.example` 中的环境变量。

### 服务端口

| 服务 | HTTP | gRPC | 职责 |
| --- | ---: | ---: | --- |
| `console-api` | 8080 | — | 浏览器管理 API：认证、租户、成员、知识库、文档、API Key |
| `open-api` | 8081 | — | 外部 API Key 问答入口 |
| `access-service` | 8091 | 9091 | 身份、会话、Membership、API Key 与 Scope |
| `knowledge-service` | 8092 | 9092 | KnowledgeBase、Document、文件与摄取状态 |
| `rag-service` | 8082 | 9093 | 摄取、混合检索、Rerank 与答案生成 |
| `sidecar` | 8001 | — | `/embed` 与 `/rerank` 模型服务 |

Access、Knowledge、RAG 的 HTTP 端口默认只暴露 Actuator；设置 `CRAG_SERVICE_PROFILES=smoke` 后，会在原进程、原端口上额外注册 `/api/v1/smoke/**`，不会创建重复容器。

## 📑 正式 API 契约

- [API 前端交接指南](./docs/api/README.md) — 登录态、Cookie、Tenant 上下文、分页、上传与轮询、重试、Scope 部分成功、一次性 API Key 和统一错误处理。
- [Console API](./docs/api/console-api.openapi.yaml) — `console-api:8080`。
- [Open API](./docs/api/open-api.openapi.yaml) — `open-api:8081`，通过单知识库 API Key 调用 `POST /api/v1/query`。

生成 TypeScript Fetch 客户端：

```bash
openapi-generator-cli generate \
  -i docs/api/console-api.openapi.yaml \
  -g typescript-fetch \
  -o ./frontend/console-client
```

OpenAPI 3.1 解析、`operationId`、`$ref`、示例、路由清单和文档链接校验已纳入 `./gradlew check`。

## 🗺️ 平台架构

![CRAG-Demo 多租户知识平台架构](./docs/assets/crag-demo-architecture.svg)

平台采用五进程边界：Console/Open 是无数据库的薄 HTTP 入口，只通过带服务身份的 gRPC 调用 Access、Knowledge 和 RAG；三个领域服务分别拥有 PostgreSQL Schema。Redis 同时承担 Snowflake Worker 租约和可靠事件传输，但不作为业务事实来源。

图中蓝色表示同步 HTTP/gRPC 调用，橙色表示 Redis Streams 事件，绿色表示数据所有权或模型调用。所有链路均为当前能力。

## ✨ 当前能力

### Console 管理面

- 注册、登录、刷新、登出和当前用户查询；RS256 Access JWT 进入响应体，Refresh Token 仅进入 HttpOnly Cookie。
- Tenant 与 OWNER/MEMBER Membership 管理，包含最后 OWNER 并发保护和跨租户不泄漏。
- KnowledgeBase 创建/查询，Document 上传、状态轮询和失败重试。
- API Key 创建、启停、轮换、吊销与 Scope 管理；完整 Key 只在创建或轮换时返回一次。

### Open 查询面

- `POST /api/v1/query` 使用单知识库 API Key 鉴权，返回 `answer + sources`。
- Key 指纹缓存默认 TTL 30 秒；Key/Scope 版本水位与 `API_KEY_INVALIDATED` 事件共同缩短失效窗口。
- HTTP 入口不直接访问数据库，只通过 Access 鉴权和 RAG Query gRPC 完成请求。

### 可靠摄取与多知识库 RAG

- Knowledge 在本地事务中保存 Document、文件元数据和 Outbox，再通过 Redis Streams 发布 `DOC_UPLOADED`。
- RAG 以 `(docId, operationVersion)` 作为摄取幂等键，维护 `ingestion_job` 与单调递增的 ingestion head。
- `chunk`、`chunk_embedding`、`chunk_fts` 和 Retrieval 同时受 `knowledgeBaseId + READY operationVersion` 约束，旧版本、失败版本和部分索引不会进入召回。
- Knowledge 消费 `INGESTION_PROCESSING / READY / FAILED`，以 CAS 投影文档状态；Retry 与 Reconciler 负责失败恢复和滞留任务收敛。
- Outbox、`processed_event`、Consumer Group、ACK/Reclaim 和 DLQ 共同构成可恢复的事件基础设施。

## 🔄 核心链路

### 文档摄取

1. Console 接收 multipart 文档并通过 Knowledge gRPC 上传。
2. Knowledge 保存文件与 Document，在同一事务写入 `DOC_UPLOADED` Outbox。
3. Redis Streams 将事件投递给 RAG；RAG 通过 Knowledge gRPC 流式读取并校验文件。
4. RAG 分块并构建 `chunk_fts` 与 `chunk_embedding`，Dense 向量由 Sidecar `/embed` 生成。
5. RAG 发布摄取状态事件；Knowledge 以 CAS 将 Document 投影为 `PROCESSING → READY / FAILED`。

### 问答查询

1. Open API 校验 API Key 缓存、版本水位和知识库 Scope。
2. RAG 在指定 `knowledgeBaseId + READY operationVersion` 内并行执行 Dense 与 Sparse 召回。
3. RRF 融合去重，Sidecar `/rerank` 精排候选，并回表扩展 Parent Evidence。
4. Evidence 进入 Stub 或 DeepSeek LLM，生成答案与可追溯 sources。

## 📦 项目结构

```text
├── crag-common/              # 统一响应、错误码与公共 HTTP 语义
├── crag-id/                  # Snowflake ID、Redis Worker 租约与时钟回拨处理
├── crag-platform-contracts/  # 平台 Probe Protobuf 契约
├── crag-knowledge-contracts/ # KnowledgeBase / Document gRPC 契约
├── crag-access-contracts/    # Identity / Membership / API Key gRPC 契约
├── crag-rag-contracts/       # Query / Ingestion Status gRPC 契约
├── crag-grpc-runtime/        # gRPC Server/Client 生命周期、服务身份与 Health
├── crag-event/               # Outbox、processed_event、Streams、ACK/Reclaim/DLQ
├── crag-access-service/      # 身份、会话、Membership、API Key 与 Scope
├── crag-knowledge-service/   # KnowledgeBase、Document、文件与摄取状态
├── crag-rag-service/         # 摄取、Storage、Retrieval、Query 与 Smoke Controller
├── crag-console-api/         # Console 正式 HTTP 入口
├── crag-open-api/            # Open 正式 HTTP 入口
├── sidecar/                  # Python `/embed` 与 `/rerank`
├── docs/                     # API 契约、交接文档与架构图
├── constraints/              # 工程与架构约束
├── plan/                     # 规划、决策与验收记录
└── scripts/                  # 静态校验和自动化 HTTP 回归
```

推荐阅读顺序：`crag-console-api` / `crag-open-api` 看入口编排 → contracts 看服务边界 → Access/Knowledge/RAG 看领域实现 → `crag-event` 看可靠事件机制。

## 🛠️ 技术栈

| 层次 | 技术 | 用途 |
| --- | --- | --- |
| 语言 | Java 21 | Record、虚拟线程与现代 Java 基线 |
| 框架 | Spring Boot 4.1 / Spring Framework 7 | Web、Security、Data、Actuator |
| AI | Spring AI 2.0 | LLM 集成基础 |
| 服务通信 | gRPC + Protobuf | 五进程间同步契约与服务身份 |
| 数据库 | PostgreSQL 17 + pgvector | 独立 Schema、业务数据、FTS 与向量检索 |
| 事件 | Redis 7.4 Streams | Outbox 事件传输、消费组与失效通知 |
| 嵌入 | GTE Chinese | Sidecar 托管的中文句向量模型 |
| 重排 | bge-reranker-v2-m3 | Sidecar 托管的候选精排模型 |
| LLM | Stub / DeepSeek | 确定性本地调试与真实生成 |
| 构建部署 | Gradle Kotlin DSL + Docker Compose | 多模块构建与完整拓扑编排 |

## ⚠️ 安全提示

本仓库的默认密钥仅用于本地 Demo。生产环境必须注入独立凭据，禁止记录完整 Token、API Key、用户文档、Prompt 或敏感模型响应；不要在生产开启会输出问答内容的 DEBUG 日志。

## 📄 许可证

MIT License — 详见 [LICENSE](./LICENSE)
