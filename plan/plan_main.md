# CRAG-Demo 总体规划（plan_main）

> 创建时间：2026-06-10
> 最后更新：2026-07-02（增加 Web Console 与开箱即用全栈 Demo 阶段）

---

## 一、项目定位

CRAG-Demo 是一个用于展示完整 RAG（Retrieval-Augmented Generation）链路的开箱即用全栈项目。

当前已完成从纯文本入库、Parent/Child Chunk、Dense/Sparse 混合检索、RRF、Rerank、Context 组装到 LLM 回答与引用返回的单进程基线。下一阶段在保留 RAG 链路可理解、可运行、可验证的前提下，将项目演进为支持租户、用户、知识库、文件上传和 API Key 查询的多租户知识平台。

目标形态不是将每个内部步骤都拆成微服务，而是围绕稳定业务边界形成五个独立进程：

- `crag-console-api`：管理控制台 HTTP 入口。
- `crag-open-api`：API Key 开放查询入口。
- `crag-access-service`：身份、租户、成员关系、会话和 API Key。
- `crag-knowledge-service`：KnowledgeBase、Document 和文件生命周期。
- `crag-rag-service`：Ingestion、Retrieval 和 Query。
- `web`：注册登录、Knowledge/Document/API Key 管理与知识检索对话。

核心原则：

- RAG 主链路仍是项目核心，服务化不掩盖 Chunk、索引、检索、重排和生成过程。
- Access、Knowledge、RAG 各自拥有独立数据边界，禁止跨 Schema 查询、写入和外键。
- 同步服务通信使用 gRPC，生命周期事件使用 Redis Streams。
- 跨服务状态变化采用可靠投递、幂等消费、补偿扫描和可观测性闭环。
- Docker Compose 继续提供本地可复现的完整运行与验收环境。

完整架构决策见 [`多租户知识平台架构设计`](../docs/superpowers/specs/2026-06-22-multi-tenant-knowledge-platform-design.md)。

---

## 二、产品边界

### 2.1 管理面

管理面由 `crag-console-api` 暴露，面向注册用户，覆盖：

- 注册、登录、刷新和退出。
- Tenant Membership 与 `OWNER / MEMBER` 权限。
- KnowledgeBase 创建、查看和删除。
- `.txt`、`.md` Document 上传、状态查看和删除。
- API Key 创建、禁用、轮换和吊销。

KnowledgeBase 必须归 Tenant 所有。用户通过 Membership 访问 Tenant 资源，首版不提供个人 KnowledgeBase。

### 2.2 开放查询面

开放查询面由 `crag-open-api` 暴露：

- 调用方使用 API Key 提交问题。
- 一个 API Key 只绑定一个 KnowledgeBase。
- 调用方不能在请求中指定或覆盖 `knowledgeBaseId`。
- 返回完整 RAG 答案和引用，不暴露内部原始 Retrieval 结果。

### 2.3 首版非目标

- 不迁移当前 Demo 数据，允许重建数据库。
- 不支持 PDF、Word、网页抓取、OCR 和断点续传。
- 不支持修改 Document 内容；修改通过上传新 Document、删除旧 Document 表达。
- 不将 Ingestion、Retrieval、Query 分别部署为独立服务。
- 不实现一个 API Key 访问多个 KnowledgeBase。
- 不引入 Kafka 等重量级消息系统。
- 不实现计费、配额和复杂组织层级。

---

## 三、技术方向

| 层级 | 方向 | 说明 |
| --- | --- | --- |
| 后端 | Java 21 + Spring Boot 4.1.0 + Spring Framework 7 | API 与业务服务 |
| 构建 | Gradle Kotlin DSL | 多模块统一构建 |
| 同步通信 | gRPC + Protobuf | 服务命令、查询和文件流式读取 |
| 异步通信 | Redis Streams | 生命周期事件、状态回传和缓存失效 |
| 数据 | PostgreSQL + pgvector | Access、Knowledge、RAG 使用独立 Schema 与账号 |
| ID | Snowflake `long / BIGINT` | HTTP、gRPC、事件边界使用十进制字符串 |
| 文件存储 | 存储契约 + Docker Volume | 文件路径仅限 Knowledge 内部 |
| Embedding / Rerank | Python Sidecar | 继续承载本地模型能力 |
| LLM | DeepSeek API + Spring AI 2.0.0 | 由 RAG Query 模块调用 |
| 部署 | Docker + Docker Compose | 本地完整环境和故障验收 |
| 前端 | React 19 + TypeScript + Ant Design | 管理控制台与知识检索 Chat |

---

## 四、目标架构与职责

