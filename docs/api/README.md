# CRAG-Demo API 前端交接指南

> 本目录是同仓库前端与外部调用方的 HTTP 契约入口。两份 OpenAPI 3.1 文档与本文档共同构成 router4 正式 API 的完整交接。**本文档与 OpenAPI 文档由实现事实维护，不由设计文档猜测**；任何路由、状态码、字段或 Cookie 属性变化必须同步修改对应文件并由 `scripts/validate_openapi.py` 校验。
>
> 生成客户端：`docs/api/console-api.openapi.yaml` 与 `docs/api/open-api.openapi.yaml` 均为 OpenAPI 3.1 文档（文件后缀 `.yaml`，语法为 JSON —— JSON 是 YAML 1.2 的超集，任何标准 OpenAPI 3.1 工具均可消费）。用 openapi-generator、`@hey-api/openapi-ts` 或 `openapi-typescript-codegen` 直接生成类型安全客户端即可。

## 1. 两个入口与端口

| 入口 | 模块 | 本地端口 | 角色 | OpenAPI 文档 |
| --- | --- | --- | --- | --- |
| Console API | `crag-console-api` | 8080 | 浏览器管理面：Auth/Tenant/Membership/KnowledgeBase/Document/API Key | [console-api.openapi.yaml](./console-api.openapi.yaml) |
| Open API | `crag-open-api` | 8081 | 外部调用方：单 KB API Key 问答 | [open-api.openapi.yaml](./open-api.openapi.yaml) |

两个入口都是无数据库的薄入口，只通过 Access/Knowledge/RAG gRPC contracts 编排下游 Provider，本身不保存业务数据。

## 2. 登录态与凭证存储

Console 的认证模型由 [AuthController](../../crag-console-api/src/main/java/ai/cerbur/crag/console/auth/controller/AuthController.java) 与 [RefreshCookieService](../../crag-console-api/src/main/java/ai/cerbur/crag/console/auth/service/RefreshCookieService.java) 实现：

- **Access JWT 只进响应体**：register/login/refresh 返回 `result.accessToken`，客户端后续以 `Authorization: Bearer <jwt>` 发起业务请求。
- **Refresh Token 只进 HttpOnly Cookie**：服务端通过 `Set-Cookie` 下发 `refresh_token`，前端 JavaScript 不可读取。
- Cookie 固定属性：`HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth`，不设置 `Domain`。本地 HTTP 必须通过 `crag.console.cookie.secure=false` 显式关闭 `Secure`，正式配置不得静默降级。
- register 额外返回默认 Tenant（`result.defaultTenant`）；login/refresh 的 `defaultTenant` 为 `null`，客户端通过 `GET /api/v1/tenants` 恢复工作上下文。

JWT 在 Console 本地验签（RS256），只建立 `userId/sessionFamilyId` 请求上下文，不信任 Tenant 或角色；Tenant 级读写调用 Access 实时授权。未认证返回 401（`40101 UNAUTHENTICATED`），已认证但无权返回 403（`40301 FORBIDDEN`），跨 Tenant 资源查询统一返回 404（不泄漏存在性）。

## 3. 同站 Origin 与 CSRF 策略

refresh/logout 在 [OriginGuard](../../crag-console-api/src/main/java/ai/cerbur/crag/console/auth/service/OriginGuard.java) 校验同站 `Origin`/`Referer`，不开放通配 CORS，不引入前端可读 CSRF Token。logout 即使 Origin 校验失败或下游 Access 不可达，也在 `finally` 中以 `Max-Age=0` 清除本地 Cookie。

## 4. Tenant 上下文与分页

Console 的所有 Tenant/Membership/KnowledgeBase/Document/ApiKey 操作都把 `actorUserId` 锁定为 Bearer filter 注入的 [ConsolePrincipal](../../crag-console-api/src/main/java/ai/cerbur/crag/console/security/jwt/ConsolePrincipal.java)，不接受请求体或查询参数覆盖，防越权。

分页统一使用：

- 请求：`pageSize`（1–100，默认 20）+ `pageToken`（首次为空字符串）。
- 响应：`items` + `nextPageToken`（无更多数据时为空字符串；API Key 列表为 `null`）。

