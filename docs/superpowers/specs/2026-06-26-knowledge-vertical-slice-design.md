# CRAG-Demo Knowledge 垂直链路设计

日期：2026-06-26

状态：待确认

范围：router1 / plan18 的 KnowledgeBase、Document、文件上传、文件存储、gRPC 读取、上传事件和 smoke-only 验收入口设计。

## 1. 背景

CRAG-Demo 已完成多服务骨架、Snowflake ID、RAG Service module 收口和可靠事件基础设施。当前 `crag-knowledge-service` 仍只有 Platform Probe 与 plan17 的 smoke 事件闭环，没有真实 KnowledgeBase、Document、文件存储或 Knowledge 领域 gRPC 契约。

总体路线中 `router1` 的交付边界是 Knowledge 垂直链路：Knowledge 侧应能独立创建知识库、接收 `.txt` / `.md` 文件、保存元数据与文件、产出 `DOC_UPLOADED` 事件，并通过 gRPC 向后续 RAG 阶段流式提供文件内容。

## 2. 目标

1. 新增 `crag-knowledge-contracts`，保存 Knowledge 领域 gRPC 契约。
2. 在 Knowledge schema 中新增 KnowledgeBase、Document、FileObject 和真实业务 Outbox 表。
3. 支持 KnowledgeBase 创建、查看和列表。
4. 支持 Document 单次客户端流式上传。
5. 上传 metadata 必须携带原始文件名、文件类型、声明大小和客户端计算的 sha256。
6. 服务端边接收边写临时文件、计算 sha256、统计大小，并校验扩展名、大小、UTF-8 和 sha256。
7. 上传成功后保存 `Document(PENDING)`、`FileObject(STORED)`，并写入真实 `DOC_UPLOADED` Outbox。
8. 启用 Outbox publisher，将 `DOC_UPLOADED` 发布到 Redis Streams；不实现 RAG 消费。
9. 提供 gRPC server streaming 文件读取契约，不泄漏内部 storage key 或路径。
10. 提供 smoke-only HTTP 验收入口，独立证明 PostgreSQL、文件 volume、Redis Streams、gRPC/读取路径和上传校验。
11. 建立 Knowledge 包结构规范，避免 router1 后续腐化。

## 3. 非目标

- 不实现 Access 权限、Tenant Membership 校验、JWT、Refresh Session 或 API Key。
- 不实现 Access `api_key_scope` 注册。
- 不实现 upload token、两阶段 upload session、秒传、断点续传或对象存储直传。
- 不实现 RAG consumer、Ingestion Job、Chunk 构建或状态回传消费。
- 不实现 `PROCESSING / READY / FAILED` 状态回传处理。
- 不实现 Document 或 KnowledgeBase 删除状态机。
- 不实现删除补偿、死信业务处理、孤立文件定时清理或告警平台。
- 不提供正式 Console/Open HTTP API；HTTP 只作为 `smoke` Profile 下的验收入口。
- 不迁移旧 Demo 数据。

## 4. 架构边界

plan18 是 Knowledge 自身的垂直闭环，不跨入 Access、RAG 或正式 API 编排。

新增模块：

- `crag-knowledge-contracts`：Knowledge 领域 Protobuf 与生成代码。

已有模块职责：

- `crag-platform-contracts`：继续只保存跨领域通用契约，例如 Platform Probe；不得放入 Knowledge RPC。
- `crag-knowledge-service`：实现 Knowledge 领域模型、DAO、文件存储、gRPC provider、事件 producer 和 smoke-only HTTP 入口。
- `crag-event`：继续只提供领域无关可靠事件基础设施；不得放入 `DOC_UPLOADED` 等业务事件语义。
- `crag-console-api`、`crag-open-api`、`crag-rag-service`、`crag-access-service`：不在本阶段实现 Knowledge 业务调用。

模块依赖方向：

```text
crag-knowledge-service
  -> crag-knowledge-contracts
  -> crag-platform-contracts
  -> crag-grpc-runtime
  -> crag-common
  -> crag-event
  -> crag-id
```

`crag-knowledge-contracts` 不依赖 Spring、runtime 或业务 service module。

## 5. 包结构规范

`crag-knowledge-service` 使用以下包结构作为 plan18 及后续 Knowledge 代码规范：

```text
ai.cerbur.crag.knowledge
├── KnowledgeServiceApplication
├── controller/
│   └── smoke/
├── core/
│   ├── knowledgebase/
│   ├── document/
│   └── file/
├── dao/
│   ├── KnowledgeBaseDao
│   ├── DocumentDao
│   ├── FileObjectDao
│   ├── entity/
│   └── repository/
├── filestore/
├── grpc/
│   ├── provider/
│   ├── mapper/
│   └── error/
├── producer/
├── consumer/
└── probe/
```

