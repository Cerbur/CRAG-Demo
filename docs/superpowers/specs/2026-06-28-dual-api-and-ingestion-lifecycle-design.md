# CRAG-Demo 双 API 与摄取生命周期设计

日期：2026-06-28

状态：已确认并完成书面规格复核

范围：router4 / plan21；Console API、Open API、正式 RAG 契约、摄取生命周期可靠性、API Key 缓存失效与单服务 Smoke 拓扑

## 1. 背景

`plan_18` 已交付 KnowledgeBase、Document、文件存储、文件流式读取和 `DOC_UPLOADED` 事件；`plan_19` 已交付 RAG 多知识库隔离、异步摄取、状态事件生产和按 KnowledgeBase 查询；`plan_20` 已交付身份、Tenant Membership、JWT、Refresh Session、API Key 和失效事件生产。

当前两个正式 HTTP 入口仍只有 Probe。Access 与 Knowledge contracts 缺少前端管理面所需的部分查询能力，RAG 没有正式业务 contracts。RAG 虽然发布 `PROCESSING / READY / FAILED`，Knowledge 尚未消费，Document 会长期停留在 `PENDING`。Docker Compose 还为同一业务服务复制了 `*-smoke` 服务定义。

router4 需要把三个 Provider 组合成可供前端与外部调用方使用的正式产品入口，并把上传、状态展示、失败恢复和查询隔离做成完整闭环。摄取生命周期可靠性由 router4 完整交付；router5 只继续负责 KnowledgeBase/Document 删除、下游物理清理及删除补偿。

## 2. 目标

1. 交付完整 Console 管理面：认证、Tenant、Membership、KnowledgeBase、Document、摄取状态与重试、API Key。
2. 交付 Open Query：API Key 鉴权、短 TTL 缓存、主动失效、RAG 答案与前端可用引用。
3. 建立正式 `crag-rag-contracts`，并以兼容性新增补齐 Access/Knowledge contracts。
4. 打通 Document 从上传、异步摄取、状态投影、失败终态、自动/手动重试到 READY 查询的可靠生命周期。
5. 保持 Console/Open 无数据库、无领域数据所有权，只通过 contracts 编排 Provider。
6. 删除重复 `*-smoke` Compose 服务；原服务固定暴露本地端口，Smoke Controller 仅在 `smoke` Profile 注册。
7. 提供 OpenAPI 3.1、中文前端交接指南、代码 reference 和可机械校验的契约。

## 3. 非目标

- 不实现 KnowledgeBase 或 Document 删除；删除状态机、下游物理清理和删除补偿属于 router5。
- 不支持文件内容修改、下载、PDF、Word、网页抓取、OCR 或断点续传。
- 不新增 Tenant 创建、用户资料修改、密码找回、MFA、邀请 Token、计费或配额。
- 不让一个 API Key 访问多个 KnowledgeBase，也不允许 Open 请求覆盖 `knowledgeBaseId`。
- 不引入 Gateway 数据库、持久化 Saga 表、Kafka、Flyway 或 Liquibase。
- 不改变 Prompt、RRF、Rerank 或模型供应商协议；真实 DeepSeek 调用不是本阶段完成门槛。

## 4. 总体架构

采用两个薄入口和 Provider 所有契约的结构：

```text
Browser / Console client
  -> crag-console-api
     -> Access gRPC      身份、Tenant、实时权限、Membership、Scope、API Key
     -> Knowledge gRPC   KnowledgeBase、Document、文件上传、摄取投影

External caller
  -> crag-open-api
     -> Access gRPC      API Key 鉴权
     -> local auth cache + API_KEY_INVALIDATED consumer
     -> RAG Query gRPC   隔离查询、LLM、引用

Knowledge DOC_UPLOADED -> Redis Streams -> RAG ingestion
RAG INGESTION_*        -> Redis Streams -> Knowledge status projection
Knowledge KB_CREATED   -> Redis Streams -> Access Scope projection
```

### 4.1 Console API

`crag-console-api` 按 `auth / tenant / membership / knowledge / document / apikey` 用例切片。它负责 HTTP DTO、Cookie、JWT 本地验签、当前用户上下文、实时权限调用、跨服务编排、统一错误和 trace 传播，不保存业务数据。

