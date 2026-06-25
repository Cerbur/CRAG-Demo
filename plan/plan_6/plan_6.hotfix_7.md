---
workflow_version: 3
plan_id: plan_6.hotfix_7
type: hotfix
parent_plan: plan_6
status: ready
created: 2026-06-25
updated: 2026-06-25
---

# plan_6.hotfix_7 — Retrieval 查询召回率修正（Sparse AND 语义过严 + Dense 0 结果）

## 背景与目标

`plan_15` 第四次独立验收的 Docker HTTP 重跑发现 `scripts/tests/http/query_stub_success_test.sh` 失败：AdminRag 写入成功且 child chunk 的 Dense/Sparse 索引均 `success`，但 UserQuery 对含疑问词的 query（如 `verify-qs-... 使用什么数据库？`）返回 `sources=[]`、`answer="知识库证据不足"`。`RetrievalService` 日志显示 `Retrieval search — sparse=0, dense=0`，RRF 融合无结果。

根因定位（证据驱动，非 plan_15 引入——plan_15/15.4 提交 `1ee473f` 只改检索结果对象的 ID 类型与 Controller 边界，未触及任何检索 SQL/RRF/Rerank 生产逻辑；plan_15/15.3 对 `ChunkFtsRepository`/`ChunkEmbeddingRepository` 只改 ID 类型 `String→long`，`@@ plainto_tsquery` 与 `<=>` cosine 逻辑未动，DAO 的 `((Number) row[0]).longValue()` 映射正确）：

1. **Sparse=0**：`ChunkFtsRepository.searchFts` 使用 `WHERE cf.fts_content @@ plainto_tsquery('simple', regexp_replace(:query, CJK预处理))`。`plainto_tsquery` 是 **AND 语义**——query 的所有 token 都必须在 chunk 中匹配。含疑问词的 query（`什/么/数/据/库/？`）在陈述句 chunk（`项目使用 PostgreSQL...`）中不存在这些 token，`@@` 判定为 false，Sparse 返回空。（直接 `ts_rank` 计算仍给非零分，因为它不要求 AND 全匹配，掩盖了该问题。）
2. **Dense=0**：Java 层 `DenseQueryService.search` 返回空。但 DB 层 `ChunkEmbeddingRepository.searchSimilar` 对 Sidecar 产生的 query 向量直接执行返回行（实测 `dist=0.1537`），Sidecar `/embed` 正常返回 768 维向量且无 NaN/Inf，`searchSimilar` SQL 无 WHERE 阈值。执行时必须按固定边界顺序定位 Java 调用链分歧点：先复现 Docker HTTP 失败，再确认 `EmbeddingClient.embed(query)` 返回非空有限 768 维向量、`DenseQueryService.search` 收到正数 topK、`ChunkEmbeddingDao.searchSimilar` 生成的 pgvector 字面量可被 DB 接受、`ChunkEmbeddingRepository.searchSimilar` 从 Java 调用返回行数，最后用同一向量字面量在 Docker DB 中直查对比。只在找到 Java 与 DB 行为分歧的最早边界后做最小修复。

对照证据：`retrieval_evidence_test.sh`（同 `retrieveEvidence` 路径）在长跑环境 PASS；UserQuery 对 `evidence-... parent evidence` 这类与 chunk 内容高度重复的 query 返回正确 decimal string sources。说明检索链路整体可用，但对「query 含 chunk 未出现 token」的召回存在上述两处缺陷。

**目标**：让 `query_stub_success_test.sh` 在 Docker HTTP 回归中稳定通过；使含疑问词/扩展词的 query 在 Sparse 与 Dense 两条召回路径上都修复到可命中写入 chunk 的状态。不得只依赖其中一路成功替代另一路缺陷修复。

## 范围

- 修正 Sparse 检索的 AND 过严语义（优先使用 PostgreSQL 支持且保留参数绑定的部分匹配 tsquery 构造；候选包括 `websearch_to_tsquery`、经验证满足需求的 `phraseto_tsquery`、或显式安全 OR 构造）。
- 按固定诊断顺序定位并修复 Java 层 Dense=0 的精确根因，使 Dense 路对有效 query 向量返回 topK。
- 补充覆盖「query 含 chunk 未出现 token」「疑问句」「中英混合 + 特殊标识符」的召回测试。
- 更新受影响的 Docker HTTP 回归脚本断言（如有）。

