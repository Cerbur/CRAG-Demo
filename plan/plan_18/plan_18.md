---
workflow_version: 3
plan_id: plan_18
type: main
status: completed
created: 2026-06-26
updated: 2026-06-26
---

# plan_18 — Knowledge 垂直链路

> **For agentic workers:** 执行本计划必须先读取 `skill/execute-crag-plan/SKILL.md`；实现步骤使用测试先行、任务级提交和独立验收交接。

**Goal**：落地 router1 的 Knowledge 垂直链路，支持 KnowledgeBase、Document、文件上传、文件存储、gRPC 流式读取、`DOC_UPLOADED` 事件和 smoke-only Docker 验收。

**Architecture**：新增 `crag-knowledge-contracts` 保存 Knowledge 领域 gRPC 契约；`crag-knowledge-service` 按 `core / dao / filestore / grpc / producer / controller.smoke` 分包实现业务。Knowledge 只产出上传事实和读取能力，不消费 RAG、不做 Access 权限、不做删除状态机。

**Tech Stack**：Java 21、Spring Boot 4.1.0、Spring Framework 7、Gradle 9.4.1、gRPC + Protobuf、Spring JDBC / Spring Data JPA、PostgreSQL 17、Redis Streams、Docker Compose、`crag-event`、`crag-id`。

## 全局实现约束

- 设计事实来源：`docs/superpowers/specs/2026-06-26-knowledge-vertical-slice-design.md`，设计提交 `a2259b0e`。
- 新增 `crag-knowledge-contracts`；Knowledge 领域 proto 不得放入 `crag-platform-contracts`。
- `crag-knowledge-contracts` 禁止依赖 Spring、runtime 或任何 service module。
- `crag-knowledge-service` 可以依赖 `crag-knowledge-contracts`、`crag-platform-contracts`、`crag-grpc-runtime`、`crag-common`、`crag-event`、`crag-id`。
- `crag-knowledge-service` 禁止依赖 `crag-access-service`、`crag-rag-service`、`crag-console-api` 或 `crag-open-api`。
- Knowledge Application 主类直接位于 `ai.cerbur.crag.knowledge`；不新增 `app` 包。
- Java HTTP 入口统一使用 `controller` 命名；plan18 只允许 `controller.smoke`，且必须受 `@Profile("smoke")` 限制。
- 业务用例与核心规则统一放在 `core`，不用 `application` 命名。
- `dao` 是数据库访问唯一边界，包根放 `*Dao`，`entity/` 放持久化实体，`repository/` 放 Spring Data Repository。
- `dao` 不新增 `result` 包；RAG 当前 `storage.result` 属历史偏差，不作为 Knowledge 新代码范式。
- `dao` 不依赖 `core`、`grpc`、`controller`、`producer`、`filestore` 或其他外部业务/入口/基础设施包。
- Repository 只允许 DAO 调用；`core`、`grpc`、`controller`、`producer` 禁止直接依赖 Repository。
- `filestore` 独立于 `dao`；`dao` 表示数据库访问，`filestore` 表示文件系统或对象存储访问。
- `grpc.provider` 只实现 contracts 定义的 RPC 暴露；业务实现位于 `core`。
- MQ/事件按方向命名为 `producer` / `consumer`；plan18 只实现 `producer` 发布 `DOC_UPLOADED`，不创建空 consumer 实现。
- `controller.smoke` 与 `grpc.provider` 必须复用同一组 `core` service，不复制业务逻辑。
- Document 上传采用单次客户端流式上传；metadata 必须携带原始文件名、文件类型、声明大小和客户端计算的 sha256。
- 服务端必须边接收边写临时文件、计算 sha256、统计大小，并校验扩展名、大小、UTF-8 和 sha256。
- 上传成功后保存 `Document(PENDING)`、`FileObject(STORED)`，并写入真实 `DOC_UPLOADED` Outbox。
- `DOC_UPLOADED` payload 不得包含文件路径、storage key、原始文件内容、Prompt 或 Context。
- plan18 不实现 Access 权限、Tenant Membership 校验、JWT、API Key、upload token、两阶段 upload session、RAG consumer、Ingestion Job、状态回传消费、删除状态机、补偿扫描或正式 Console/Open HTTP API。
- Java 代码遵守 `constraints/code-style.md`；持久化遵守 `constraints/persistence-style.md`；HTTP smoke 边界遵守 `constraints/api-style.md`；测试遵守 `constraints/test-workflow.md`。

## 背景与目标

当前仓库已经完成五进程服务骨架、独立 schema、Snowflake ID、RAG Service module 收口和可靠事件基础设施。`crag-knowledge-service` 目前只有 Platform Probe 与 plan17 的 smoke 事件闭环，没有真实 KnowledgeBase、Document、文件存储或 Knowledge 领域 gRPC 契约。

