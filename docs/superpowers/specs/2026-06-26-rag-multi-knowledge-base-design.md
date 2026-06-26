# CRAG-Demo RAG 多知识库化设计

日期：2026-06-26

状态：已确认

范围：router2 / plan19 的设计事实来源。正式执行前仍需创建 workflow v3 Plan 文件并提交后再实现。

## 1. 背景

CRAG-Demo 已完成多服务骨架、Snowflake ID、RAG Service module 收口、可靠事件基础设施和 Knowledge 垂直链路。

当前 Knowledge 已能创建 KnowledgeBase、上传 `.txt / .md` Document、保存文件、通过 gRPC 流式读取文件，并发布 `DOC_UPLOADED` 事件。RAG 仍保留旧的单知识空间 Chunk、Dense、Sparse、Retrieval 和 Query 模型，不能按 KnowledgeBase 隔离，也不能消费 Knowledge 上传事件异步索引。

当前 `plan_main.md` 已将后续阶段改为 router 占位。旧总体设计稿中的历史编号 `plan_18 = RAG`、`plan_19 = Access` 已被后续路线修正覆盖；本设计以当前 `plan_main.md` 为准，将正式创建的 `plan_19` 用于 router2：RAG 多知识库化。

## 2. 目标

1. RAG 消费 Knowledge 的 `DOC_UPLOADED` 事件，并具备消费失败重试、Pending reclaim 和 DLQ 处理。
2. 以 `docId + operationVersion` 幂等创建并推进 `ingestion_job`。
3. RAG 通过 Knowledge gRPC `ReadDocumentFile` 读取文件，解析文本并构建 Chunk、Dense、Sparse 索引。
4. `chunk`、`chunk_embedding`、`chunk_fts` 三张 RAG 表全部落 `knowledgeBaseId`，并在写入和查询路径强制一致。
5. Retrieval 和 Query 公开入口显式接收 `knowledgeBaseId`，所有召回、融合、重排候选扩展和 Parent Evidence 回表都先按 KnowledgeBase 隔离。
6. RAG 发布 `INGESTION_PROCESSING / INGESTION_READY / INGESTION_FAILED` 状态事件。
7. 保留旧 `AdminRag` smoke 入口作为历史诊断能力，并为旧入口补固定或显式 smoke `knowledgeBaseId`。
8. 提供 Docker smoke 验收，证明从 Knowledge 上传事件到 RAG 索引、查询隔离和状态事件发布的完整链路。

## 3. 非目标

- 不实现 Knowledge 消费 RAG 状态事件，也不更新 Knowledge 的 Document 展示状态。
- 不实现 Ingestion Job 自动业务 retry attempt、人工 retry、reconciler 或补偿扫描。
- 不实现 Document / KnowledgeBase 删除、`deletion_guard` 或下游物理清理。
- 不实现 Access、API Key、Console API、Open API 或正式 RAG Query gRPC 契约。
- 不迁移旧 Demo 数据。测试和本地环境允许冷启动重建 RAG schema。
- 不移除旧 `AdminRag` / `UserQuery` smoke 诊断入口。

## 4. 总体架构

```text
Knowledge Service
  Document upload
  -> outbox DOC_UPLOADED
  -> Redis Streams

RAG Service
  consumer DOC_UPLOADED
  -> ingestion_job(docId, operationVersion)
  -> Knowledge gRPC ReadDocumentFile
  -> ChunkSplit
  -> chunk / chunk_embedding / chunk_fts with knowledgeBaseId
  -> Retrieval / Query with knowledgeBaseId
  -> outbox INGESTION_PROCESSING / READY / FAILED
```

RAG 只消费 Knowledge 已发布的上传事实，不读取 Knowledge 数据库，不保存文件路径，不接触 Access 权限。跨服务同步读取只通过 Knowledge gRPC 契约完成。

RAG 状态回传只发布事件。Knowledge 对状态事件的消费、Document 展示状态更新、补偿扫描和长期故障治理留给后续生命周期可靠性阶段。

## 5. 事件消费设计

RAG 新增 `consumer` 包，订阅 Knowledge 的 `DOC_UPLOADED` 事件。事件 payload 必须包含：

- `tenantId`
- `knowledgeBaseId`
- `docId`
- `operationVersion`
- `fileType`
- `sizeBytes`
- `sha256`

Consumer 使用 `crag-event` 的标准能力：

- Redis Streams Consumer Group。
- Pending reclaim。
- 最大失败次数。
- DLQ / dead-letter。
- `processed_event` 幂等记录。