```text
                 Browser :3000
                       │
                ┌──────▼──────┐
                │ Web Console │
                └──────┬──────┘
                       │ HTTP proxy
        ┌─────────────────────────┐
        │ crag-console-api        │
        │ JWT 验签、管理用例编排   │
        └───────┬─────────┬───────┘
                │ gRPC    │ gRPC
                ▼         ▼
     ┌────────────────┐  ┌──────────────────┐
     │ Access Service │  │ Knowledge Service│
     │ identity/auth  │  │ KB/doc/file      │
     └────────────────┘  └────────┬─────────┘
                                  │ Redis Streams
                                  ▼
                         ┌──────────────────┐
                         │ RAG Service      │
                         │ ingest/retrieve/ │
                         │ query            │
                         └────────┬─────────┘
                                  ▲
                                  │ gRPC
        ┌─────────────────────────┴┐
        │ crag-open-api            │
        │ API Key 鉴权、查询入口    │
        └──────────────────────────┘
```

### 4.1 HTTP 入口

- `crag-console-api` 负责 Cookie、Header、HTTP DTO、统一响应、本地 JWT 验签和跨服务管理用例编排，不拥有业务数据。
- `crag-open-api` 负责 API Key 接入、短 TTL 鉴权缓存、RAG 查询调用和答案响应，不接受客户端指定的 KnowledgeBase。

### 4.2 Access

- User、Tenant、Tenant Membership。
- 密码认证、Access JWT 与 Refresh Session。
- API Key 创建、哈希、禁用、轮换、吊销和鉴权。
- KnowledgeBase 的最小授权投影 `api_key_scope`。
- Tenant 权限矩阵和敏感操作实时校验。

### 4.3 Knowledge

- KnowledgeBase 与 Document 生命周期。
- 文件校验、存储、流式读取和物理清理。
- 用户可见的 Ingestion 状态。
- 上传、删除和补偿相关领域事件。

### 4.4 RAG

- Ingestion：解析文件、Chunk Split、Dense/Sparse 索引构建。
- Retrieval：Sparse、Dense、RRF、Rerank 和 Parent Evidence。
- Query：Context、Prompt、LLM 和引用组装。

三个模块保持窄公开接口和单向依赖，但首版共同部署于 `crag-rag-service`。

### 4.5 契约与数据边界

- `crag-platform-contracts` 保存跨领域通用的 Protobuf 基础契约，例如请求/响应公共元数据、稳定错误信息和平台 Probe；不包含具体领域 RPC 或业务实现。
- 领域 gRPC 契约按服务提供方归属，分别进入 `crag-access-contracts`、`crag-knowledge-contracts` 和 `crag-rag-contracts`。
- 稳定事件信封由可靠事件基础设施阶段定义，不与具体领域事件载荷或 gRPC 契约混放。
- Access、Knowledge、RAG 使用独立 Schema、数据库账号和 Schema 初始化所有权。当前三个业务服务统一沿用各自拥有的幂等 Schema SQL；若未来引入 Flyway/Liquibase，必须通过独立工程治理 Plan 同时设计三个 Schema 的迁移、基线和回滚，不允许单个服务独自切换启动机制。
- 服务间只保存对方资源 ID，不建立跨 Schema 外键。
- Console API 可以编排多个服务，业务服务不得形成同步循环调用。
- 所有消息采用至少一次投递，消费者必须幂等。

---

## 五、RAG 主链路

### 5.1 上传与索引

```text
Console API
  -> Knowledge 保存 File + Document(PENDING) + Outbox
  -> Redis Streams: DOC_UPLOADED
  -> RAG 创建 Ingestion Job
  -> Knowledge gRPC 流式读取文件
  -> Parent/Child Chunk Split
  -> Dense + Sparse 索引
  -> Redis Streams: PROCESSING / READY / FAILED
  -> Knowledge 更新用户可见状态
```

### 5.2 查询

```text
Open API
  -> Access 校验 API Key
  -> 获取唯一绑定的 knowledgeBaseId
  -> RAG Query
  -> Dense + Sparse（强制 knowledgeBaseId）
  -> RRF + Parent Evidence + Rerank
  -> Context + LLM
  -> answer + sources
```

RAG 的数据访问方法必须以 `knowledgeBaseId` 为必填参数，禁止先按 Chunk ID 查询后再补做隔离判断。

### 5.3 删除

删除分为同步封禁和异步物理清理：

- 删除请求成功返回前，Access 必须禁用相关 API Key，RAG 必须建立 `deletion_guard`。
- Knowledge 使用 `DELETE_REQUESTED → DOWNSTREAM_NOTIFIED → DOWNSTREAM_DELETED → DELETED` 状态机。
- RAG 幂等清理 Chunk、Dense、Sparse 和任务数据。
- Knowledge 在下游完成后清理文件，并保留最小 tombstone。
- Reconciler、死信、指标和告警处理长期未完成状态。

---

## 六、关键设计决策

