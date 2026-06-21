# CRAG-Demo 多租户知识平台架构设计

日期：2026-06-22

状态：已完成讨论，待用户审阅

范围：后续阶段的总体分层与演进方向，不对应具体 Plan 编号

## 1. 背景

CRAG-Demo 当前是单进程、多 Gradle 模块的 RAG 学习项目：

- 管理写入直接接收标题与纯文本，并立即生成 `docId` 和 Chunk。
- `docId`、`chunkId` 使用 UUID 字符串。
- 数据库只有 Chunk、Dense Embedding 和 Sparse FTS 等 RAG 数据。
- 查询没有 KnowledgeBase 维度，无法隔离多个知识空间。
- 管理入口和查询入口都在当前 `crag-api` 中。

下一阶段将项目扩展为支持租户、用户、知识库、文件上传和 API Key 查询的多服务知识平台。项目仍以清晰展示 RAG 全链路为目标，不追求把每个内部阶段都拆成微服务。

## 2. 目标

1. 使用 Snowflake `long` ID 替换 UUID，不考虑历史数据迁移。
2. 引入 Tenant、User、Membership、KnowledgeBase 和 Document。
3. 支持 `.txt`、`.md` 文件上传，并异步解析和索引。
4. Chunk、Dense、Sparse、Retrieval 和 Query 全链路按 KnowledgeBase 隔离。
5. 管理面与开放查询面使用独立 API 进程。
6. Access、Knowledge、RAG 使用独立服务和独立数据库 schema。
7. 同步通信从首版开始使用 gRPC，异步生命周期事件使用 Redis Streams。
8. 为上传、索引、删除和 API Key 失效建立可靠投递、幂等、补偿和告警机制。

## 3. 非目标

- 不迁移当前 Demo 数据，允许重建数据库。
- 首版不支持 PDF、Word、网页抓取或 OCR。
- 首版不支持修改已有 Document 内容；修改通过上传新 Document、删除旧 Document 表达。
- 首版 Open API 不暴露原始 Retrieval 结果，只返回完整 RAG 回答和引用。
- 不将 Ingestion、Retrieval、Query 分别部署为独立服务。
- 不实现一个 API Key 访问多个 KnowledgeBase。
- 不实现个人 KnowledgeBase；KnowledgeBase 必须归 Tenant 所有。
- 不在首版引入 Kafka 等重量级消息系统。

## 4. 总体架构

系统由五个独立进程组成。

### 4.1 HTTP 入口

#### `crag-console-api`

面向管理控制台，负责：

- 注册、登录、刷新和退出的 HTTP 协议。
- Cookie、Header、HTTP DTO 和统一响应。
- 本地验证 Access JWT，并建立当前用户请求上下文。
- 调用 Access 校验敏感操作权限。
- 编排创建 KnowledgeBase、上传文件、删除资源、管理 API Key 等跨服务用例。

Console API 不拥有用户、会话、知识库或文件数据。

#### `crag-open-api`

面向使用 API Key 的外部调用方，负责：

- 接收 API Key 和问题。
- 调用 Access 鉴权，并短时缓存鉴权结果。
- 将 Access 返回的 `knowledgeBaseId` 与问题传给 RAG。
- 返回 LLM 答案和引用来源。

调用方不得在请求中指定或覆盖 `knowledgeBaseId`。

### 4.2 业务服务

#### `crag-access-service`

负责：

- User、Tenant、Tenant Membership。
- 密码认证和密码哈希。
- Access JWT 的签发规则与密钥管理。
- Refresh Session 的签发、哈希、轮换、撤销和复用检测。
- Tenant 权限判断。
- API Key 的创建、哈希、禁用、轮换、吊销和鉴权。

Console API 只处理 JWT 的 Web 传输和本地验签。认证安全规则与有状态会话属于 Access。

#### `crag-knowledge-service`

负责：

- KnowledgeBase 生命周期。
- Document 元数据和用户可见处理状态。
- 文件写入、读取与物理清理。
- 文件存储契约及 Docker Volume 实现。
- 上传、删除和补偿相关领域事件。
- 通过 gRPC 向 RAG 流式提供文件内容，不泄漏文件路径。

#### `crag-rag-service`

内部保留三个清晰模块：

- Ingestion：文件解析、Chunk Split、Dense/Sparse 索引构建。
- Retrieval：Sparse、Dense、RRF、Rerank 和 Parent Evidence。
- Query：Context、Prompt、LLM 和引用组装。