### 4.2 Open API

`crag-open-api` 只负责 Bearer API Key、鉴权缓存、缓存失效事件、Query gRPC 和答案映射。它不依赖 Knowledge，不接受 Tenant/KnowledgeBase 定位参数，也不保存持久化事件处理状态。

### 4.3 Provider 与 contracts

- 新增 `crag-rag-contracts`，只保存正式 Query 与 Ingestion Status Protobuf，不依赖 Spring、runtime 或 Service module。
- Access contracts 兼容性新增用户安全投影、用户所属 Tenant 列表、Refresh Token Logout、`EnsureScope`、Scope 查询以及 API Key 查询/列表。
- Knowledge contracts 兼容性新增摄取状态字段、失败安全信息、retry 命令和 Reconciler 所需的窄查询；已有上传、查询和列表语义保持兼容。
- Console 只依赖 Access/Knowledge/RAG contracts 和公共 runtime；Open 只依赖 Access/RAG contracts 和公共 runtime。
- Console/Open 不共享业务编排模块；`crag-common` 只承载响应、错误码和 trace 等稳定基础设施。

## 5. 正式 HTTP 契约

所有 ID 在 HTTP/JSON 中使用十进制字符串，所有时间使用 RFC 3339 UTC。列表统一使用 `pageSize`、`pageToken`，响应统一使用 `items`、`nextPageToken`。默认页大小 20，最大 100。

### 5.1 Console Auth

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| POST | `/api/v1/auth/register` | 注册 User/Account/默认 Tenant，返回 Access JWT 并设置 Refresh Cookie |
| POST | `/api/v1/auth/login` | 登录并创建 Session Family |
| POST | `/api/v1/auth/refresh` | 从 Cookie 轮换 Refresh Token，返回新 Access JWT |
| POST | `/api/v1/auth/logout` | 通过 Cookie 定位并撤销 Session Family，清除 Cookie |
| GET | `/api/v1/auth/me` | 返回当前用户安全投影 |

register/login/refresh 的响应体不包含 Refresh Token。register 额外返回默认 Tenant；login 后客户端通过 Tenant 列表恢复工作上下文。

### 5.2 Tenant 与 Membership

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| GET | `/api/v1/tenants` | 当前用户的有效 Tenant 与角色列表 |
| GET | `/api/v1/tenants/{tenantId}/members` | 成员列表 |
| POST | `/api/v1/tenants/{tenantId}/members` | 按 Username 添加已注册用户 |
| PATCH | `/api/v1/tenants/{tenantId}/members/{userId}` | 调整 OWNER/MEMBER |
| DELETE | `/api/v1/tenants/{tenantId}/members/{userId}` | 移除成员并保护最后 OWNER |

成员安全投影包含 userId、nickname、role、status 和时间，不暴露密码、账号状态或登录标识。

### 5.3 KnowledgeBase 与 Document

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| GET | `/api/v1/tenants/{tenantId}/knowledge-bases` | 列出 Tenant 的 KnowledgeBase |
| POST | `/api/v1/tenants/{tenantId}/knowledge-bases` | 创建 KnowledgeBase 并确保 Access Scope |
| GET | `/api/v1/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}` | KnowledgeBase 详情 |
| GET | `/api/v1/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents` | Document 列表与摄取状态 |
| POST | `/api/v1/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents` | 单文件 multipart 上传 |
| GET | `/api/v1/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents/{docId}` | Document 与摄取详情 |
| POST | `/api/v1/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents/{docId}/ingestion/retry` | 对允许重试的 FAILED 摄取创建新版本 |

上传只接受一个 `.txt` 或 `.md` 文件，默认上限 10 MiB。Console 从 multipart 临时内容计算 size 和 SHA-256，再以 metadata + bytes 分片发送 Knowledge。成功创建 Document 后返回 HTTP 202 和 `PENDING`，客户端轮询详情直至 `READY` 或 `FAILED`。

Document 响应包含 operationVersion、attempt、failureCategory、安全 failureMessage、retryable、startedAt 和 completedAt。确定性文件错误要求重新上传；只有可重试分类且未达到次数上限时允许 retry。