router1 需要把 Knowledge 侧第一个业务垂直切片打穿：Knowledge 能独立创建知识库、上传 `.txt` / `.md` 文件、保存元数据与文件、产出 `DOC_UPLOADED` 事件，并通过 gRPC 为后续 router2/RAG 流式提供文件内容。plan18 以 smoke-only HTTP 入口完成 Docker 级验收，但不把该入口定义为正式业务 API。

## 范围

- 新增 `crag-knowledge-contracts` Gradle module 与 Knowledge 领域 proto。
- 更新模块依赖白名单、包结构约束和静态校验器，使 `crag-knowledge-contracts` 与 Knowledge 包规范可被验证。
- 将 `KnowledgeServiceApplication` 移到 `ai.cerbur.crag.knowledge` 根包。
- 在 Knowledge schema 中新增 `knowledge_base`、`document`、`file_object`、真实业务 `outbox_event` 和后续 consumer 预留的 `processed_event`。
- 实现 Knowledge DAO、Entity、Repository 和数据库初始化。
- 实现 KnowledgeBase 创建、查看和列表。
- 实现 Document 单次客户端流式上传的 core 服务、文件校验和本地文件存储。
- 实现 `Document(PENDING)`、`FileObject(STORED)` 与 `DOC_UPLOADED` Outbox 的同事务创建。
- 实现 Knowledge gRPC provider：KnowledgeBase 创建/查询/list，Document 上传/查询/list，Document 文件 server streaming 读取。
- 实现 `DOC_UPLOADED` producer 与 Outbox publisher 接入。
- 实现 `controller.smoke` 下的 HTTP 验收入口。
- 为 smoke Docker 环境增加 Knowledge 文件 volume、配置和 HTTP 回归脚本。
- 更新 README、`constraints/package-structure.md`、`constraints/docker-structure.md`、`constraints/test-workflow.md`、依赖校验脚本和相关测试。

## 非目标

- 不实现 Access 权限、Tenant Membership、JWT、Refresh Session、API Key 或 `api_key_scope`。
- 不实现 upload token、两阶段 upload session、秒传、断点续传或对象存储直传。
- 不实现 RAG consumer、Ingestion Job、Chunk 构建、Retrieval 或 Query 改造。
- 不实现 RAG 状态回传后的 `PROCESSING / READY / FAILED` 消费。
- 不实现 Document 或 KnowledgeBase 删除。
- 不实现删除补偿、死信业务处理、孤立文件定时清理、告警平台或运维重放界面。
- 不提供正式 Console/Open HTTP API；HTTP 只作为 `smoke` Profile 下的验收入口。
- 不迁移旧 Demo 数据。
- 不修复 RAG 既有 `storage.result` 历史偏差。

## 前置依赖

- **执行前置 Plan**：无。
- `plan_17` 已完成可靠事件基础设施，`crag-event` 可用于 Knowledge 真实业务 Outbox 发布。
- 设计文档已提交：`a2259b0e docs: design knowledge vertical slice`。
- 当前工作区存在用户未提交的 `plan/plan_main.md` 改动；执行 plan18 前必须确认该改动归属，不得混入 plan18 实现或交接提交。
- 进入实现前必须先提交本计划和索引；未提交规划修订时不得开始 18.1。

## 文件边界

- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle/libs.versions.toml`
- `crag-knowledge-contracts/**`
- `crag-knowledge-service/build.gradle.kts`
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/**`
- `crag-knowledge-service/src/main/resources/**`
- `crag-knowledge-service/src/test/java/ai/cerbur/crag/knowledge/**`
- `crag-knowledge-service/src/test/resources/**`
- `docker-compose.yml`
- `docker/java-service.Dockerfile`
- `constraints/package-structure.md`
- `constraints/docker-structure.md`
- `constraints/test-workflow.md`
- `constraints/api-style.md`
- `constraints/persistence-style.md`
- `scripts/validate_module_dependencies.py`
- `scripts/tests/test_validate_module_dependencies.py`
- `scripts/validate_constraints.py`
- `scripts/tests/test_validate_constraints.py`
- `scripts/tests/http/knowledge_smoke_default_disabled_test.sh`
- `scripts/tests/http/knowledge_smoke_upload_txt_test.sh`
- `scripts/tests/http/knowledge_smoke_upload_md_test.sh`
- `scripts/tests/http/knowledge_smoke_upload_invalid_test.sh`
- `scripts/tests/http/knowledge_smoke_event_published_test.sh`
- `README.md`
- `plan/plan_18/plan_18.md`
- `plan/index/README.md`

## 实现文件地图

### `crag-knowledge-contracts`

- `crag-knowledge-contracts/build.gradle.kts`：Protobuf/gRPC contracts module，不依赖 Spring 或 service module。
- `crag-knowledge-contracts/src/main/proto/crag/knowledge/v1/knowledge_base_service.proto`：KnowledgeBase RPC、请求、响应、状态消息。
- `crag-knowledge-contracts/src/main/proto/crag/knowledge/v1/document_service.proto`：Document 上传、查询、列表、文件读取 RPC。

### `crag-knowledge-service`

- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/KnowledgeServiceApplication.java`：组合根主类，位于 base package 根。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/core/knowledgebase/**`：KnowledgeBase 用例服务、命令、结果和状态规则。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/core/document/**`：Document 上传、查询、校验策略、命令和结果。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/core/file/**`：文件读取用例、checksum、文件读取结果。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/dao/KnowledgeBaseDao.java`：KnowledgeBase 数据库访问边界。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/dao/DocumentDao.java`：Document 数据库访问边界。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/dao/FileObjectDao.java`：FileObject 数据库访问边界。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/dao/entity/**`：Knowledge 持久化 Entity 与 Converter。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/dao/repository/**`：Spring Data Repository，只允许 DAO 调用。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/filestore/**`：本地文件存储、storage key 生成、临时文件写入、原子移动和文件读取。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/grpc/provider/**`：gRPC provider，实现 contracts RPC。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/grpc/mapper/**`：core result 与 proto 互转。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/grpc/error/**`：gRPC 错误映射。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/producer/**`：`DOC_UPLOADED` payload、事件类型、Outbox 组装与发布接入。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/controller/smoke/**`：smoke-only HTTP 验收入口与 DTO。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/probe/**`：保留 Platform Probe 与 schema readiness。
- `crag-knowledge-service/src/main/resources/schema-knowledge.sql`：Knowledge 真实业务表初始化。
- `crag-knowledge-service/src/main/resources/application.yml`：默认 profile 业务配置，不暴露 smoke controller。
- `crag-knowledge-service/src/main/resources/application-smoke.yml`：smoke profile 文件存储、事件和诊断配置。

### 测试与脚本

- `crag-knowledge-contracts/src/test/**`：contracts 生成和模块边界测试，如需要。
- `crag-knowledge-service/src/test/java/ai/cerbur/crag/knowledge/core/**`：纯单元测试。
- `crag-knowledge-service/src/test/java/ai/cerbur/crag/knowledge/dao/**`：H2 组件测试。
- `crag-knowledge-service/src/test/java/ai/cerbur/crag/knowledge/filestore/**`：文件存储单元/临时目录测试。
- `crag-knowledge-service/src/test/java/ai/cerbur/crag/knowledge/grpc/**`：gRPC provider 映射组件测试。
- `crag-knowledge-service/src/test/java/ai/cerbur/crag/knowledge/controller/smoke/**`：smoke controller profile 与 multipart 组件测试。
- `crag-knowledge-service/src/test/java/ai/cerbur/crag/knowledge/architecture/**`：包边界与 profile 架构测试。
- `scripts/tests/http/knowledge_smoke_*.sh`：Docker HTTP 回归。

## 关键决策

- `plan_18` 对应 `router1`，正式创建后使用真实编号 `plan_18`。
- 新增 `crag-knowledge-contracts`，不把 Knowledge RPC 放入 `crag-platform-contracts`。
- `KnowledgeServiceApplication` 位于 `ai.cerbur.crag.knowledge` 根包，不保留 `app` 包。
- Knowledge 新代码使用 `core / dao / filestore / grpc / producer / controller.smoke / probe` 分包。
- `dao` 不新增 `result` 包；DAO 只管理数据库访问，跨层结构由外部 mapper/converter 处理。
- Document 上传使用单次客户端流式 RPC，不做两阶段 upload token。
- 客户端必须提供 sha256；服务端必须边接收边计算并强校验。
- 上传成功后写真实 `DOC_UPLOADED` Outbox 并发布到 Redis Streams，但不要求 RAG 消费。
- `controller.smoke` 是验收入口，不是正式业务 API。
- 删除、状态回传消费和权限编排均留给后续 router。

## 未决问题

无。

## 风险与回滚

- 风险：plan18 过度扩大到权限、RAG 或删除流程。预防措施是任务和架构测试禁止依赖 Access/RAG，所有跨服务消费和生命周期清理留给后续 router。
- 风险：文件上传失败留下孤立文件。预防措施是可控失败路径主动清理，崩溃残留只作为后续 Reconciler 范围。
- 风险：smoke HTTP 被误认为正式 API。预防措施是 `controller.smoke`、`@Profile("smoke")`、默认 profile 禁用测试和 Docker 回归。
- 风险：DAO 分层滑向跨层适配。预防措施是禁止 `dao.result`，增加包边界架构测试。
- 风险：Outbox 发布失败被误判为上传失败。预防措施是上传事务与发布解耦，发布失败进入 retry，并通过 smoke 诊断观察发布状态。
- 风险：真实文件 volume 影响重复回归。预防措施是脚本使用唯一 `runId`，不清空共享表、不删除 volume。
- 回滚：本计划不包含不可逆生产迁移。可按任务提交 revert `crag-knowledge-contracts`、Knowledge 表、filestore、gRPC provider、producer、smoke controller、Compose smoke 配置和约束文档改动。本地 Docker 残留测试文件按 runId 定位，普通回滚不要求删除共享 volume。

## 测试与验证计划

- 纯单元测试：`./gradlew :crag-knowledge-service:test --tests '*DocumentUploadPolicyTest' --tests '*StorageKeyGeneratorTest' --tests '*DocumentUploadedPayloadTest' --tests '*KnowledgeBaseServiceTest' --tests '*DocumentUploadServiceTest'`。
- 轻量组件测试：`./gradlew :crag-knowledge-service:test --tests '*DaoComponentTest' --tests '*GrpcProviderComponentTest' --tests '*SmokeControllerComponentTest' --tests '*FileStoreComponentTest'`。
- 架构测试：`./gradlew test --tests '*KnowledgeArchitectureTest' --tests '*ModuleDependencyArchitectureTest'`。
- 静态与格式：`./gradlew spotlessCheck`、`./gradlew check`。
- Plan 校验：`python3 scripts/validate_plans.py`；完成前由验收 session 运行 `python3 scripts/validate_plans.py --strict --verify-git`。
- 约束/依赖校验器：`python3 -m unittest scripts.tests.test_validate_module_dependencies scripts.tests.test_validate_constraints -v`。
- Docker HTTP 回归：`scripts/tests/http/knowledge_smoke_default_disabled_test.sh`、`scripts/tests/http/knowledge_smoke_upload_txt_test.sh`、`scripts/tests/http/knowledge_smoke_upload_md_test.sh`、`scripts/tests/http/knowledge_smoke_upload_invalid_test.sh`、`scripts/tests/http/knowledge_smoke_event_published_test.sh`。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 18.1 | 建立 Knowledge contracts 与包结构护栏 | ✅ 完成 | 51a53b93 | 2026-06-26 |
| 18.2 | 落地 Knowledge 数据模型与 DAO | ✅ 完成 | b52306a1 | 2026-06-26 |
| 18.3 | 实现文件存储与上传 core 链路 | ✅ 完成 | 441b550e | 2026-06-26 |
| 18.4 | 暴露 Knowledge gRPC provider | ✅ 完成 | 8b2b906d | 2026-06-26 |
| 18.5 | 接入 `DOC_UPLOADED` producer 与 Outbox 发布 | ✅ 完成 | 5071dc3e | 2026-06-26 |
| 18.6 | 提供 smoke HTTP 入口与 Docker 回归 | ✅ 完成 | 6ae8ff85 | 2026-06-26 |
| 18.7 | 同步约束文档、README 与全量验证 | ✅ 完成 | 3355ddad | 2026-06-26 |

整体进度：7 / 7（100%）

## 18.1 建立 Knowledge contracts 与包结构护栏

**目标**：新增 `crag-knowledge-contracts`，定义 KnowledgeBase 与 Document gRPC 契约，并建立 Knowledge 包结构与模块依赖护栏。  
**前置任务**：无  
**范围**：新增 `crag-knowledge-contracts` module；新增 KnowledgeBase/Document proto；更新 `settings.gradle.kts`、root/模块 Gradle 依赖；将 `KnowledgeServiceApplication` 移到 `ai.cerbur.crag.knowledge` 根包；新增或更新模块依赖校验、架构测试与包结构约束，禁止 Knowledge contracts 污染 platform contracts，禁止 service 依赖 Access/RAG。  
**非目标**：不实现 DAO、文件上传、gRPC provider 业务逻辑、事件 producer 或 smoke HTTP。  
**验收标准**：`crag-knowledge-contracts` 可生成 gRPC Java 代码；`crag-platform-contracts` 不包含 Knowledge RPC；`crag-knowledge-service` 能依赖 contracts；包结构规范在约束文档和架构测试中可验证；`KnowledgeServiceApplication` 根包扫描正常。  
**验证方式**：运行 `./gradlew :crag-knowledge-contracts:build :crag-knowledge-service:test --tests '*KnowledgeArchitectureTest'`；运行 `python3 -m unittest scripts.tests.test_validate_module_dependencies -v`；运行 `rg 'KnowledgeBaseService|DocumentService' crag-platform-contracts/src/main/proto` 应无命中。  
**涉及文件**：`settings.gradle.kts`、`build.gradle.kts`、`gradle/libs.versions.toml`、`crag-knowledge-contracts/**`、`crag-knowledge-service/build.gradle.kts`、`crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/**`、`crag-knowledge-service/src/test/java/ai/cerbur/crag/knowledge/architecture/**`、`constraints/package-structure.md`、`scripts/validate_module_dependencies.py`、`scripts/tests/test_validate_module_dependencies.py`

## 18.2 落地 Knowledge 数据模型与 DAO

**目标**：在 Knowledge schema 中建立 `knowledge_base`、`document`、`file_object`、真实业务 `outbox_event` 和 `processed_event`，并实现纯净 DAO 边界。  
**前置任务**：18.1  
**范围**：新增 schema 初始化 SQL；新增 `KnowledgeBaseEntity`、`DocumentEntity`、`FileObjectEntity` 与状态 converter；新增 `KnowledgeBaseRepository`、`DocumentRepository`、`FileObjectRepository`；新增 `KnowledgeBaseDao`、`DocumentDao`、`FileObjectDao`；实现 insert、tenant-scoped get/list、Document/FileObject 同事务保存所需 DAO 方法；覆盖 `created_at`、`updated_at`、`version` 与必要 CAS 更新。  
**非目标**：不实现上传文件落盘、不实现 gRPC provider、不实现 outbox 发布、不实现删除表状态。  
**验收标准**：所有业务表包含 `created_at`、`updated_at`、`version`；DAO 包不包含 `result`；Repository 只被 DAO 调用；查询均带 `tenantId` 或通过 DAO 方法表达租户隔离；H2 组件测试覆盖 insert/query/CAS/`updated_at`。  
**验证方式**：运行 `./gradlew :crag-knowledge-service:test --tests '*DaoComponentTest' --tests '*KnowledgeArchitectureTest'`；运行 `rg 'package .*knowledge\\.dao\\.result' crag-knowledge-service/src/main/java` 应无命中；运行 `rg 'repository\\.' crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/core crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/grpc crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/controller crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/producer` 应无命中。  
**涉及文件**：`crag-knowledge-service/src/main/resources/schema-knowledge.sql`、`crag-knowledge-service/src/main/resources/application.yml`、`crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/dao/**`、`crag-knowledge-service/src/test/java/ai/cerbur/crag/knowledge/dao/**`、`crag-knowledge-service/src/test/java/ai/cerbur/crag/knowledge/architecture/**`

## 18.3 实现文件存储与上传 core 链路

**目标**：实现单次客户端流式上传的核心业务链路，完成 metadata 校验、临时文件写入、sha256 强校验、UTF-8 校验、原子落盘和业务记录创建。  
**前置任务**：18.2  
**范围**：新增 `core/knowledgebase` 用例服务与结果；新增 `core/document` 上传命令、上传策略、状态与结果；新增 `core/file` 文件读取用例；新增 `filestore` 本地文件存储、storage key 生成、临时文件写入和文件读取；实现 KnowledgeBase 创建/查看/list；实现 Document 上传核心服务，成功时创建 `Document(PENDING)` 与 `FileObject(STORED)`，失败时清理临时/最终文件。  
**非目标**：不暴露 gRPC/HTTP，不写 `DOC_UPLOADED` Outbox，不启动 publisher，不做删除和状态回传消费。  
**验收标准**：`.txt`、`.md` 上传通过；非法扩展名、空文件、超 10 MiB、大小不匹配、sha256 不匹配、非 UTF-8 均失败且不创建业务记录；storage key 不包含原始文件名；事务失败清理本次文件；core 不直接依赖 Repository。  
**验证方式**：运行 `./gradlew :crag-knowledge-service:test --tests '*DocumentUploadPolicyTest' --tests '*StorageKeyGeneratorTest' --tests '*DocumentUploadServiceTest' --tests '*FileStoreComponentTest' --tests '*KnowledgeArchitectureTest'`。  
**涉及文件**：`crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/core/**`、`crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/filestore/**`、`crag-knowledge-service/src/test/java/ai/cerbur/crag/knowledge/core/**`、`crag-knowledge-service/src/test/java/ai/cerbur/crag/knowledge/filestore/**`

## 18.4 暴露 Knowledge gRPC provider

**目标**：实现 `crag-knowledge-contracts` 中定义的 KnowledgeBase 与 Document gRPC provider，并提供文件 server streaming 读取。  
**前置任务**：18.3  
**范围**：新增 `grpc/provider`、`grpc/mapper`、`grpc/error`；实现 KnowledgeBase 创建/查询/list RPC；实现 Document 客户端流式上传 RPC；实现 Document 查询/list RPC；实现 `ReadDocumentFile` server streaming；接入 grpc runtime 与错误映射；确保 ID 边界使用十进制字符串。  
**非目标**：不实现 Console/Open API 调用方，不实现 RAG client，不实现正式 HTTP API。  
**验收标准**：gRPC provider 只做协议暴露、proto 映射和错误映射；业务逻辑复用 core service；读取响应不包含 storage key/path；跨租户查询返回 permission-safe not found 类错误；gRPC 组件测试覆盖正常、非法参数、sha256 mismatch、读取不存在文档。  
**验证方式**：运行 `./gradlew :crag-knowledge-service:test --tests '*GrpcProviderComponentTest' --tests '*KnowledgeArchitectureTest'`；运行 `rg 'storageKey|storage_key|path' crag-knowledge-contracts/src/main/proto` 应无跨服务泄漏字段。  
**涉及文件**：`crag-knowledge-contracts/src/main/proto/crag/knowledge/v1/**`、`crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/grpc/**`、`crag-knowledge-service/src/test/java/ai/cerbur/crag/knowledge/grpc/**`、`crag-knowledge-service/src/test/java/ai/cerbur/crag/knowledge/architecture/**`

## 18.5 接入 `DOC_UPLOADED` producer 与 Outbox 发布

**目标**：上传成功后同事务写入真实 `DOC_UPLOADED` Outbox，并通过 `crag-event` publisher 发布到 Redis Streams。  
**前置任务**：18.4  
**范围**：新增 `producer` 包中的事件类型、payload、Outbox 组装服务；将 Document 上传成功路径扩展为同事务创建 Document、FileObject、Outbox；配置 Knowledge 真实业务 Outbox publisher；更新 `application.yml` / `application-smoke.yml` 中事件配置；新增 payload 和 producer 测试。  
**非目标**：不实现 RAG consumer，不实现 Knowledge consumer，不实现人工 DLQ 重放界面。  
**验收标准**：`DOC_UPLOADED` payload 包含 `tenantId`、`knowledgeBaseId`、`docId`、`operationVersion`、`fileType`、`sizeBytes`、`sha256`；payload 不包含文件路径、storage key 或文件内容；发布失败不回滚上传事务，Outbox 进入 retry；重复 publisher claim 遵守 `crag-event` 幂等/CAS 语义。  
**验证方式**：运行 `./gradlew :crag-knowledge-service:test --tests '*DocumentUploadedPayloadTest' --tests '*KnowledgeEventProducerTest' --tests '*DocumentUploadServiceTest'`；运行 `rg 'storageKey|storage_key|path|content' crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/producer` 核对 producer 不泄漏路径或内容。  
**涉及文件**：`crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/producer/**`、`crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/core/document/**`、`crag-knowledge-service/src/main/resources/application.yml`、`crag-knowledge-service/src/main/resources/application-smoke.yml`、`crag-knowledge-service/src/test/java/ai/cerbur/crag/knowledge/producer/**`、`crag-knowledge-service/src/test/java/ai/cerbur/crag/knowledge/core/document/**`

## 18.6 提供 smoke HTTP 入口与 Docker 回归

**目标**：提供 `smoke` Profile 下的 Knowledge HTTP 验收入口，并用 Docker HTTP 脚本证明真实 PostgreSQL、文件 volume、Redis Streams 和读取链路。  
**前置任务**：18.5  
**范围**：新增 `controller.smoke` Controller、DTO 和 mapper；实现创建 KB、查询 KB、multipart 上传、查询 Document、读取文件、查询事件发布状态的 smoke endpoint；更新 `docker-compose.yml` 与文件 volume；新增或更新 `knowledge-service-smoke` 环境变量和健康检查；新增 Docker HTTP 回归脚本。  
**非目标**：不提供正式业务 HTTP API，不接 Console/Open API，不把 smoke DTO 作为产品契约。  
**验收标准**：默认 profile 不暴露 Knowledge smoke endpoint；smoke profile 可创建 KB、上传 `.txt` / `.md`、查询 Document、读回原始内容；sha256 mismatch、非 UTF-8、非法扩展名、超 10 MiB 上传失败且不创建 Document；`DOC_UPLOADED` 发布到 Redis Streams；脚本使用唯一 runId，不清空共享表或删除 volume。  
**验证方式**：运行 `./gradlew :crag-knowledge-service:test --tests '*SmokeControllerComponentTest'`；运行 `scripts/tests/http/knowledge_smoke_default_disabled_test.sh`、`scripts/tests/http/knowledge_smoke_upload_txt_test.sh`、`scripts/tests/http/knowledge_smoke_upload_md_test.sh`、`scripts/tests/http/knowledge_smoke_upload_invalid_test.sh`、`scripts/tests/http/knowledge_smoke_event_published_test.sh`。  
**涉及文件**：`crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/controller/smoke/**`、`crag-knowledge-service/src/test/java/ai/cerbur/crag/knowledge/controller/smoke/**`、`docker-compose.yml`、`docker/java-service.Dockerfile`、`scripts/tests/http/knowledge_smoke_default_disabled_test.sh`、`scripts/tests/http/knowledge_smoke_upload_txt_test.sh`、`scripts/tests/http/knowledge_smoke_upload_md_test.sh`、`scripts/tests/http/knowledge_smoke_upload_invalid_test.sh`、`scripts/tests/http/knowledge_smoke_event_published_test.sh`

## 18.7 同步约束文档、README 与全量验证

**目标**：同步项目约束、README、校验器和 plan/index，完成全量验证并进入独立验收交接。  
**前置任务**：18.6  
**范围**：更新 `constraints/package-structure.md` 的 Knowledge 包结构与 `crag-knowledge-contracts`；更新 `constraints/docker-structure.md` 的 Knowledge 文件 volume 与 smoke 服务事实；更新 `constraints/test-workflow.md` 的 Knowledge smoke 回归说明；必要时更新 `constraints/api-style.md`、`constraints/persistence-style.md`；更新 README 当前能力；运行全量验证；回填任务实现提交 hash；将 plan18 任务转为待验收并同步索引。  
**非目标**：不修复与 plan18 无关的历史 Hotfix，不提交用户未归属的 `plan_main.md` 改动，不执行最终验收。  
**验收标准**：约束文档与当前实现事实一致；静态校验器覆盖新模块与包结构；所有计划内验证命令有记录；任务提交栏回填真实短 hash；plan18 状态进入 `verifying`；索引验收队列包含 plan18。  
**验证方式**：运行 `./gradlew spotlessCheck`、`./gradlew check`、`python3 scripts/validate_plans.py`、`python3 scripts/validate_plans.py --strict --verify-git`、`python3 -m unittest scripts.tests.test_validate_module_dependencies scripts.tests.test_validate_constraints -v`，并重跑 18.6 的 Docker HTTP 回归脚本。  
**涉及文件**：`constraints/package-structure.md`、`constraints/docker-structure.md`、`constraints/test-workflow.md`、`constraints/api-style.md`、`constraints/persistence-style.md`、`scripts/validate_module_dependencies.py`、`scripts/tests/test_validate_module_dependencies.py`、`scripts/validate_constraints.py`、`scripts/tests/test_validate_constraints.py`、`README.md`、`plan/plan_18/plan_18.md`、`plan/index/README.md`

## 验收记录

> 以下为执行 session 自测记录；最终独立验收由未参与实现的新 agent session 完成。

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-26 | 本机 JDK 21 + Gradle 9.4.1 | `./gradlew check` | 通过 | 全模块测试、spotless、框架/模块依赖/约束/Plan 校验通过（24 个 P101 警告来自历史 v2 Plan，与 plan_18 无关）。 |
| 2026-06-26 | 本机 JDK 21 | 各任务 `--tests` 验证命令 | 通过 | DocumentUploadPolicy/StorageKeyGenerator/DocumentUploadService/FileStore/KnowledgeDao/GrpcProvider/KnowledgeSmokeController/DocumentUploadedPayload/KnowledgeEventProducer/KnowledgeArchitecture 测试均通过。 |
| 2026-06-26 | 本机 Python 3 | `python3 scripts/validate_plans.py`、`-m unittest scripts.tests.test_validate_module_dependencies scripts.tests.test_validate_constraints` | 通过 | 0 错误。 |
| 2026-06-26 | Docker Compose（PostgreSQL17 + Redis7.4 + knowledge-service-smoke smoke profile） | `scripts/tests/http/knowledge_smoke_{default_disabled,upload_txt,upload_md,upload_invalid,event_published}_test.sh` | 通过 | 默认 profile 不暴露 smoke(404)；.txt/.md 上传成功(PENDING+读回)；4 类非法上传(400)；DOC_UPLOADED 发布到 Redis Streams(PUBLISHED)。 |

### 独立验收（2026-06-26，未参与实现的新 session）

验收结论：**通过**。独立验收 session 从仓库事实重建上下文，重新运行全部必需验证，未修改任何实现代码或测试。

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-26 | 本机 JDK 21 + Gradle 9.4.1 | `./gradlew :crag-knowledge-service:cleanTest :crag-knowledge-service:test` | 通过 | 强制重跑知识服务全部纯单元/轻量组件/架构测试，BUILD SUCCESSFUL（23s）。 |
| 2026-06-26 | 本机 JDK 21 | `./gradlew check` | 通过 | 全模块 spotless/测试/Plan/模块依赖/约束校验通过，BUILD SUCCESSFUL。 |
| 2026-06-26 | 本机 Python 3 | `python3 scripts/validate_plans.py --strict --verify-git` | 通过 | 0 error；24 个 P101 警告均为历史 v2 Plan，与 plan_18 无关。 |
| 2026-06-26 | 本机 Python 3 | `python3 -m unittest scripts.tests.test_validate_module_dependencies scripts.tests.test_validate_constraints -v` | 通过 | 37 个校验器单测全部 OK。 |
| 2026-06-26 | Docker Compose（PostgreSQL17 + Redis7.4） | `scripts/tests/http/knowledge_smoke_default_disabled_test.sh` | 通过 | 默认 profile `/api/v1/smoke/knowledge/**` 返回 404。 |
| 2026-06-26 | 同上 | `scripts/tests/http/knowledge_smoke_upload_txt_test.sh` | 通过 | .txt 上传 → doc=4 PENDING，读回内容一致。 |
| 2026-06-26 | 同上 | `scripts/tests/http/knowledge_smoke_upload_md_test.sh` | 通过 | .md 上传 → doc=5 PENDING，读回内容一致。 |
| 2026-06-26 | 同上 | `scripts/tests/http/knowledge_smoke_upload_invalid_test.sh` | 通过 | 非法扩展名 / sha256 不匹配 / 非 UTF-8 / 超 10 MiB 均返回 400。 |
| 2026-06-26 | 同上 | `scripts/tests/http/knowledge_smoke_event_published_test.sh` | 通过 | DOC_UPLOADED 发布到 Redis Streams，状态 PUBLISHED（doc=6）。 |

逐任务核对：7 个实现 commit（`51a53b93`…`3355ddad`）经 `git show --stat` 核对均服务对应任务，无跨 Plan 范围混入；交接提交 `029481eb` 与验收后 `af4f2adc`（仅改 `plan/plan_main.md`）不涉及实现证据。架构测试覆盖：无模块环依赖、Repository 仅 DAO 访问、DAO 不反向依赖上层、无 `dao.result` 包、Controller 收口 `controller.smoke` 且带 `@Profile("smoke")`、字段注入、不依赖 RAG 业务模块。代码核对：三张业务表均含 `created_at/updated_at/version`；`StorageKeyGenerator` 生成的 key 不含原始文件名；`DocumentUploadedPayload` 仅 7 个安全字段、无路径/storage key/内容；`DocumentUploadService.complete()` 同事务创建 Document(PENDING)+FileObject(STORED)+Outbox，失败清理文件并回滚，Redis 发布异步解耦故发布失败不回滚上传；默认 `application.yml` 不启用 publisher 且不暴露 smoke 入口。HTTP 脚本均以唯一 `runId`（`k-{type}-{epoch}-{pid}`）隔离数据，不清表、不删 volume。

未执行项：无。Plan 与 `constraints/test-workflow.md` 要求的全部验证均有新鲜证据。

观察（非阻塞）：`cleanTest` 重跑日志在 Spring Context 关闭阶段出现 `LettuceConnectionFactory is STOPPING`（`crag-event` consumer 调度器在 `ionShutdownHook` 时仍尝试 `createGroup`）。该 ERROR 发生在测试断言之后的销毁阶段，BUILD 成功，属 crag-event 既有关闭竞态，不影响测试结果与 plan_18 正确性。

## 阻塞记录

无。发生阻塞时记录原因、当前进度、解除条件、解除方、下一步与日期。

## 废弃任务记录

无。任务废弃时记录原因、日期及替代任务或决策。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-26 | 创建计划 | 将 router1 Knowledge 垂直链路设计转为 workflow v3 主 Plan | 初始范围，状态 ready |
| 2026-06-26 | 18.1–18.7 实现交接 | 7 任务实现并回填真实短 hash | 状态 ready → verifying，整体进度 7/7 |
| 2026-06-26 | ID 策略 | 业务主键采用数据库 identity 列（非 Snowflake） | plan_18 文件边界不含 crag-id；边界 ID 仍为十进制字符串，outbox event_id 取 Knowledge 本地序列 `knowledge_event_id_seq` |
| 2026-06-26 | 默认 profile publisher | 默认 profile 不启用 publisher | 默认服务无上传入口；DOC_UPLOADED 发布由 smoke profile（publisher+redis+文件 volume）证明 |
| 2026-06-26 | DAO 组件测试上下文 | KnowledgeDaoComponentTest 改用不含 gRPC 的窄上下文（KnowledgeDaoTestConfig） | 规避多 @SpringBootTest 共享全量上下文时 gRPC Server 单次使用的 "Already started" 重启问题 |