执行规则：

- `KnowledgeServiceApplication` 直接位于 `ai.cerbur.crag.knowledge`，不新增 `app` 包。
- Java HTTP 入口统一使用 `controller` 命名。plan18 只允许 `controller.smoke`，且必须受 `@Profile("smoke")` 限制。
- 业务用例与核心规则统一放在 `core`，不用 `application` 命名。
- `core` 按业务能力分包，承载 KnowledgeBase、Document、文件读取等用例服务、命令对象、结果对象和核心规则。
- `dao` 是数据库访问唯一边界。包根放 `*Dao`，`entity/` 放持久化实体，`repository/` 放 Spring Data Repository。
- `dao` 不新增 `result` 包。RAG 当前 `storage.result` 属历史偏差，不作为 Knowledge 新代码范式。
- `dao` 不依赖 `core`、`grpc`、`controller`、`producer`、`filestore` 或其他外部业务/入口/基础设施包。
- `dao` 可以向外返回 Entity 或 Entity 列表；外部如果需要业务结果、proto 或 HTTP DTO，由外部 mapper/converter 转换。
- Repository 只允许 DAO 调用；`core`、`grpc`、`controller`、`producer` 禁止直接依赖 Repository。
- `core` 可以依赖 DAO 和 Entity，但不能把 Entity 直接返回到 `controller` 或 `grpc`；入口层应接收 core result 或 mapper 转换后的结构。
- `filestore` 独立于 `dao`；`dao` 表示数据库访问，`filestore` 表示文件系统或对象存储访问。
- `grpc.provider` 只实现 contracts 定义的 RPC 暴露；业务实现位于 `core`。
- `grpc.mapper` 负责 core result 与 proto 转换；`grpc.error` 负责 gRPC 错误映射。
- MQ/事件按方向命名为 `producer` / `consumer`。plan18 只实现 `producer` 发布 `DOC_UPLOADED`；不创建空 consumer 实现。
- `controller.smoke` 与 `grpc.provider` 必须复用同一组 `core` service，不复制业务逻辑。

## 6. 数据模型

### 6.1 `knowledge_base`

字段：

- `knowledge_base_id` BIGINT 主键。
- `tenant_id` BIGINT，必填。
- `name` 文本展示名，必填。
- `created_by_user_id` BIGINT，必填。
- `status`，首版只实现 `ACTIVE`。
- `created_at`。
- `updated_at`。
- `version`。

约束：

- KnowledgeBase 归 Tenant 所有。
- 名称只作展示，首版允许同 Tenant 内重名。
- 查询必须携带 `tenantId`，避免跨租户泄漏。

### 6.2 `document`

字段：

- `doc_id` BIGINT 主键。
- `knowledge_base_id` BIGINT，必填。
- `tenant_id` BIGINT，必填。
- `uploaded_by_user_id` BIGINT，必填。
- `original_filename`，只作展示，不参与存储路径拼接。
- `file_type`，首版支持 `TXT`、`MARKDOWN`。
- `size_bytes`。
- `sha256`。
- `ingestion_status`，首版创建时为 `PENDING`。
- `operation_version`，首版上传创建时为 `1`。
- `created_at`。
- `updated_at`。
- `version`。

约束：

- Document 内容不可变。
- 上传校验失败不得创建 Document。
- `PROCESSING / READY / FAILED` 由后续 RAG 状态回传阶段引入或消费。

### 6.3 `file_object`

字段：

- `file_object_id` BIGINT 主键。
- `doc_id` BIGINT，必填。
- `storage_key`，Knowledge 内部使用，不出现在跨服务契约、HTTP DTO 或日志中。
- `size_bytes`。
- `sha256`。
- `storage_status`，首版只实现 `STORED`。
- `created_at`。
- `updated_at`。
- `version`。

约束：

- 文件存储名由服务端生成，禁止拼接原始文件名。
- 文件路径不得出现在 gRPC、HTTP 响应、事件 payload 或业务日志中。

### 6.4 `outbox_event` 与 `processed_event`

- `outbox_event` 作为 Knowledge schema 的真实业务 Outbox 宿主，结构应满足 `crag-event` publisher 的字段需求。
- plan17 的 smoke 表可作为实现参考，但 plan18 要将 Knowledge schema 中的 Outbox 收敛为真实业务版本。
- `processed_event` 可创建为后续 Knowledge consumer 的幂等表；plan18 不实现真实 consumer。
- Outbox 与 processed event 仍属于 Knowledge 本地 schema，不建立全局 event schema。