| 主题 | 决策 |
| --- | --- |
| KnowledgeBase 所有权 | Tenant 所有 |
| 注册 | 自动创建默认 Tenant，注册用户为 `OWNER` |
| 角色 | 首版仅 `OWNER / MEMBER` |
| HTTP 入口 | Console API 与 Open API 独立进程 |
| 业务服务 | Access、Knowledge、RAG 三个独立服务 |
| RAG 部署 | Ingestion、Retrieval、Query 保持模块边界，共同部署 |
| 同步通信 | gRPC |
| 异步通信 | Redis Streams + Outbox |
| 数据隔离 | 独立 Schema、账号和迁移，禁止跨 Schema 访问 |
| ID | Snowflake `long / BIGINT`，边界使用十进制字符串 |
| 文件存储 | 契约隔离，首版 Docker Volume |
| API Key | 单 Key 绑定单 KnowledgeBase，只保存前缀与哈希 |
| 删除语义 | 同步封禁查询，异步完成物理清理 |
| 数据迁移 | 不迁移当前 Demo 数据，允许重建 |

---

### 6.1 暴露边界

- 对外业务流量最终只通过 Console API 与 Open API 进入。
- Access、Knowledge、RAG 的 HTTP 端口固定映射到宿主机，作为本地开发、Actuator 与诊断入口；不构成正式业务 API。
- 默认 Profile 不注册 `/api/v1/smoke/**` Controller；显式启用 `smoke` Profile 时，Smoke Controller 在原服务进程和原端口内注册，不创建 `*-smoke` 重复服务。
- `sidecar:8001` 是本地开发、Demo 与自动化回归的长期诊断例外，不属于公开业务 API；服务间调用仍必须使用 Compose 私有网络。

---

## 七、阶段路线

以下路线只维护依赖顺序和交付边界。已创建阶段使用真实 Plan 编号；尚未创建的后续阶段使用 `routerN` 作为路线占位，不表示对应 Plan 文件已经创建或进入 `draft`。任务、依赖、状态、进度和验收记录只进入 [`plan/index/README.md`](./index/README.md) 与对应 Plan。

| 路线编号 | 阶段 | 交付边界 |
| --- | --- | --- |
| `plan_14` | 多服务基础骨架 | 多服务骨架、独立 Schema、gRPC 契约与服务身份 |
| `plan_15` | 分布式 ID | Snowflake ID、Redis Worker 租约、时钟回拨与发号健康状态 |
| `plan_16` | RAG Service Module 收口 | RAG 内部 subproject 合并、legacy HTTP 迁入 smoke namespace |
| `plan_17` | 可靠事件基础设施 | Outbox、Redis Streams、事件信封、消费组、ACK、Reclaim 与消费幂等 |
| `plan_18` | Knowledge 垂直链路 | KnowledgeBase、Document、文件上传、存储与流式读取 |
| `plan_19` | RAG 多知识库化 | RAG 多知识库隔离、异步索引、Ingestion Job 与状态回传 |
| `plan_20` | Access 与权限 | User、Account、Tenant、Membership、JWT、Refresh Session 与 API Key |
| `router4` | 双 API 与摄取生命周期 | Console API、Open API、完整用例编排、摄取状态/重试/Reconciler 与旧混合入口退出 |
| `plan_22` | Web Console 与开箱即用部署 | 注册登录、Knowledge/Document/API Key 管理、Chat、UI 交接和 Node Docker 入口 |
| `router5` | 删除生命周期可靠性 | 删除状态机、下游物理清理、补偿、死信、监控、告警与故障恢复 |

每个阶段必须在准备执行时创建独立主 Plan，达到 `ready` 并提交后才能开始实现；完成实现后由未参与实现的新 session 独立验收。不得仅凭本路线表更新执行队列或开始编码。

### 7.1 未来演进候选

- **LLM 韧性治理**：仅在真实运行数据证明认证外故障、限流或超时形成稳定问题后，再创建独立主 Plan，评估限次重试与退避、熔断和超时隔离、多供应商降级、请求幂等、成本及延迟控制。
- 触发依据应包含可复现的故障率、限流率、超时率、延迟和成本数据，不因预想需求提前增加抽象。

---

## 八、约束与事实入口

- 多租户平台设计：[`docs/superpowers/specs/2026-06-22-multi-tenant-knowledge-platform-design.md`](../docs/superpowers/specs/2026-06-22-multi-tenant-knowledge-platform-design.md)
- Web Console 设计：[`docs/superpowers/specs/2026-07-02-web-console-design.md`](../docs/superpowers/specs/2026-07-02-web-console-design.md)
- Plan 工作流：[`constraints/plan-workflow.md`](../constraints/plan-workflow.md)
- Java 代码风格：[`constraints/code-style.md`](../constraints/code-style.md)
- HTTP API：[`constraints/api-style.md`](../constraints/api-style.md)
- 持久化：[`constraints/persistence-style.md`](../constraints/persistence-style.md)
- Retrieval：[`constraints/retrieval-style.md`](../constraints/retrieval-style.md)
- Java 包结构：[`constraints/package-structure.md`](../constraints/package-structure.md)
- Docker 部署结构：[`constraints/docker-structure.md`](../constraints/docker-structure.md)
- 测试工作流：[`constraints/test-workflow.md`](../constraints/test-workflow.md)

`plan_main.md` 只维护当前有效的项目定位、产品边界、技术方向、架构职责与阶段路线，不保存执行计划索引、任务进度或 Hotfix 明细。