这三个模块在首版部署于同一 RAG 服务中，继续使用窄公开接口和单向依赖约束。

### 4.3 通信与数据边界

- 同步命令和查询使用 gRPC。
- 生命周期通知、状态回传和缓存失效使用 Redis Streams。
- PostgreSQL 实例可以共享，但 Access、Knowledge、RAG 使用独立 schema、数据库账号和迁移脚本。
- 禁止服务跨 schema 查询、写入或建立外键。
- 跨服务只保存对方资源的 ID。
- Console API 可以编排多个服务；业务服务不得形成同步循环调用。
- 事件采用至少一次投递，所有消费者必须幂等。

建议建立独立的 `crag-contracts` 模块，保存 Protobuf gRPC 契约和稳定的事件信封定义。该模块不得包含业务实现。

## 5. 身份与所有权模型

### 5.1 注册与 Tenant

- 用户注册成功时自动创建一个默认 Tenant。
- 注册用户成为该 Tenant 的 `OWNER`。
- KnowledgeBase 归 Tenant 所有，不归 User 所有。
- User 通过 Tenant Membership 访问 Tenant 资源。

### 5.2 角色

首版只支持：

- `OWNER`
- `MEMBER`

权限规则：

| 操作 | OWNER | MEMBER |
| --- | --- | --- |
| 管理成员 | 允许 | 禁止 |
| 创建 KnowledgeBase | 允许 | 允许 |
| 查看 Tenant 内 KnowledgeBase | 允许 | 允许 |
| 向任意 KnowledgeBase 上传 Document | 允许 | 允许 |
| 删除自己上传的 Document | 允许 | 允许 |
| 删除其他用户上传的 Document | 允许 | 禁止 |
| 删除 KnowledgeBase | 允许 | 禁止 |
| 创建、禁用、轮换、吊销 API Key | 允许 | 禁止 |

KnowledgeBase 名称仅作展示，可以重名。所有关联与权限判断使用 ID。

## 6. 领域模型

### 6.1 Access schema

#### `user_account`

保存用户身份、密码哈希、状态和审计时间。

#### `tenant`

保存租户基本信息和状态。

#### `tenant_membership`

保存 User 与 Tenant 的关系和 `OWNER / MEMBER` 角色。

#### `refresh_session`

保存 Refresh Token 哈希、会话族、签发时间、到期时间、轮换时间、撤销状态和复用检测信息。

#### `api_key`

保存：

- `apiKeyId`
- `tenantId`
- `knowledgeBaseId`
- 可检索前缀
- 密码学哈希
- 状态
- 创建者
- 创建、最后使用、到期、吊销时间

一个 API Key 只绑定一个 KnowledgeBase。完整 Key 只在创建时返回一次。

#### `api_key_scope`

保存 KnowledgeBase 在 Access 中的最小授权投影：`knowledgeBaseId`、`tenantId` 和 `ACTIVE / BLOCKED` 状态。它不保存 KnowledgeBase 业务信息。

- Console 创建 KnowledgeBase 后注册该 Scope。
- API Key 创建与 Scope 状态检查在 Access 的同一事务中完成。
- 删除时 Access 原子地将 Scope 标记为 `BLOCKED` 并禁用其全部 API Key。
- Scope 注册失败不会删除已经创建的 KnowledgeBase，但该 KnowledgeBase 暂时不能创建 API Key；Knowledge 创建事件和 Reconciler 负责补偿注册。

#### `outbox_event`

保存 API Key 缓存失效等 Access 领域事件的可靠投递记录。

### 6.2 Knowledge schema

#### `knowledge_base`

保存：

- `knowledgeBaseId`
- `tenantId`
- 展示名称
- 创建者
- 删除状态与操作版本
- 创建、更新时间

#### `document`

保存：

- `docId`
- `knowledgeBaseId`
- `tenantId`
- 上传者
- 原始文件名
- 文件类型、大小、校验和
- 用户可见的 Ingestion 状态
- 删除状态与操作版本
- 创建、更新时间

Document 内容不可变。

#### `file_object`

保存 Knowledge 内部文件定位、大小、校验和和物理清理状态。文件路径不得出现在跨服务契约中。

#### `outbox_event`

保存 Document 上传、Document 删除、KnowledgeBase 删除等领域事件的可靠投递记录。

### 6.3 RAG schema