## 7. 文件上传流程

### 7.1 gRPC 客户端流式上传

`UploadDocument` 使用客户端流式 RPC。

首个 message 是 metadata，包含：

- `tenantId`
- `knowledgeBaseId`
- `uploadedByUserId`
- `originalFilename`
- `contentType` 或文件类型声明
- `sizeBytes`
- `sha256`

后续 message 是 bytes chunk。

### 7.2 服务端处理

1. 校验 metadata 必填字段。
2. 校验原始文件名扩展名，仅允许 `.txt`、`.md`。
3. 校验声明大小不超过默认 10 MiB。
4. 校验 `knowledgeBaseId` 属于 `tenantId`。
5. 流式接收 bytes，写入临时文件，同时计算 sha256 并统计实际大小。
6. 接收完成后校验实际大小等于声明大小。
7. 校验实际 sha256 等于客户端声明 sha256。
8. 校验文件内容为 UTF-8 文本。
9. 将临时文件原子移动到最终 storage key。
10. 在数据库事务中创建 Document、FileObject 和 `DOC_UPLOADED` Outbox。
11. 返回 Document 元数据。

校验或事务失败时：

- 不创建业务记录。
- 清理本次临时文件或最终文件。
- 返回稳定错误，不泄漏内部路径或 SQL 细节。

进程在落盘后、事务前崩溃导致的孤立文件由后续生命周期可靠性阶段处理；plan18 只要求 storage key 可识别，并在可控失败路径中主动清理。

## 8. gRPC 契约

`crag-knowledge-contracts` 首版定义：

- `KnowledgeBaseService`
  - `CreateKnowledgeBase`
  - `GetKnowledgeBase`
  - `ListKnowledgeBases`
- `DocumentService`
  - `UploadDocument`
  - `GetDocument`
  - `ListDocuments`
  - `ReadDocumentFile`

契约规则：

- 边界 ID 使用十进制字符串。
- `UploadDocument` 为客户端流式。
- `ReadDocumentFile` 为 server streaming。
- `ReadDocumentFile` 输入包含 `tenantId`、`knowledgeBaseId`、`docId`。
- 文件读取响应可包含安全 metadata 与 bytes chunk，但不得包含 `storageKey` 或路径。
- 错误使用明确 gRPC status 与稳定业务错误详情，不透传 SQL、堆栈或内部路径。

## 9. 事件设计

上传成功后，Knowledge 在同一事务中保存：

- `Document(PENDING)`
- `FileObject(STORED)`
- `DOC_UPLOADED` Outbox

`DOC_UPLOADED` payload 包含：

- `tenantId`
- `knowledgeBaseId`
- `docId`
- `operationVersion`
- `fileType`
- `sizeBytes`
- `sha256`

payload 不包含：

- 文件路径。
- storage key。
- 原始文件内容。
- Prompt、Context 或其他未来 RAG 内部数据。

plan18 启用 Outbox publisher，将 `DOC_UPLOADED` 发布到 Redis Streams。RAG 消费、Ingestion Job 创建、状态回传和消费幂等处理留给 router2。

## 10. Smoke-only HTTP 验收入口

`controller.smoke` 提供最小 HTTP 端点，用于 Docker 回归证明真实链路：

- 创建 KnowledgeBase。
- 查询 KnowledgeBase。
- multipart 上传 `.txt` / `.md`。
- 查询 Document。
- 读取 Document 文件内容。
- 查询 outbox 或事件发布诊断状态。

规则：

- 所有 smoke controller 必须位于 `controller.smoke`。
- 所有 smoke controller 必须受 `@Profile("smoke")` 限制。
- 默认 profile 不得暴露 Knowledge smoke HTTP 端点。
- smoke HTTP 入口只做协议适配，必须复用 `core` service。
- smoke HTTP 不作为正式 Console API，不写入产品 API 边界。

## 11. 错误处理

- 上传校验失败不创建 `document`、`file_object` 或 `outbox_event`。
- metadata 缺失、空文件、非法扩展名、非法文件类型、超出大小上限、大小不匹配、sha256 不匹配、非 UTF-8 均返回稳定错误。
- `knowledgeBaseId` 不属于 `tenantId` 时返回 permission-safe 的 not found 类错误，不泄漏跨租户资源存在性。
- 文件已写入但数据库事务失败时，清理本次文件。
- Outbox 发布失败不回滚上传事务；Outbox 状态进入 retry，等待 publisher 重试。
- gRPC 上传或读取中断按整文件重试处理，不做断点续传。
- 日志禁止输出完整文件内容、内部 storage path、完整 payload 或敏感请求头。
- 日志可以记录 ID、大小、sha256、事件 ID、trace ID 和失败分类。