消费处理异常时不 ACK，消息留在 Pending，由 reclaim 重新投递。超过最大消费失败次数后进入 DLQ / dead-letter，不再无限重试。重复投递由 `processed_event` 和 `ingestion_job(docId, operationVersion)` 双层幂等保护。

本设计区分消费层重试和业务 Job 重试。plan19 必须实现消费层失败重试、reclaim 和 DLQ；不实现 Ingestion Job 自动业务 retry attempt。业务处理失败后 Job 进入 `FAILED` 并发布 `INGESTION_FAILED`，后续显式 retry 或 reconciler 留给后续阶段。

## 6. Ingestion Job

新增 `ingestion_job` 表。

建议字段：

- `job_id`
- `tenant_id`
- `knowledge_base_id`
- `doc_id`
- `operation_version`
- `status`
- `file_type`
- `size_bytes`
- `sha256`
- `failure_category`
- `failure_message`
- `started_at`
- `completed_at`
- `created_at`
- `updated_at`
- `version`

唯一键：

- `(doc_id, operation_version)`

状态：

```text
PENDING -> PROCESSING -> READY
                       -> FAILED
```

处理流程：

1. 首次看到 `docId + operationVersion` 时创建 `PENDING` Job。
2. 推进为 `PROCESSING`，并发布 `INGESTION_PROCESSING`。
3. 调用 Knowledge gRPC `ReadDocumentFile(tenantId, knowledgeBaseId, docId)` 读取文件流。
4. 校验字节数、sha256 和 fileType。
5. 将文件按 UTF-8 文本解析，复用 `ChunkSplitService` 生成 parent / child。
6. 批量写入 `chunk`，parent 跳过 Dense/Sparse，child 进入 Dense/Sparse 待处理。
7. 复用现有 Dense/Sparse worker 或 cron 处理 child 索引。
8. 当该 Document 的 child Dense/Sparse 全部成功后推进 Job 为 `READY`，并发布 `INGESTION_READY`。
9. 业务处理失败时推进 Job 为 `FAILED`，记录安全失败分类和短摘要，并发布 `INGESTION_FAILED`。

重复 `DOC_UPLOADED`：

- 已有 `PENDING / PROCESSING`：不得创建第二个 Job，可按当前状态安全返回。
- 已有 `READY`：直接视为已处理。
- 已有 `FAILED`：不自动重跑，直接视为终态已记录。

## 7. RAG 数据模型

RAG 三张索引表全部显式落 `knowledge_base_id`。

### `chunk`

新增：

- `knowledge_base_id BIGINT NOT NULL`

约束与索引建议：

- 不迁移旧数据，测试和本地环境允许冷启动重建。
- `doc_id` 是 Knowledge 传来的 Snowflake ID。
- parent 和 child chunk 必须带同一个 `knowledge_base_id`。
- 增加 `(knowledge_base_id, doc_id)` 索引。
- 增加 `(knowledge_base_id, parent_chunk_id)` 索引。
- 状态扫描按实际 SQL 增加包含 `knowledge_base_id` 的组合索引。

### `chunk_embedding`

新增：

- `knowledge_base_id BIGINT NOT NULL`

规则：

- 主键仍为 `chunk_id`。
- 不建立数据库外键。
- Dense 查询必须先按 `chunk_embedding.knowledge_base_id = :knowledgeBaseId` 限定候选。
- 写入只能从包含 `knowledgeBaseId` 的 chunk 投影派生，禁止调用方自己拼装不一致数据。
- 增加 `(knowledge_base_id, chunk_id)` 索引。
- 保留向量 HNSW 索引。

### `chunk_fts`

新增：

- `knowledge_base_id BIGINT NOT NULL`

规则：

- 主键仍为 `chunk_id`。
- 不建立数据库外键。
- Sparse 查询必须先按 `chunk_fts.knowledge_base_id = :knowledgeBaseId` 限定候选。
- 写入只能从包含 `knowledgeBaseId` 的 chunk 投影派生。
- 增加 `(knowledge_base_id, chunk_id)` 索引。
- 保留 FTS GIN 索引。

### 无外键策略

生产级逻辑不依赖数据库外键。RAG 内部表之间使用相同业务 ID 关联，但不建立数据库 FK。应用层一致性由 DAO 和 service 写入路径保证，测试必须覆盖：

- Chunk、Embedding、FTS 三表 `knowledgeBaseId` 一致。
- 孤儿索引行不会被召回为有效结果。
- 跨表 `knowledgeBaseId` 不一致时不会串库召回。

## 8. DAO 与持久化边界

新增：