参考：[TenantController](../../crag-console-api/src/main/java/ai/cerbur/crag/console/tenant/controller/TenantController.java)、[MembershipController](../../crag-console-api/src/main/java/ai/cerbur/crag/console/membership/controller/MembershipController.java)。

### 4.1 Membership 列表的 nickname

Membership list（`GET /api/v1/tenants/{tenantId}/members`）返回的 `MemberResponse.nickname` 由 Access 在服务端批量补齐（plan_21/21.7 已修复：`Membership` proto 追加 `nickname` 字段，`MembershipService.list` 用批量用户查询补齐，非 N+1）。单成员命令（add/change-role/remove）通过单用户 `GetUserProfile` 解析 nickname。前端可直接展示 list 返回的 nickname。

## 5. KnowledgeBase 创建与 Scope 部分成功

建库由 [KnowledgeBaseOrchestrator](../../crag-console-api/src/main/java/ai/cerbur/crag/console/knowledge/service/KnowledgeBaseOrchestrator.java) 编排：`Authorize(CREATE_KNOWLEDGE_BASE) → Knowledge Create → Access EnsureScope`。

- 成功返回 **HTTP 201** + `apiKeyReady=true`。
- Access `EnsureScope` 暂时失败（`UNAVAILABLE` / `FAILED_PRECONDITION`）时**仍返回 201** + `apiKeyReady=false`，不二次 create，也不假装跨服务回滚。
- `apiKeyReady=false` 的 KB 可以先建库与上传文档，但**不能立即创建 API Key**；Access `KB_CREATED` consumer 会幂等补齐 Scope，前端可轮询 `GET /api/v1/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}` 直到 `apiKeyReady=true`，或在创建 API Key 前 Console 内部再 `EnsureScope` 兜底一次。

## 6. Document 上传、轮询与重试

Document 由 [DocumentController](../../crag-console-api/src/main/java/ai/cerbur/crag/console/document/controller/DocumentController.java) 与 [KnowledgeDocumentClient](../../crag-console-api/src/main/java/ai/cerbur/crag/console/document/service/KnowledgeDocumentClient.java) 承接：

- **上传**（`POST .../documents`）：multipart 单文件，只接受 `.txt`/`.md`，10 MiB 上限，UTF-8。Console 计算 size/SHA-256 后以 metadata-first 分片流式上传 Knowledge。成功返回 **HTTP 202** + `ingestionStatus=PENDING`（底层 Knowledge 内 Document 创建为 201，但 Console 对外契约是 202）。
- **轮询**：客户端用 `GET .../documents/{docId}` 轮询，直到 `ingestionStatus` 进入终态 `READY` 或 `FAILED`。
- **重试**（`POST .../documents/{docId}/ingestion/retry`）：只有可重试分类且未达次数上限（默认总 attempt 上限 3）允许；返回 200 与新 `operationVersion` 的 `PENDING` 投影。不可重试返回 `40902 INGESTION_RETRY_NOT_ALLOWED` / HTTP 409。

### 6.1 摄取状态字段与 retryable 语义

`DocumentResponse` 字段（见 [DocumentResponse](../../crag-console-api/src/main/java/ai/cerbur/crag/console/document/dto/DocumentResponse.java)）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `ingestionStatus` | `PENDING`/`PROCESSING`/`READY`/`FAILED` | 当前 `operationVersion` 的状态 |
| `operationVersion` | 十进制字符串 | 摄取操作版本；retry 创建新版本 |
| `attempt` | integer | 当前版本尝试次数；首次摄取为 1，总上限 3 |
| `failureCategory` | string \| null | 失败分类；非 FAILED 时可能为空字符串 |
| `failureMessage` | string \| null | 安全限长失败描述；不含文件内容或堆栈 |
| `retryable` | boolean | Console 根据 FAILED + 可重试分类 + 未达上限推导；权威决策由 Knowledge `RetryIngestion` gRPC 给出 |
| `startedAt` / `completedAt` | RFC 3339 UTC \| null | 当前版本起止时间 |

可重试分类：`DISPATCH_MISSING`、`FILE_READ_FAILED`、`PROCESSING_TIMEOUT`、`INDEX_TRANSIENT_FAILURE`（自动退避 30s、120s）。不可重试：checksum/size/file type/UTF-8 解码、内容/切分校验失败、未分类错误、达到 attempt 上限。