### 5.4 API Key

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| GET | `/api/v1/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/api-keys` | Key 安全投影列表 |
| POST | `/api/v1/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/api-keys` | 创建 Key，完整秘密仅返回一次 |
| GET | `/api/v1/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/api-keys/{apiKeyId}` | Key 详情 |
| POST | `.../api-keys/{apiKeyId}/disable` | 暂停 Key |
| POST | `.../api-keys/{apiKeyId}/enable` | 恢复 Key |
| POST | `.../api-keys/{apiKeyId}/rotate` | 原子轮换，完整新秘密仅返回一次 |
| POST | `.../api-keys/{apiKeyId}/revoke` | 终态吊销 |

### 5.5 Open Query

`POST /api/v1/query` 只从 `Authorization: Bearer crag_...` 读取 Key。请求体只有 `question`；去除首尾空白后允许 1–2000 个 Unicode 字符，不得出现 tenantId 或 knowledgeBaseId。响应只包含 answer 和 sources。source 结构为 `reference + documentId + excerpt`，不暴露 Chunk ID、分数、Prompt、Context 或内部 Retrieval 结果。excerpt 来自实际进入 Context 的 Parent Evidence，最多 500 个 Unicode 字符，且与 `[S1]` 引用连续对应。

## 6. Console 认证与授权

Access JWT 放在响应体，由客户端后续使用 `Authorization: Bearer`；Refresh Token 只存 Cookie。Cookie 默认使用 `HttpOnly + Secure + SameSite=Lax + Path=/api/v1/auth`，不设置 Domain。本地 HTTP 必须通过显式配置关闭 Secure，正式配置不得静默降级。

refresh/logout 校验同站 Origin/Referer，不开放通配 CORS，不引入前端可读 CSRF Token。Logout 通过完整 Refresh Token 在 Access 内定位 Session Family，因此不依赖仍有效的 Access JWT；无论下游结果如何，Console 都清除本地 Cookie。

Console 启动时从 Access 拉取 JWT 公钥集。验签必须校验 kid、alg、signature、issuer、audience、exp 和 nbf。未知 kid 只触发一次同步刷新，仍未知则拒绝。缓存中的既有公钥可以继续验证已签发的 15 分钟 JWT；Access 不可达时，新登录/刷新失败，但普通已登录请求不因无意义的在线验签而中断。

JWT 只建立 userId/sessionFamilyId 请求上下文，不信任 Tenant 或 Role。Tenant 级读写调用 Access 实时授权；Membership 与 API Key Provider 再执行自身业务授权。未认证返回 401，已认证但无权返回 403，跨 Tenant 资源查询统一返回 404。

## 7. KnowledgeBase 与 Scope 一致性

创建流程：

1. Console 调用 Access 校验 `CREATE_KNOWLEDGE_BASE`。
2. Knowledge 创建 KnowledgeBase，并在同一事务写 `KNOWLEDGE_BASE_CREATED` Outbox。
3. Console 同步调用 Access `EnsureScope`。
4. Scope 成功时返回 HTTP 201、`apiKeyReady=true`。
5. Scope 暂时失败时仍返回已创建资源、`apiKeyReady=false`，不假装跨服务回滚，也不让客户端重试创建出重复 KnowledgeBase。
6. Access 幂等消费 `KNOWLEDGE_BASE_CREATED` 补齐 Scope；API Key 查询/创建前，Console 以 KB 归属校验加 `EnsureScope` 再兜底一次。

EnsureScope 对同一 KnowledgeBase/Tenant 幂等；不同 Tenant 冲突；BLOCKED 是终态，任何补偿不得复活。Access consumer 使用本地 processed_event 保证至少一次事件下的幂等处理。

## 8. Document 摄取生命周期

### 8.1 状态与持久化

Knowledge Document 保存：`ingestion_status`、`operation_version`、`ingestion_attempt`、`ingestion_job_id`、failureCategory、安全 failureMessage、startedAt、completedAt、nextRetryAt、version。

对一个 operationVersion，合法状态为：

```text
PENDING -> PROCESSING -> READY
                      -> FAILED
PENDING -------------> READY / FAILED   # 容忍中间状态事件丢失
```