- `IngestionJobDao`
- `IngestionJobEntity`
- `IngestionJobRepository`

调整：

- `ChunkDao`
- `ChunkEmbeddingDao`
- `ChunkFtsDao`

要求：

- Repository 只允许 DAO 调用。
- 新增 Retrieval / Query 相关 DAO 查询必须显式要求 `knowledgeBaseId`。
- Dense/Sparse 写入方法必须从可信 chunk 投影携带 `knowledgeBaseId`。
- Parent 回表 DAO 必须要求 `knowledgeBaseId`，不能只按 parent chunk IDs 查询。
- 旧 smoke/AdminRag 兼容入口可使用固定 smoke `knowledgeBaseId`，但不能新增无 KB 的写入路径。
- CAS 更新继续遵守 `constraints/persistence-style.md`，自定义更新必须带版本条件并在 DAO 判断 affected rows。

## 9. Retrieval 与 Query

Retrieval 新入口：

```java
retrieve(long knowledgeBaseId, String query, int topN)
retrieveEvidence(long knowledgeBaseId, String query, int topN)
```

Query 新入口：

```java
answer(long knowledgeBaseId, String question)
```

规则：

- Sparse 召回必须在 `chunk_fts.knowledge_base_id = :knowledgeBaseId` 范围内执行。
- Dense 召回必须在 `chunk_embedding.knowledge_base_id = :knowledgeBaseId` 范围内执行。
- RRF 只融合同一 KB 内的 child 候选。
- Rerank 相邻 child 扩展只在同一 KB 内查询。
- Parent Evidence 回表必须带 `knowledgeBaseId`。
- Context、Prompt、LLM 逻辑不感知租户，只接收已经隔离后的 evidence。
- 日志允许记录 `knowledgeBaseId`、ID、计数和失败分类；禁止记录完整文档、Prompt、Context、向量。

旧无 KB 方法不得作为新业务路径。若为了兼容历史 smoke 测试保留，必须内部委派到固定 smoke `knowledgeBaseId`，并用架构测试或单元测试防止 Query 新链路调用无 KB 入口。

## 10. Smoke 验证

保留旧 RAG smoke AdminRag/UserQuery 脚本，使用固定 smoke KB 验证历史链路仍可运行。

新增 router2 smoke 脚本，建议覆盖：

1. 通过 Knowledge smoke 创建两个 KB。
2. 分别上传内容明显不同的 `.txt / .md`。
3. 等待 Knowledge 发布 `DOC_UPLOADED`。
4. RAG 消费 `DOC_UPLOADED`。
5. 等待 RAG `ingestion_job` 进入 `READY`。
6. 使用 RAG smoke query endpoint 分别带 `knowledgeBaseId` 查询。
7. 断言 KB A 只能召回 A 的内容，KB B 只能召回 B 的内容。
8. 断言 RAG 发布 `INGESTION_PROCESSING` 与 `INGESTION_READY`。
9. 重复投递同一个 `DOC_UPLOADED`，断言不重复生成 chunk。
10. 通过失败注入或无效 doc 事件验证消费失败进入 Pending/reclaim/DLQ。

脚本要求：

- 使用唯一 `runId`。
- 不清空共享表。
- 不删除 Docker volume。
- 不执行 `docker compose down -v`。
- 所有断言通过 HTTP smoke 入口或已定义诊断入口完成；`docker exec` / logs 只作辅助诊断。

验收者如果遇到旧 schema 或历史数据残留导致初始化冲突，可以手动停止 Compose 并清理本仓库 `data/pgdata*` 后冷启动。该动作属于人工验收环境准备，不写入自动化脚本，也不表示项目支持旧数据迁移。

## 11. 状态事件

RAG 发布三类状态事件：

- `INGESTION_PROCESSING`
- `INGESTION_READY`
- `INGESTION_FAILED`

payload 建议包含：

- `tenantId`
- `knowledgeBaseId`
- `docId`
- `operationVersion`
- `jobId`
- `status`
- `failureCategory`
- `failureMessage`
- `occurredAt`

安全要求：

- 不包含文件内容。
- 不包含 storage key 或文件路径。
- 不包含 Prompt、Context 或向量。
- `failureMessage` 必须是安全短摘要，不透传 SQL、堆栈、文件内容或下游原始敏感错误。

## 12. 任务拆分建议

正式 plan19 可按以下任务拆分：