## 7. API Key 一次性秘密

API Key 由 [ApiKeyController](../../crag-console-api/src/main/java/ai/cerbur/crag/console/apikey/controller/ApiKeyController.java) 与 [ApiKeyOrchestrator](../../crag-console-api/src/main/java/ai/cerbur/crag/console/apikey/service/ApiKeyOrchestrator.java) 管理，嵌套在 KB 路径下：

- 只有 **OWNER** 可管理（Access `PERMISSION_DENIED` → 403）。
- **完整 Key（`completeKey`）只在 `create` 与 `rotate` 响应中返回一次**，类型是 [CreatedApiKeyResponse](../../crag-console-api/src/main/java/ai/cerbur/crag/console/apikey/dto/CreatedApiKeyResponse.java)。前端必须立即保存（如写入密钥管理器），此后不可再读；`toString()` 屏蔽 `completeKey`，日志/异常/错误响应一律不含完整 Key。
- 列表/详情/disable/enable/revoke 只返回 [ApiKeyResponse](../../crag-console-api/src/main/java/ai/cerbur/crag/console/apikey/dto/ApiKeyResponse.java) 的 `keyPrefix`（可检索前缀），**绝不**复用 `CreatedApiKeyResponse`。
- 状态机：`ACTIVE → DISABLED → ACTIVE`（enable）、`ACTIVE/DISABLED → REVOKED`（终态）。状态冲突（disable 已 DISABLED、revoke 已 REVOKED、rotate 非 ACTIVE）→ `40901 CONFLICT` / 409。
- `revoke` 与 `rotate` 同事务写 `API_KEY_INVALIDATED` 失效事件，经 Redis Streams 发布；Open 缓存（21.10）在收到事件后主动 evict。

## 8. Open Query（外部调用方）

Open Query 由 [QueryController](../../crag-open-api/src/main/java/ai/cerbur/crag/open/query/controller/QueryController.java) 与 [OpenQueryService](../../crag-open-api/src/main/java/ai/cerbur/crag/open/query/service/OpenQueryService.java) 承接：

- **只从 `Authorization: Bearer crag_...` 读取 Key**（[BearerApiKeyExtractor](../../crag-open-api/src/main/java/ai/cerbur/crag/open/auth/service/BearerApiKeyExtractor.java)）。
- **请求体只有 `question`**（去除首尾空白后 1–2000 Unicode 字符）；**不接受** `tenantId` / `knowledgeBaseId`（由 Key 决定）。
- Open 用完整 Key 的 **SHA-256 指纹**做缓存键，默认 TTL 30s、最大 10000 项（[ApiKeyAuthCache](../../crag-open-api/src/main/java/ai/cerbur/crag/open/authcache/)）。完整 Key 不写日志/指标/异常/缓存值。
- Open 维护 Key/Scope 版本水位；缓存 miss 的 Access 响应若早于已观察失效版本不会回写旧缓存。Redis 不可用时降级为 Access 在线鉴权 + 既有缓存的短 TTL 窗口。
- 响应只含 `answer` 与 `sources`。`sources` 项是 [CitationResponse](../../crag-open-api/src/main/java/ai/cerbur/crag/open/query/dto/CitationResponse.java)：`reference`（与 answer 中 `[S1]` 引用连续对应）+ `documentId`（十进制字符串）+ `excerpt`（≤ 500 Unicode 字符防御截断）。**不暴露** chunk id、分数、Prompt 或 Context。
- Open **不对 LLM Query 自动重试**；LLM 不可用映射为 `50201 LLM_UNAVAILABLE` / HTTP 502（`retryable=true`）。

## 9. 统一错误处理

所有错误响应是 `Response<ErrorDetail>`，由 Console [GlobalExceptionHandler](../../crag-console-api/src/main/java/ai/cerbur/crag/console/advice/GlobalExceptionHandler.java) 与 Open [GlobalExceptionHandler](../../crag-open-api/src/main/java/ai/cerbur/crag/open/advice/GlobalExceptionHandler.java) 统一构造（共享 crag-common 的 [ErrorDetail](../../crag-common/src/main/java/ai/cerbur/crag/common/dto/error/ErrorDetail.java) / [FieldErrorDetail](../../crag-common/src/main/java/ai/cerbur/crag/common/dto/error/FieldErrorDetail.java) / [ResponseCode](../../crag-common/src/main/java/ai/cerbur/crag/common/dto/result/ResponseCode.java)）。