READY 与 FAILED 是该版本终态。同版本首个终态获胜；重复事件幂等 ACK；矛盾终态不覆盖事实，记录指标并进入安全诊断路径。旧 operationVersion 事件只记录并 ACK，不得使状态倒退。

### 8.2 RAG 当前版本防线

RAG 新增 `document_ingestion_head`，按 docId 保存当前 operationVersion。Ingestion Job 仍以 `(docId, operationVersion)` 唯一。Chunk、Dense、Sparse 全部记录 operationVersion；Retrieval 只读取同时满足以下条件的数据：

- knowledgeBaseId 匹配；
- operationVersion 等于 document_ingestion_head；
- 对应 Ingestion Job 为 READY。

新版本会使旧 Worker 失去 current head 资格。即使旧 Worker 迟到写入，旧版本数据也不能进入召回。新版本处理前清理旧失败残留；失败或部分索引从未成为可查询数据。

### 8.3 状态消费与恢复

Knowledge 消费 `INGESTION_PROCESSING / READY / FAILED`，校验 event type、payload version、tenantId、knowledgeBaseId、docId、operationVersion 和合法状态迁移。failureMessage 在 RAG 生产端和 Knowledge 接收端都做安全限长。

Knowledge Reconciler 扫描滞留 PENDING/PROCESSING，通过 RAG Ingestion Status RPC 查询权威 Job：

- Job 已推进：修复 Knowledge 投影；
- 当前版本未创建或进入可重试失败：按退避策略、次数上限和 CAS 递增 operationVersion，并在同一事务写新的 DOC_UPLOADED；
- RAG PROCESSING 超过执行上限：RAG 先以 CAS 终态化为安全超时失败，再由 Knowledge 决定是否创建新版本；
- 已达上限或确定性错误：保持 FAILED，要求用户重新上传。

自动与手动 retry 共用 attempt 上限、分类策略和 CAS。默认总 attempt 上限为 3（首次摄取计为 1），自动退避默认依次为 30 秒和 120 秒。`DISPATCH_MISSING`、`FILE_READ_FAILED`、`PROCESSING_TIMEOUT` 和 `INDEX_TRANSIENT_FAILURE` 可重试；checksum、size、file type、UTF-8 解码和内容/切分校验失败不可重试；未分类错误默认不自动重试。并发 retry 只允许一个新版本成功。每个版本的事件身份不同，不通过复用 eventId 绕过幂等记录。

Reconciler 默认将 PENDING 超过 2 分钟、PROCESSING 超过 15 分钟视为滞留候选；它必须先查询 RAG 当前事实再推进或重试，不能只凭本地时间直接改写终态。阈值可配置，但测试使用更短的显式配置，不修改生产默认值。

## 9. Open API 缓存与查询

Open 使用完整 Key 的 SHA-256 指纹作为缓存键，默认 TTL 30 秒、最大 10,000 项，两者均可配置。缓存值只含 apiKeyId、tenantId、knowledgeBaseId、Key/Scope 版本和过期时间。完整 Key 不写日志、指标、异常或持久化。

Open 无数据库。API Key eviction 是天然幂等且缓存是临时状态，因此使用无 JDBC processed_event 的 Redis Stream 消费模式：重复失效只重复 eviction；进程重启后缓存为空；Malformed 事件进入 DLQ 并 ACK；Redis 不可用时退化为 Access 在线鉴权或既有缓存的短 TTL 窗口。

`API_KEY_INVALIDATED` 可按 apiKeyId 或 knowledgeBaseId 定向清理。Open 维护 Key/Scope 版本水位；缓存 miss 的 Access 响应若早于已观察失效版本，不得重新写入旧缓存。事件到达前的并发窗口由 30 秒 TTL 限制。

RAG Query gRPC 只接受 knowledgeBaseId、question 和公共 metadata。Open 不对 LLM Query 自动重试。RAG 返回 answer 及实际 Context 来源；Open 映射为稳定 HTTP sources。空证据返回稳定回答与空 sources，模型不可用映射为 502。

## 10. 错误、超时与可观测性