1. 建立 RAG 多 KB schema 和 Ingestion Job 基础模型。
2. 接入 `DOC_UPLOADED` consumer、消费幂等、Pending reclaim 和 DLQ 验证。
3. 实现 Knowledge gRPC 文件读取与 Ingestion Job 处理编排。
4. 将 Chunk / Dense / Sparse 写入路径改为携带 `knowledgeBaseId`。
5. 将 Retrieval / Query 改为强制 `knowledgeBaseId` 隔离。
6. 发布 RAG ingestion 状态事件。
7. 提供 router2 smoke HTTP 入口与 Docker 回归。
8. 同步约束、README、Plan 索引和全量验证。

任务边界可在正式 Plan 中进一步细化，但不得把 Access、Open API、Knowledge 状态消费或删除可靠性并入 plan19。

## 13. 测试策略

纯单元测试：

- Ingestion Job 状态流转。
- `docId + operationVersion` 幂等。
- `DOC_UPLOADED` payload 解析和非法字段处理。
- 状态事件 payload 安全字段。
- Retrieval API 入参校验和空结果。
- Query 在空 evidence 时保持“知识库证据不足”语义。

轻量组件测试：

- IngestionJob DAO insert、CAS、唯一键和状态推进。
- Chunk / Embedding / FTS 三表 `knowledgeBaseId` 写入一致。
- Dense/Sparse 查询 SQL 必须带 `knowledgeBaseId`。
- Parent Evidence 回表必须带 `knowledgeBaseId`。
- Consumer 处理重复事件不重复创建 job 或 chunk。
- 消费失败不 ACK，超过次数进入 DLQ / dead-letter。

架构测试：

- RAG consumer 不依赖 Knowledge service 实现，只依赖 Knowledge contracts 和 gRPC client。
- Repository 只被 DAO 调用。
- Query 不调用无 `knowledgeBaseId` 的 Retrieval 新业务路径。
- 新增 RAG 状态事件 producer 不泄漏文件内容、storage key、Prompt、Context 或向量字段。
- 旧 smoke 入口受 smoke Profile 限制。

Docker HTTP 回归：

- 旧 RAG smoke 链路仍可运行。
- Knowledge 上传 `.txt / .md` 后，RAG 消费并索引到 `READY`。
- 两个 KB 内容互不串召回。
- 重复 `DOC_UPLOADED` 不重复生成 chunk。
- 消费失败进入 Pending/reclaim/DLQ。
- RAG 状态事件发布可观察。

## 14. 风险与回滚

风险：plan19 范围扩大到 Access、Open API 或 Knowledge 状态消费。  
预防：Plan 文件明确非目标，任务文件边界不包含 Access/Open API，Knowledge 只作为 contracts 调用方和 smoke 数据源。

风险：只在业务层过滤 KB，底层召回仍跨库。  
预防：Dense/Sparse SQL 和 Parent 回表 DAO 强制带 `knowledgeBaseId`，用组件测试构造串库数据验证不会召回。

风险：无外键后出现孤儿索引行或跨表 KB 不一致。  
预防：写入路径统一从 chunk 投影派生 KB，测试覆盖孤儿和不一致数据不被召回。

风险：消费失败与业务失败混淆，导致无限重试或过早丢弃。  
预防：消费层使用 `crag-event` reclaim/DLQ；业务 Job 失败进入终态 `FAILED`，不由重复事件自动重跑。

风险：旧 AdminRag smoke 入口污染新业务模型。  
预防：旧入口只使用固定或显式 smoke KB，不复用新 Ingestion Job，不作为正式入口。

回滚：plan19 不承诺迁移旧数据。本地和测试环境可以冷启动重建 RAG schema。代码回滚可按任务提交撤销 `ingestion_job`、RAG consumer、schema、Retrieval/Query API、状态事件和 smoke 脚本改动。自动化脚本不得执行破坏性清理；人工验收环境清理需记录在验收证据中。

## 15. 已确认决策

- plan19 对应当前 `plan_main.md` 的 router2：RAG 多知识库化。
- 采用完整 RAG 垂直链路：消费上传事件、异步索引、查询隔离、状态事件发布。
- 状态回传只做到 RAG 发布事件，不实现 Knowledge 消费。
- `docId + operationVersion` 是 Ingestion Job 业务幂等键。
- 消费层必须支持失败重试、Pending reclaim 和 DLQ。
- Ingestion Job 不做自动业务 retry attempt。
- 旧 `AdminRag` smoke 入口保留但隔离。
- `chunk`、`chunk_embedding`、`chunk_fts` 三表都落 `knowledgeBaseId`。
- RAG 内部索引表不使用数据库外键，依赖应用层一致性和测试护栏。

## 16. 未决问题

无。