## 12. 测试与验证计划

### 12.1 纯单元测试

- 文件扩展名与文件类型校验。
- sha256 格式、大小声明和实际统计校验。
- UTF-8 校验。
- storage key 生成，不包含原始文件名。
- Document / FileObject 状态枚举。
- `DOC_UPLOADED` payload 组装。
- core service 在 DAO、filestore、producer fake 下的正常与失败分支。

### 12.2 轻量组件测试

- H2 下 DAO insert/query/CAS/`updated_at` 行为。
- Repository 只由 DAO 调用的组件或架构约束。
- 上传服务事务失败时清理临时/最终文件。
- gRPC provider 在受控替身下完成 proto/core 映射。
- smoke controller multipart 校验和默认 profile 禁用。

H2 仅证明 DAO 行为与 Spring 装配，不表述为 PostgreSQL 方言兼容证明。

### 12.3 架构测试

- `crag-knowledge-contracts` 不依赖 Spring、runtime 或 service module。
- `crag-knowledge-service` 不依赖 Access 或 RAG service。
- `dao.repository` 只允许 `dao` 包访问。
- `dao` 不依赖 `core`、`grpc`、`controller`、`producer`、`filestore`。
- `controller.smoke` 必须受 `smoke` Profile 限制。
- `grpc.provider` 与 `controller.smoke` 不直接依赖 Repository。
- Proto、HTTP DTO 和事件 payload 不包含 storage key/path 字段。

### 12.4 Docker HTTP 回归

新增或更新脚本位于 `scripts/tests/http/`，通过 `knowledge-service-smoke` 证明：

1. 默认 profile 不暴露 Knowledge smoke 端点。
2. 创建 KnowledgeBase 成功落库。
3. 上传 `.txt` 成功，Document 为 `PENDING`，文件可读回。
4. 上传 `.md` 成功，Document 为 `PENDING`，文件可读回。
5. `DOC_UPLOADED` Outbox 发布到 Redis Streams。
6. sha256 不匹配上传失败且不创建 Document。
7. 非 UTF-8 上传失败且不创建 Document。
8. 非法扩展名上传失败且不创建 Document。
9. 超 10 MiB 上传失败且不创建 Document。

Docker 回归使用唯一 `runId`，不得清空共享表、删除 volume 或影响其他 Plan 数据。

### 12.5 完成前验证

- `./gradlew spotlessCheck`
- `./gradlew check`
- `python3 scripts/validate_plans.py`
- `python3 -m unittest scripts.tests.test_validate_module_dependencies scripts.tests.test_validate_constraints -v`
- 相关 Docker HTTP 回归脚本。

## 13. 风险与回滚

- 风险：plan18 过度扩大到权限、RAG 或删除流程。控制措施是明确非目标，所有跨服务消费和生命周期清理留给后续 router。
- 风险：文件上传失败留下孤立文件。控制措施是可控失败路径主动清理，崩溃残留记录为后续 Reconciler 范围。
- 风险：两阶段 upload token 提前引入临时鉴权体系。控制措施是 plan18 只做单次流式上传和 sha256 强校验。
- 风险：smoke HTTP 被误认为正式 API。控制措施是 `controller.smoke` + `@Profile("smoke")` + 默认 profile 禁用回归。
- 风险：DAO 分层再次滑向跨层适配。控制措施是禁止 `dao.result`，DAO 只管理数据库访问，转换放到外部 mapper/core。
- 风险：Outbox 发布失败被误判为上传失败。控制措施是上传事务与发布解耦，发布失败进入 retry 并暴露诊断。

回滚：

- 本阶段不包含不可逆业务数据迁移要求。可 revert `crag-knowledge-contracts`、Knowledge 表、filestore、gRPC provider、producer、smoke controller、Compose smoke 配置和约束文档改动。
- 本地 Docker 数据若残留 plan18 测试记录，可通过 runId 定位；普通回滚不要求删除共享 volume。

## 14. 未来演进

- 两阶段上传会话：`INITIATED / UPLOADING / STORED / FAILED / EXPIRED`、upload token、过期清理、重复提交保护和对象存储直传。
- RAG router2 消费 `DOC_UPLOADED`，创建 Ingestion Job，并通过 Knowledge gRPC 读取文件。
- RAG 状态回传后，Knowledge consumer 更新 `PROCESSING / READY / FAILED`。
- Access router3 提供 Tenant Membership、JWT、API Key 和 `api_key_scope`。
- Console router4 编排正式 HTTP 上传与权限校验。
- router5 实现删除状态机、deletion guard、补偿扫描、死信诊断、告警和孤立文件清理。