## 非目标

- 不改变 plan_15 的 `BIGINT` ID 类型或 decimal string 边界。
- 不重构 Retrieval 架构（Sparse/Dense/RRF/Rerank 分层不变）。
- 不调整 Rerank 模型、Embedding 模型或 topN 默认值。
- 不修复 `docker_readiness_test.sh` 的计时 bug（归 `plan_10.hotfix_1`）。

## 前置依赖

- **执行前置 Plan**：`plan_6`
- 所属主 Plan 已完成；`plan_15` 已完成且其 ID 切换实现经独立验收确认与本缺陷无关。

## 文件边界

- `crag-storage/src/main/java/ai/cerbur/crag/storage/repository/ChunkFtsRepository.java`
- `crag-storage/src/main/java/ai/cerbur/crag/storage/ChunkFtsDao.java`
- `crag-retrieval/src/main/java/ai/cerbur/crag/retrieval/sparse/SparseQueryService.java`
- `crag-retrieval/src/main/java/ai/cerbur/crag/retrieval/dense/DenseQueryService.java`
- `crag-retrieval/src/main/java/ai/cerbur/crag/retrieval/embedding/SidecarEmbeddingClient.java`
- `crag-storage/src/test/java/**`（补充 FTS/vector DAO 回归测试）
- `crag-retrieval/src/test/java/**`（补充召回测试）
- `scripts/tests/http/query_stub_success_test.sh`（仅断言同步，不改计时逻辑）
- `docs/superpowers/specs/2026-06-25-plan-6-hotfix-7-retrieval-recall-design.md`（设计依据）

## 关联范围与规模说明

- 关联 `plan_4`（Sparse 写入侧 `chunk_fts` 的 CJK SQL 与查询侧 CJK 预理需保持一致）、`plan_7`（UserQuery 编排，调 `retrieveEvidence`）。
- 修正集中在 Retrieval 读链路（crag-storage 的 FTS 查询 + crag-retrieval 的 Sparse/Dense 查询服务），≤2 个业务模块、2 个有效任务，不升级为主 Plan。

## 关键决策

- Sparse 修正优先选择保持写入侧 CJK 预处理不变、只调整查询侧 tsquery 构造的方案，避免重建已入库的 `chunk_fts` 数据。
- Sparse 查询侧必须保留参数绑定，不得把原始用户 query 拼接进 SQL；空 query 或 tokenless query 不得变成全表扫描。
- Dense=0 必须先以复现命令确认根因（RED），再按 `EmbeddingClient → DenseQueryService → ChunkEmbeddingDao → ChunkEmbeddingRepository → Docker DB 直查` 顺序定位分歧边界并最小修复（GREEN），不得盲目放宽断言、加无依据等待、引入 Dense 分数阈值或用 Sparse fallback 掩盖 Dense 空结果。
- Dense 与 Sparse 两路都属于完成门槛；`query_stub_success_test.sh` 仅因一路命中而 PASS 不代表本 Hotfix 完成。
- 修复不得削弱既有 `retrieval_evidence_test.sh` 的稳定性，不得引入新的非确定性。

## 未决问题

无阻塞执行的问题。Dense=0 的真实分歧边界需在执行 session 按本 Plan 的固定诊断顺序确认；确认结果必须记录到验收记录中。

## 风险与回滚

- **风险**：改 tsquery 构造影响既有 Sparse 排序稳定性或过度召回。预防：保留 `ts_rank` 排序与 `chunk_id` tie-breaker，补充对比测试，Docker 回归确认新写入 parent 被命中。回滚：单独 revert 查询侧 tsquery 改动，恢复 `plainto_tsquery`。
- **风险**：Dense 修复误改 `toPgVectorString` 精度影响存储向量。预防：存储路径（写入）与查询路径分离，查询向量字面量不写入；DAO 测试覆盖有效向量映射和非法向量语义。回滚：单独 revert Dense 路改动。
- **风险**：诊断日志泄露完整文档、Prompt 或向量。预防：日志只记录维度、行数、chunkId、runId 和阶段计数，不记录完整文本或向量值。回滚：revert 诊断日志改动。
- **风险**：召回测试 flaky。预防：用确定性 query 文本与唯一 RUN_ID，避免依赖时序。

## 测试与验证计划