#### `ingestion_job`

以 `docId + operationVersion` 幂等创建，保存详细处理阶段、失败原因、重试次数和时间。

Document 用户可见的 Ingestion 状态固定为：

```text
PENDING → PROCESSING → READY
                     ↘ FAILED
```

重试使用新的 Ingestion Job attempt，但不创建新的 Document。

#### `chunk`

主键改为 Snowflake ID，并增加：

- `knowledgeBaseId`
- `docId`
- `parentChunkId`
- Chunk 内容和处理状态

#### `chunk_embedding`

继续以 `chunkId` 关联 Chunk，并通过查询路径强制使用 `knowledgeBaseId` 过滤。

#### `chunk_fts`

继续以 `chunkId` 关联 Chunk，并通过查询路径强制使用 `knowledgeBaseId` 过滤。

#### 消费幂等记录

保存已处理的 `eventId`，或使用受唯一约束保护的业务幂等键。重复事件不得重复创建任务、Chunk 或状态副作用。

#### `deletion_guard`

保存已经进入删除流程的 `docId` 或 `knowledgeBaseId` 及其操作版本。Retrieval 在任何召回前检查该防线，确保已请求删除但尚未物理清理的数据不再参与查询。

#### `outbox_event`

保存 Ingestion 状态回传、删除完成回执等 RAG 领域事件的可靠投递记录。

## 7. Snowflake ID

### 7.1 格式

默认使用经典 64 位布局：

- 1 位符号位，固定为 0。
- 41 位毫秒时间戳，纪元为 `2026-01-01T00:00:00Z`。
- 10 位 Worker ID，最多 1024 个同时发号实例。
- 12 位毫秒内序列号，每实例每毫秒最多 4096 个 ID。

Java 和数据库使用 `long / BIGINT`。HTTP、gRPC 和事件契约使用十进制字符串，避免 JavaScript 和其他语言的整数精度问题。

### 7.2 Worker 租约

- 所有需要发号的服务实例从 Redis Worker 池动态领取 Worker ID。
- 租约默认 30 秒，实例每 10 秒续租。
- 租约记录包含实例标识和单调递增的租约 epoch。
- 本地生成器只在租约安全截止时间前工作。
- 续租失败且到达安全截止时间后，实例立即停止发号并转为不健康。
- 新实例在 Redis 不可用时不得启动发号。
- 已有实例只可在仍可证明租约有效的窗口内继续发号。

租约 epoch 用于识别新旧持有者和诊断冲突，不直接写入 Snowflake 位段。暂停后恢复的旧实例必须重新检查租约有效期，不能沿用过期 Worker ID。

### 7.3 时钟回拨

- 回拨不超过 5 毫秒时，生成器等待时钟追平。
- 回拨超过 5 毫秒时拒绝发号，并将服务置为不健康。
- 同一毫秒序列耗尽时等待下一毫秒，不溢出序列位。

这些数值作为默认配置，可在不改变 ID 布局的前提下调整。

## 8. 核心流程

### 8.1 注册

1. Console 接收注册请求。
2. Access 在一个本地事务中创建 User、默认 Tenant 和 `OWNER` Membership。
3. Access 建立 Refresh Session 并签发 Token 材料。
4. Console 设置安全 Cookie，并返回 Access Token。

### 8.2 创建 KnowledgeBase

1. Console 从 JWT 建立当前 User。
2. Console 调用 Access 校验 Tenant Membership。
3. Console 调用 Knowledge 创建 KnowledgeBase。
4. Knowledge 保存 `tenantId`、创建者和 Snowflake ID，并写入 KnowledgeBase 创建 Outbox。
5. Console 调用 Access 注册 `api_key_scope`。
6. 若同步注册失败，Knowledge 的创建事件和 Reconciler 最终补偿；Scope 建立前禁止创建 API Key。

### 8.3 上传与索引

1. Console 校验用户对 Tenant 和 KnowledgeBase 的权限。
2. Console 将 `.txt` 或 `.md` 文件流式传给 Knowledge。
3. Knowledge 校验扩展名、MIME、大小和 UTF-8 内容；默认最大文件大小为 10 MiB，可通过配置收紧。
4. Knowledge 使用服务端生成的存储名先写临时文件，完成校验后原子重命名到最终位置。
5. Knowledge 在一个数据库事务中保存 File、`Document(PENDING)` 和 `DOC_UPLOADED` Outbox。
6. Outbox Publisher 将事件发布到 Redis Streams。
7. RAG 消费事件，按 `docId + operationVersion` 幂等创建 Ingestion Job。
8. RAG 通过 Knowledge gRPC 流式读取文件。
9. RAG 解析文本、切分 Chunk、构建 Dense 与 Sparse 索引。
10. RAG 通过事件回传 `PROCESSING / READY / FAILED`。
11. Knowledge 更新用户可见的 Document 状态。