HTTP 错误统一为 `Response<ErrorDetail>`。ErrorDetail 包含安全 message、traceId、可选 fieldErrors、稳定 reason 和 retryable。既有业务码保持不变；新增业务码固定为 `40101 UNAUTHENTICATED`、`40102 INVALID_CREDENTIALS`、`40301 FORBIDDEN`、`40901 CONFLICT`、`40902 INGESTION_RETRY_NOT_ALLOWED`、`41301 UPLOAD_TOO_LARGE`、`41501 UNSUPPORTED_MEDIA_TYPE`、`50301 DOWNSTREAM_UNAVAILABLE` 和 `50401 DOWNSTREAM_TIMEOUT`。登录、Refresh 和 API Key 的具体失败原因不得通过 message/reason 泄漏。

gRPC 必须使用稳定 error detail，不解析 message 推断业务语义。每类调用配置 deadline；普通查询短于上传和 Query。只允许幂等查询、EnsureScope 和安全状态探测做有限重试。创建、Membership 变更、Token 轮换、API Key 轮换和 LLM Query 不自动重试。建库部分成功是 HTTP 201，不映射为错误。

系统接收或生成 `X-Request-Id`，贯穿 HTTP、gRPC 和事件 traceId，并写回响应 Header。指标至少覆盖 HTTP 延迟/错误、gRPC 超时、JWT 公钥刷新、上传字节、摄取状态/重试/Reconciler、API Key 缓存命中/失效/旧版本拒绝和 Query 延迟。日志只记录安全 ID、状态、分类和 traceId。

Readiness 区分关键依赖与降级能力。例如 Redis 不可用不应阻止 Open 直连 Access 鉴权；JWT 公钥从未成功加载时 Console 不可接受需要认证的请求。

## 11. Docker 与 Smoke 拓扑

`docker-compose.yml` 只保留 `access-service`、`knowledge-service`、`rag-service` 三个业务服务，不存在 `access-service-smoke`、`knowledge-service-smoke` 或 `rag-service-smoke`。

原服务固定映射本地端口：Access 8091、Knowledge 8092、RAG 8082。默认不启用 smoke Profile，因此端口上没有 `/api/v1/smoke/**` Controller。Docker 回归通过环境变量让原服务启用 smoke Profile 并重建容器；Smoke Controller 作为同一应用的条件 Bean 暴露。Console 8080、Open 8081 是正式业务入口；业务服务端口定义为本地开发/诊断入口，不是产品 API。

## 12. 前端接口文档

Plan21 必须交付：

- `docs/api/console-api.openapi.yaml`
- `docs/api/open-api.openapi.yaml`
- `docs/api/README.md`
- `docs/README.md`

OpenAPI 使用 3.1，所有 operation 具有稳定且唯一的 operationId、请求/响应 Schema、Header/Cookie、HTTP 状态、业务错误码和示例。中文 README 说明登录态、Cookie、Tenant 上下文、分页、上传、轮询、retry、建库部分成功、API Key 一次性秘密、Open Query 和错误处理，并链接对应 Controller、DTO、Adapter、异常映射源码。

自动校验至少覆盖 OpenAPI 可解析、operationId 唯一、引用可解析、示例匹配 Schema、实现路由/方法/状态码与文档清单一致。新增 `docs/api` 与 `docs/README.md` 后，同步包结构、API、Docker、测试约束及根 README，保证后续前端客户端能在同一仓库继续迭代。

## 13. 测试策略

### 13.1 单元与组件

- JWT、公钥刷新、Cookie/Origin、权限映射和 gRPC 错误映射。
- multipart 限制、SHA-256、HTTP 到 gRPC 分片和 backpressure。
- 摄取状态机、operationVersion head、CAS、retry/backoff/reconcile 策略和终态冲突。
- API Key 指纹缓存、版本水位、Key/Scope 定向失效和 Redis 降级。
- MockMvc 正式 HTTP 契约、Provider/Adapter、事件消费者、DAO 和配置绑定。

### 13.2 契约与架构

- Protobuf 字段兼容、稳定错误详情和调用方身份限制。
- OpenAPI 解析、Schema 示例、operationId 和实现路由一致性。
- Console/Open 无数据库、Entity、Repository 或 Service module 依赖。
- contracts 不依赖 Spring/runtime/Service；Smoke Controller 只在 smoke Profile 注册。