- 纯单元/组件测试（`./gradlew test`）：补充或调整「query 含未出现 token 仍命中」「疑问句命中」「中英混合 + 特殊标识符命中」「Dense 对有效向量返回 topK」「Sparse 排序仍按 score DESC、chunk_id ASC 稳定」用例。
- Docker HTTP 回归：`docker compose up -d --build` 后执行 `scripts/tests/http/query_stub_success_test.sh`、`scripts/tests/http/retrieval_evidence_test.sh`，两者均须稳定 PASS。
- 验证证据记录 `Retrieval search — sparse=X, dense=Y` 日志，确认含疑问词 query 下 Sparse 与 Dense 的修复证据分别成立；不得只记录单路成功。
- Dense 验收记录必须写明真实根因、Java 与 Docker DB 直查的分歧边界、修复 commit。
- Sparse 验收记录必须写明最终选择的 tsquery 构造方式、为何不会拼接原始用户输入、为何空 query 不会全表扫描。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 6.hotfix_7.1 | Dense Java 层召回诊断与修复 | ⏳ 待开始 | — | — |
| 6.hotfix_7.2 | Sparse partial-match 查询语义修正与召回测试 | ⏳ 待开始 | — | — |

整体进度：0 / 2（0%）

## 6.hotfix_7.1 Dense Java 层召回诊断与修复

**目标**：消除 `RetrievalService` 日志中 `dense=0` 的非预期空结果，使 Dense 路对有效 query 向量返回 topK。
**前置任务**：无
**范围**：在 `RetrievalService.retrieveInternal → EmbeddingClient.embed → DenseQueryService.search → ChunkEmbeddingDao.searchSimilar → ChunkEmbeddingRepository.searchSimilar` 链路定位 query 向量为何在 Java 调用时未命中（DB 层 `searchSimilar` 直接执行返回 `dist=0.1537` 行），并最小修复。执行步骤：1）用 Docker HTTP 复现并保留 `dense=0` RED 证据；2）确认 embedding 向量非空、有限、维度符合模型输出；3）确认 `DenseQueryService.search` 收到正数 topK；4）确认 `ChunkEmbeddingDao` 生成的 pgvector 字面量可用于 DB 直查；5）确认 repository Java 调用行数；6）对同一 vector literal 执行 Docker DB 直查；7）只在最早分歧边界修复。
**非目标**：不改变 `searchSimilar` 的 cosine SQL 与无阈值设计，除非诊断证明 Java 参数绑定或 cast 路径正是分歧边界；不改变 ID 类型；不新增 Dense 分数阈值；不以 Sparse fallback 掩盖 Dense 空结果。
**验收标准**：含疑问词 query 触发后 `Retrieval search` 日志或等价测试证据显示 `dense>0`；验收记录写明 Dense 真实根因与 Java/DB 分歧边界；`query_stub_success_test.sh` 在 Docker 链路 PASS；既有 Dense 单元/组件测试不退化。
**验证方式**：`./gradlew :crag-storage:test :crag-retrieval:test`；`docker compose up -d --build` + `bash scripts/tests/http/query_stub_success_test.sh`；查 rag-service 日志 `Retrieval search — sparse=*, dense=*`；必要时用 `docker compose exec db psql ...` 对同一 vector literal 直查并记录摘要。
**涉及文件**：`crag-retrieval/src/main/java/ai/cerbur/crag/retrieval/api/RetrievalService.java`（仅当需要安全计数日志或诊断边界时）、`crag-retrieval/src/main/java/ai/cerbur/crag/retrieval/dense/DenseQueryService.java`、`crag-storage/src/main/java/ai/cerbur/crag/storage/ChunkEmbeddingDao.java`、`crag-storage/src/main/java/ai/cerbur/crag/storage/repository/ChunkEmbeddingRepository.java`（仅当诊断证明参数绑定/cast 为根因时）、`crag-retrieval/src/main/java/ai/cerbur/crag/retrieval/embedding/SidecarEmbeddingClient.java`、`crag-storage/src/test/java/**`、`crag-retrieval/src/test/java/**`

## 6.hotfix_7.2 Sparse partial-match 查询语义修正与召回测试