首版不支持断点续传。HTTP 上传或 gRPC 文件读取中断后，从头重试整个文件。数据库事务失败时立即清理已落盘文件；进程在落盘后、提交前崩溃产生的孤立文件，由 Knowledge 的定时清理任务根据文件年龄和数据库引用关系回收。

### 8.4 Open API 查询

1. Open API 接收 API Key 和问题。
2. Open API 查询短 TTL 本地缓存。
3. 缓存未命中时，通过 Access gRPC 校验 Key 前缀、哈希、状态和到期时间。
4. Access 返回该 Key 唯一绑定的 `knowledgeBaseId`。
5. Open API 调用 RAG gRPC，传入 `knowledgeBaseId` 和问题。
6. RAG 的 Sparse、Dense、Parent 回表和最终证据组装全部按 `knowledgeBaseId` 过滤。
7. Query 使用隔离后的证据调用 LLM。
8. Open API 返回答案和引用来源。

### 8.5 API Key 缓存失效

1. Owner 禁用、轮换或吊销 API Key。
2. Access 原子更新 Key 状态并写入 Outbox。
3. Access 发布 `API_KEY_INVALIDATED`。
4. Open API 删除对应缓存项。
5. 即使事件延迟或暂时丢失，短 TTL 仍限制旧鉴权结果的有效窗口。

### 8.6 删除

删除由“同步封禁”和“异步物理清理”两部分组成。

Console 删除编排：

1. 通过 Access 校验删除权限。
2. 调用 Knowledge 将资源推进至 `DELETE_REQUESTED`，取得操作版本。
3. 调用 Access 禁用该 KnowledgeBase 的全部 API Key。
4. 调用 RAG 写入 `deletion_guard`，立即阻止目标 Document 或 KnowledgeBase 参与 Retrieval。
5. Access 和 RAG 均确认封禁后，Console 才返回删除请求已接受。
6. 任一同步封禁调用失败时，Knowledge 仍保留 `DELETE_REQUESTED`，由 Outbox 和 Reconciler 继续补偿；Console 返回明确的暂时失败，不把资源恢复为 Active。

因此，“立即不可查询”指删除请求成功返回时，Access 与 RAG 两道查询防线均已生效。物理数据清理继续异步执行。

异步删除使用状态机：

```text
ACTIVE
  → DELETE_REQUESTED
  → DOWNSTREAM_NOTIFIED
  → DOWNSTREAM_DELETED
  → DELETED
```

规则：

- 进入 `DELETE_REQUESTED` 后，Knowledge 拒绝该资源的新上传和写入。
- Knowledge 在同一事务中推进资源状态并写入删除 Outbox。
- RAG 按资源 ID 和操作版本幂等删除 Chunk、Dense、Sparse 和任务数据。
- RAG 发布完成事件后，Knowledge 才推进到 `DOWNSTREAM_DELETED`。
- Knowledge 随后物理清理文件，并将资源推进到 `DELETED`。
- `DELETED` 行作为最小 tombstone 保留，用于幂等和审计；业务内容及文件已清理。
- 定时 Reconciler 扫描超时状态，生成具有新 `eventId`、但保持相同操作版本的新一轮重试事件。
- 长期未完成时记录指标、结构化日志并触发告警。
- 迟到事件只能完成同一操作版本，不能使状态倒退。

KnowledgeBase 删除时先整体封禁，再扇出并追踪其所有 Document 和 RAG 数据清理；只有全部下游资源完成后，KnowledgeBase 才进入 `DELETED`。

## 9. Outbox 与 Redis Streams

### 9.1 职责分离

- 资源状态表达业务生命周期。
- Outbox 表达一次领域事件是否可靠发布。
- Redis Streams 负责跨服务传输。
- Reconciler 检查下游业务是否真正完成。

Outbox 不取代业务补偿；业务补偿也不取代 Outbox。

### 9.2 Outbox 状态