`ErrorDetail` 字段：`message`（安全稳定说明，不泄漏凭据失败原因/堆栈/下游原始报错）、`traceId`（贯穿 HTTP/gRPC/事件，来自 `X-Request-Id` 或服务端生成的 UUID）、`reason`（稳定机器可读原因标签）、`retryable`、`fieldErrors`。敏感字段（密码/Token/Key）的 `fieldErrors[].rejectedValue` 一律为 `null`。

### 9.1 业务码表

| 业务码 | 枚举 | HTTP | 默认消息 |
| --- | --- | --- | --- |
| 0 | SUCCESS | 200 | Success |
| 40001 | VALIDATION_ERROR | 400 | Validation failed |
| 40002 | INVALID_ARGUMENT | 400 | Invalid argument |
| 40101 | UNAUTHENTICATED | 401 | Unauthenticated |
| 40102 | INVALID_CREDENTIALS | 401 | Invalid credentials |
| 40301 | FORBIDDEN | 403 | Forbidden |
| 40401 | NOT_FOUND | 404 | Resource not found |
| 40901 | CONFLICT | 409 | Conflict |
| 40902 | INGESTION_RETRY_NOT_ALLOWED | 409 | Ingestion retry not allowed |
| 41301 | UPLOAD_TOO_LARGE | 413 | Upload too large |
| 41501 | UNSUPPORTED_MEDIA_TYPE | 415 | Unsupported media type |
| 50001 | INTERNAL_ERROR | 500 | Internal server error |
| 50201 | LLM_UNAVAILABLE | 502 | LLM unavailable |
| 50301 | DOWNSTREAM_UNAVAILABLE | 503 | Downstream unavailable |
| 50401 | DOWNSTREAM_TIMEOUT | 504 | Downstream timeout |

业务码独立稳定，不复用 HTTP 状态码数值语义。`Response.code=0` 表示成功；非 0 为错误码。登录/Refresh/API Key 的具体失败原因**不**通过 `message`/`reason` 泄漏（统一返回 `Authentication failed` + 通用 reason）。

## 10. 生成客户端与契约校验

```bash
# 生成 Console 客户端（示例：openapi-generator-cli）
openapi-generator-cli generate -i docs/api/console-api.openapi.yaml -g typescript-fetch -o ./frontend/console-client

# 生成 Open 客户端
openapi-generator-cli generate -i docs/api/open-api.openapi.yaml -g typescript-fetch -o ./frontend/open-client

# 契约校验（解析、openapi=3.1、operationId 唯一、$ref 可解析、示例匹配、路由清单漂移、Markdown 链接）
python3 scripts/validate_openapi.py
python3 -m unittest scripts.tests.test_validate_openapi
```

契约校验已纳入根 Gradle `check`（见 `build.gradle.kts` 的 `validateOpenApi` 任务，plan_21/21.12 新增）。前端 Pull Request 必须保证校验 0 error。

## 11. 常见联调陷阱

1. **跨站 Origin**：本地前端联调时务必把 `Origin` 头设为 Console 允许的同站值；缺失或跨站 refresh/logout 会得到 `40301 FORBIDDEN` / `reason=CROSS_SITE_ORIGIN`。
2. **`apiKeyReady=false` 时建 API Key**：会因 Scope 未就绪在编排内部降级或失败；前端应等待 `apiKeyReady=true` 或提示稍后重试，不要重试建库导致重复 KB。
3. **完整 Key 只有一次**：rotate/create 后未保存即丢失，只能再次 rotate/create；前端必须立即落库并隐藏显示。
4. **上传不是 201**：Document upload 对外契约是 **202**（PENDING），不是 201；底层 Knowledge Document 创建的 201 不暴露给前端。
5. **Open 请求体不含定位参数**：任何 `tenantId`/`knowledgeBaseId` 字段都会被忽略；定位完全由 API Key 决定。
6. **List nickname 已补齐**：见 §4.1，list 由 Access 批量补齐 nickname（plan_21/21.7 修复），前端可直接展示。