**目标**：使含 chunk 未出现 token（疑问词、扩展词）的 query 仍能通过 Sparse 命中。
**前置任务**：6.hotfix_7.1
**范围**：将 `ChunkFtsRepository.searchFts` 的 `@@ plainto_tsquery(...)` 调整为支持部分匹配的构造，保持写入侧 CJK 预处理、`ts_rank` 排序和 `chunk_id` tie-breaker 不变；补充召回测试。实现优先级：1）PostgreSQL 标准函数且保留参数绑定；2）查询侧 CJK 预处理与写入侧语义一致；3）不拼接原始用户输入；4）空 query 或 tokenless query 返回空结果而非全表扫描。候选包括 `websearch_to_tsquery`、经实验证明满足 partial-match 的 `phraseto_tsquery`、或安全 token OR 构造。
**非目标**：不重建已入库 `chunk_fts` 数据；不改 CJK 写入预处理；不引入新表；不修改 RRF/Rerank 以掩盖 Sparse 召回不足。
**验收标准**：含疑问词 query 的 Sparse 路有单独证据显示 `sparse>0`；`ts_rank` 排序对既有用例稳定；新增召回单元/组件测试 PASS；Docker HTTP 回归中 `query_stub_success_test.sh` 与 `retrieval_evidence_test.sh` 均 PASS。
**验证方式**：`./gradlew :crag-storage:test :crag-retrieval:test`；`docker compose up -d --build` + `bash scripts/tests/http/query_stub_success_test.sh` + `bash scripts/tests/http/retrieval_evidence_test.sh`；查 rag-service 日志 `Retrieval search — sparse=*, dense=*`；必要时用 `docker compose exec db psql ...` 验证最终 tsquery 对目标 query 的匹配行为。
**涉及文件**：`crag-storage/src/main/java/ai/cerbur/crag/storage/repository/ChunkFtsRepository.java`、`crag-storage/src/main/java/ai/cerbur/crag/storage/ChunkFtsDao.java`、`crag-retrieval/src/main/java/ai/cerbur/crag/retrieval/sparse/SparseQueryService.java`、`crag-storage/src/test/java/**`、`crag-retrieval/src/test/java/**`、`scripts/tests/http/query_stub_success_test.sh`（仅当断言需同步，禁止放宽核心命中断言）

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-25 | macOS, Docker | `docker compose up -d --build` + `bash scripts/tests/http/query_stub_success_test.sh` | 失败 | 第四次独立验收 Docker 重跑发现：query 轮询 30 次未命中，`Retrieval search — sparse=0, dense=0` |
| 2026-06-25 | macOS, 代码审查 | `git show 1ee473f` / `git show 38ed9e9` | 通过 | plan_15 未触及检索 SQL/RRF/Rerank 生产逻辑，只改 ID 类型；DAO `((Number)row[0]).longValue()` 映射正确——本缺陷非 plan_15 引入 |
| 2026-06-25 | macOS, Docker | `docker compose exec db psql ... searchSimilar`（stdin 直传 query 向量） | 通过 | DB 层 Dense 对 query 向量返回 `dist=0.1537` 行，证明 SQL 正常，dense=0 在 Java 层 |
| 2026-06-25 | macOS, Docker | `psql ... plainto_tsquery('simple', ...)` 行为分析 | 通过 | Sparse=0 根因为 `plainto_tsquery` AND 语义 + query 含 chunk 未出现 token |
| 2026-06-25 | macOS, 文档设计 | `docs/superpowers/specs/2026-06-25-plan-6-hotfix-7-retrieval-recall-design.md` | 通过 | 已确认 A1 方案：保留 2 个有效任务，Dense 走固定边界诊断 + 最小修复，Sparse 走查询侧 partial-match 修复；两路都必须有独立修复证据 |

## 阻塞记录

无。本 Hotfix 为非优先项，登记后等待闲时执行；当前不阻塞任何 Plan（详见索引阻塞说明）。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-25 | 创建 Hotfix | plan_15 第四次独立验收 Docker HTTP 重跑发现 query_stub_success_test 失败，根因为 plan_6 检索链路 Sparse AND 语义过严 + Dense Java 层 0 结果（均非 plan_15 引入） | 初始范围；状态 ready，非优先闲时修复 |
| 2026-06-25 | 细化 Hotfix 执行计划 | 根据已批准设计文档 `docs/superpowers/specs/2026-06-25-plan-6-hotfix-7-retrieval-recall-design.md` 收敛 A1 方案 | 状态仍为 ready；任务数仍为 2；明确 Dense 固定诊断顺序、Sparse partial-match 约束与双路完成门槛 |