```text
PENDING → PUBLISHING → PUBLISHED
                  ↘ RETRY_WAIT
                  ↘ DEAD
```

- Publisher 使用 CAS 抢占待发布事件。
- 发布失败按退避策略重试。
- `DEAD` 事件必须打点和告警，不能静默丢弃。

### 9.3 事件信封

每条事件至少包含：

- `eventId`
- `eventType`
- `producer`
- `resourceType`
- `resourceId`
- `operationVersion`
- `occurredAt`
- `traceId`
- `payloadVersion`
- 事件载荷

消费者使用 Redis Consumer Group、ACK、Pending Reclaim 和死信处理。事件版本必须向后兼容；新增字段不得破坏旧消费者。

## 10. 认证与凭证

### 10.1 Console 登录

- Access Token 是短期 JWT。
- Refresh Token 是高熵随机字符串，数据库仅保存哈希。
- 每次刷新轮换 Refresh Token。
- 旧 Token 再次使用时触发复用检测，并撤销其会话族。
- 退出登录、密码变更或用户禁用可以撤销 Refresh Session。
- Console 使用 Access 发布的公钥本地验证 JWT。
- 敏感操作仍调用 Access 实时校验权限和账号状态。

### 10.2 API Key

- 完整 Key 仅创建时返回一次。
- Key 包含可检索前缀和高熵秘密部分。
- Access 通过前缀定位记录，再使用带服务端 Pepper 的 HMAC-SHA-256 验证高熵秘密。
- 日志只允许记录前缀，禁止记录完整 Key。
- Open API 使用短 TTL 缓存；Access 事件负责主动失效。

用户密码使用 Argon2id。Refresh Token 与 API Key 都是高熵随机秘密，使用带独立 Pepper 的 HMAC-SHA-256 保存和比对。Pepper 只存在于运行时 Secret，不写入数据库。

## 11. 服务安全

- 外部只暴露 Console API 和 Open API。
- Access、Knowledge、RAG 不开放公网端口。
- Demo 部署使用每调用方独立的服务身份凭据，通过 gRPC Metadata 传递，并存放于运行时 Secret/环境配置。
- 生产部署目标是 gRPC mTLS，每个服务使用独立客户端证书。
- Docker 私有网络不能替代服务身份验证。
- JWT、Refresh Token、完整 API Key、文件内容、Prompt、Context 和向量禁止写入日志。
- 文件存储名由服务端生成，禁止将原始文件名拼入路径。
- RAG 数据访问方法必须以 `knowledgeBaseId` 为必填参数，禁止按 Chunk ID 查询后再补做隔离判断。

## 12. 错误处理

- gRPC 使用明确的状态码和稳定业务错误详情，不透传 SQL、堆栈或下游原始错误。
- Console/Open API 将 gRPC 错误映射为统一 HTTP 状态和业务错误码。
- 权限不足与资源不存在对外不得泄漏其他 Tenant 的资源存在性。
- 上传校验失败不创建 Document。
- 文件已写入但数据库事务失败时，由 Knowledge 清理孤立临时文件。
- Ingestion 失败保留 Job 诊断信息，并向 Knowledge 回传安全的失败分类。
- 外部模型失败不得在数据库事务中重试。
- 所有跨服务调用必须配置超时；只有幂等操作允许受控重试。

## 13. 可观测性

HTTP、gRPC 和消息统一传播 `traceId`。

至少提供以下指标：

- Snowflake Worker 租约领取、续租失败、时钟回拨和停止发号。
- Outbox 待发布数量、最老事件年龄、失败次数和 Dead 数量。
- Redis Stream 消费延迟、Pending 数量和 Reclaim 次数。
- Ingestion 各阶段耗时、失败率和长期滞留任务。
- 删除各状态数量、滞留时间和长期失败数量。
- gRPC 调用延迟、错误率和超时。
- API Key 鉴权成功、失败、缓存命中和失效事件延迟。
- KnowledgeBase 隔离拒绝和权限拒绝。

长期未完成的 Ingestion、删除和 Outbox 事件必须同时产生指标与结构化日志，并接入告警。

## 14. 测试策略

### 14.1 单元测试

- Snowflake 位布局、并发唯一性、序列耗尽和时钟回拨。
- Worker 租约状态转换。
- Tenant 权限矩阵。
- JWT、Refresh Session 轮换与复用检测。
- API Key 哈希与状态。
- Document、KnowledgeBase 和删除状态机。
- Outbox CAS、重试和合法迁移。
- 消费幂等和迟到事件处理。