### 13.3 Docker HTTP 回归

1. 注册、登录、Refresh 轮换、复用检测、Logout。
2. Tenant/Membership 权限、最后 OWNER 与跨租户隔离。
3. 建库、Scope 同步部分成功、事件补偿和 EnsureScope 兜底。
4. 上传、状态推进、READY、创建 Key、Open Query 和来源映射。
5. FAILED、自动/手动 retry、迟到/乱序/重复事件、Reconciler 和次数上限。
6. API Key disable/enable/rotate/revoke 与缓存主动失效、Redis 降级和 TTL 窗口。
7. 两个 Tenant、KnowledgeBase 和 API Key 之间不能交叉召回。
8. 默认 Profile 下 Smoke 路由不存在；启用后从原服务固定端口访问。

Query 必跑回归使用确定性 LLM Stub。所有脚本使用唯一 runId，不清表、不删除共享 Volume、不执行 `docker compose down -v`。

## 14. 实施切片

1. 补齐 Access/Knowledge/RAG contracts 与稳定错误契约。
2. 完成 Access 查询能力、EnsureScope、KB 创建事件消费与 API Key 列表。
3. 完成 Knowledge 摄取状态投影、状态事件消费和持久化字段。
4. 完成 RAG ingestion head、operationVersion 索引隔离与正式 Query/Status Provider。
5. 完成 retry、超时终态化、Reconciler 和旧版本清理。
6. 完成 Console JWT/Cookie/Auth。
7. 完成 Tenant/Membership HTTP。
8. 完成 KB/Document 上传、状态、retry 编排。
9. 完成 API Key HTTP 编排。
10. 完成 Open 鉴权缓存、失效消费和 Query。
11. 收敛 Compose 单服务 Smoke 拓扑和固定端口。
12. 完成 OpenAPI、中文前端交接文档和代码 reference。
13. 完成全链路 Docker 回归、约束同步和全量验证。

具体任务字段、前置依赖、文件地图、命令和提交边界由 plan21 按 workflow v3 展开。

## 15. 风险与回滚

- 风险：入口层吸收领域规则。通过无数据库、contract-only 依赖、Provider 二次授权和架构测试限制。
- 风险：多版本摄取造成旧数据召回。通过 document_ingestion_head、operationVersion、READY Job 三重过滤和迟到事件测试限制。
- 风险：自动 retry 与 Worker 并发。通过 Knowledge CAS、RAG head CAS、版本化事件和次数上限限制。
- 风险：失效事件与缓存写入竞态。通过版本水位、定向 eviction 和短 TTL 限制。
- 风险：Plan21 跨模块过大。使用 13 个依赖有序、可独立验证和提交的实施切片，前序失败不得被后续任务掩盖。
- 风险：接口文档漂移。OpenAPI 与实现一致性校验纳入 check 和最终验收。

回滚按实施切片逆序执行：先停止入口和消费者，再回滚 HTTP、Provider 和 contracts，最后恢复 Compose。新增兼容字段、表或列可保留为无读取残余，避免破坏已存在本地数据；事件消费者必须先停再回滚 Provider。该设计不迁移旧 Demo 数据，不包含不可逆外部系统操作。

## 16. 关键决策摘要

- 采用两个薄入口，不新增共享业务 Gateway module 或持久化 Saga。
- Access JWT 返回响应体，Refresh Token 只存 HttpOnly Cookie；首版同站部署。
- Console 提供完整可用管理面，删除资源留给 router5。
- Provider 契约缺口在 plan21 内窄补齐，不允许入口依赖 Service 实现。
- 摄取生命周期可靠性完整前移到 router4；router5 聚焦删除生命周期。
- 建库 Scope 允许显式部分成功，并由事件消费与 EnsureScope 双重修复。
- Open 使用 Bearer API Key、30 秒指纹缓存、主动失效和版本水位。
- Open Query 来源是 reference、documentId、excerpt，不暴露内部 Retrieval 结果。
- Smoke 是原服务的 Profile，不是重复 Compose 服务；原业务服务固定暴露本地诊断端口。
- OpenAPI 3.1、中文交接指南和代码 reference 是完成定义，不是可选文档。