### 14.2 组件测试

- 每个服务只连接自己的 PostgreSQL schema。
- 使用真实 Redis 验证 Worker 租约、Streams、ACK、Pending Reclaim 和缓存失效。
- 使用本地文件系统验证上传、流式读取和清理。

### 14.3 契约测试

- Protobuf 字段兼容性。
- gRPC 错误码和超时。
- 文件流式读取的分片、校验和和中断恢复语义。
- 事件信封和 payload 版本兼容性。

### 14.4 服务集成测试

- Knowledge 发布上传事件，RAG 消费、读取文件并回传状态。
- 重复和乱序事件不产生重复数据或状态倒退。
- 删除事件失败后可重发并最终完成。
- API Key 吊销事件能主动清理 Open API 缓存。

### 14.5 全链路测试

1. 注册并自动创建默认 Tenant。
2. 创建 KnowledgeBase。
3. 上传 Document 并等待 `READY`。
4. 创建 API Key。
5. 通过 Open API 查询并获得答案与引用。
6. 删除 Document 或 KnowledgeBase，并确认下游数据和文件完成清理。

### 14.6 隔离与故障测试

- 不同 Tenant、KnowledgeBase 和 API Key 之间不能交叉召回。
- 伪造资源 ID 不能越权读取或删除。
- Redis 暂时不可用。
- gRPC 超时和进程中途退出。
- 重复、延迟和乱序消息。
- Snowflake 时钟回拨和租约丢失。
- Refresh Token 复用。
- 路径穿越、非法 MIME、超限文件和非 UTF-8 文件。

## 15. 分层演进顺序

本节只描述架构层次，不创建或编号具体 Plan。

### 层 1：分布式基础设施

- Snowflake ID 与 Redis Worker 租约。
- gRPC 契约规范和服务身份。
- 事件信封、Redis Streams 和 Outbox 基础能力。
- 独立 schema、账号和多启动进程骨架。

### 层 2：Knowledge 垂直链路

- KnowledgeBase、Document、File Object。
- Docker Volume 文件存储。
- `.txt / .md` 上传和流式读取。
- 上传 Outbox 与用户可见状态。

### 层 3：RAG 多知识库化

- Ingestion Job。
- Chunk、Dense、Sparse 增加 KnowledgeBase 归属。
- Retrieval 和 Query 强制携带 `knowledgeBaseId`。
- 消费上传事件并回传处理状态。

### 层 4：Access 与权限

- User、默认 Tenant、Membership。
- JWT 和 Refresh Session。
- API Key 与 KnowledgeBase 单绑定。
- 权限矩阵和缓存失效事件。

### 层 5：双 API 入口

- Console API 管理用例编排。
- Open API 的 API Key 鉴权和完整 RAG 查询。
- 移除现有混合职责的 `crag-api`。

### 层 6：生命周期可靠性

- Document 和 KnowledgeBase 删除状态机。
- 下游清理、回执、补偿扫描和死信。
- 指标、告警和故障恢复验收。

每一层后续应分别经过设计确认、具体 Plan、实现提交和独立验收。不得把整套服务化改造放入一个巨型 Plan。

## 16. 关键决策摘要

| 主题 | 决策 |
| --- | --- |
| KnowledgeBase 所有权 | Tenant 所有 |
| 注册 | 自动创建默认 Tenant，用户为 Owner |
| 角色 | OWNER / MEMBER |
| HTTP 入口 | Console API 与 Open API 独立进程 |
| 业务服务 | Access、Knowledge、RAG 三个独立服务 |
| 同步通信 | 首版直接使用 gRPC |
| 异步通信 | Redis Streams |
| 文件存储 | 契约隔离，首版 Docker Volume |
| 文件类型 | `.txt / .md` |
| ID | Snowflake `long/BIGINT`，边界使用十进制字符串 |
| Worker ID | Redis 动态租约 |
| API Key | 单 Key 绑定单 KnowledgeBase，仅保存前缀与哈希 |
| Console 登录 | 短 JWT + 可撤销、轮换 Refresh Session |
| 数据库 | 一个 PostgreSQL 实例，三套 schema 和账号 |
| Document 内容 | 不可变 |
| 查询接口 | 只提供完整回答与引用 |
| 删除 | 状态机、Outbox、下游回执、补偿和告警 |
