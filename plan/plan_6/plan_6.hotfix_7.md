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
2. **Dense=0**：Java 层 `DenseQueryService.search` 返回空。但 DB 层 `ChunkEmbeddingRepository.searchSimilar` 对 Sidecar 产生的 query 向量直接执行返回行（实测 `dist=0.1537`），Sidecar `/embed` 正常返回 768 维向量且无 NaN/Inf，`searchSimilar` SQL 无 WHERE 阈值。精确根因需在执行 session 通过 `superpowers:systematic-debugging` 在 Java 调用链定位（候选：`toPgVectorString` 的 `Float.toString` 输出在容器内实际形态、`embeddingClient` 在 rag-service 容器内的返回、或 `retrieveInternal` 调用时的向量状态）。

对照证据：`retrieval_evidence_test.sh`（同 `retrieveEvidence` 路径）在长跑环境 PASS；UserQuery 对 `evidence-... parent evidence` 这类与 chunk 内容高度重复的 query 返回正确 decimal string sources。说明检索链路整体可用，但对「query 含 chunk 未出现 token」的召回存在上述两处缺陷。

**目标**：让 `query_stub_success_test.sh` 在 Docker HTTP 回归中稳定通过；使含疑问词/扩展词的 query 能通过 Sparse 或 Dense 至少一路命中写入的 chunk。

## 范围

- 修正 Sparse 检索的 AND 过严语义（改用支持部分匹配的 `websearch_to_tsquery` / `phraseto_tsquery` 或显式 OR 构造，或降低 `plainto_tsquery` 全 token AND 要求）。
- 定位并修复 Java 层 Dense=0 的精确根因，使 Dense 路对有效 query 向量返回 topK。
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
- `crag-retrieval/src/test/java/**`（补充召回测试）
- `scripts/tests/http/query_stub_success_test.sh`（仅断言同步，不改计时逻辑）

## 关联范围与规模说明

- 关联 `plan_4`（Sparse 写入侧 `chunk_fts` 的 CJK SQL 与查询侧 CJK 预理需保持一致）、`plan_7`（UserQuery 编排，调 `retrieveEvidence`）。
- 修正集中在 Retrieval 读链路（crag-storage 的 FTS 查询 + crag-retrieval 的 Sparse/Dense 查询服务），≤2 个业务模块、2 个有效任务，不升级为主 Plan。

## 关键决策

- Sparse 修正优先选择保持写入侧 CJK 预处理不变、只调整查询侧 tsquery 构造的方案，避免重建已入库的 `chunk_fts` 数据。
- Dense=0 必须先以复现命令确认根因（RED），再最小修复（GREEN），不得盲目放宽断言或加无依据等待。
- 修复不得削弱既有 `retrieval_evidence_test.sh` 的稳定性，不得引入新的非确定性。

## 未决问题

- Dense=0 的精确 Java 层根因待执行 session 定位（候选见背景）；定位结果可能将子任务范围收敛到单一文件。

## 风险与回滚

- **风险**：改 tsquery 构造影响既有 Sparse 排序稳定性。预防：保留 `ts_rank` 排序与 tie-breaker，补充对比测试。回滚：revert 查询侧 tsquery 改动，恢复 `plainto_tsquery`。
- **风险**：Dense 修复误改 `toPgVectorString` 精度影响存储向量。预防：存储路径（写入）与查询路径分离，查询向量字面量不写入。回滚：revert Dense 路改动。
- **风险**：召回测试 flaky。预防：用确定性 query 文本与唯一 RUN_ID，避免依赖时序。

## 测试与验证计划

- 纯单元/组件测试（`./gradlew test`）：补充「query 含未出现 token 仍命中」「疑问句命中」「Dense 对有效向量返回 topK」用例。
- Docker HTTP 回归：`docker compose up -d --build` 后执行 `scripts/tests/http/query_stub_success_test.sh`、`scripts/tests/http/retrieval_evidence_test.sh`，两者均须稳定 PASS。
- 验证证据记录 `Retrieval search — sparse=X dense=Y` 日志，确认含疑问词 query 下 sparse 或 dense 至少一路非 0。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 6.hotfix_7.1 | 定位并修复 Dense 检索 Java 层 0 结果 | ⏳ 待开始 | — | — |
| 6.hotfix_7.2 | Sparse 检索 AND 语义过严修正与召回测试 | ⏳ 待开始 | — | — |

整体进度：0 / 2（0%）

## 6.hotfix_7.1 定位并修复 Dense 检索 Java 层 0 结果

**目标**：消除 `RetrievalService` 日志中 `dense=0` 的非预期空结果，使 Dense 路对有效 query 向量返回 topK。
**前置任务**：无
**范围**：在 `retrieveInternal → DenseQueryService.search → ChunkEmbeddingDao.searchSimilar` 链路定位 query 向量为何在 Java 调用时未命中（DB 层 `searchSimilar` 直接执行返回 `dist=0.1537` 行），并最小修复。
**非目标**：不改变 `searchSimilar` 的 cosine SQL 与无阈值设计；不改变 ID 类型。
**验收标准**：含疑问词 query 触发后 `Retrieval search` 日志 `dense>0`；`query_stub_success_test.sh` 在 Docker 链路 PASS；既有 Dense 单测/组件测试不退化。
**验证方式**：`./gradlew :crag-retrieval:test`；`docker compose up -d --build` + `bash scripts/tests/http/query_stub_success_test.sh`；查 rag-service 日志 `Retrieval search — sparse=*, dense=*`。
**涉及文件**：`crag-retrieval/src/main/java/ai/cerbur/crag/retrieval/dense/DenseQueryService.java`、`crag-storage/src/main/java/ai/cerbur/crag/storage/ChunkEmbeddingDao.java`、`crag-retrieval/src/main/java/ai/cerbur/crag/retrieval/embedding/SidecarEmbeddingClient.java`、相关测试

## 6.hotfix_7.2 Sparse 检索 AND 语义过严修正与召回测试

**目标**：使含 chunk 未出现 token（疑问词、扩展词）的 query 仍能通过 Sparse 命中。
**前置任务**：6.hotfix_7.1
**范围**：将 `ChunkFtsRepository.searchFts` 的 `@@ plainto_tsquery(...)` 调整为支持部分匹配的构造（`websearch_to_tsquery` 或 OR 语义），保持写入侧 CJK 预处理与 `ts_rank` 排序/tie-breaker 不变；补充召回测试。
**非目标**：不重建已入库 `chunk_fts` 数据；不改 CJK 写入预处理；不引入新表。
**验收标准**：含疑问词 query 的 Sparse 路 `sparse>0` 或经由 Dense+RRF 命中；`ts_rank` 排序对既有用例稳定；新增召回单测/组件测试 PASS。
**验证方式**：`./gradlew :crag-storage:test :crag-retrieval:test`；`bash scripts/tests/http/query_stub_success_test.sh`、`bash scripts/tests/http/retrieval_evidence_test.sh`。
**涉及文件**：`crag-storage/src/main/java/ai/cerbur/crag/storage/repository/ChunkFtsRepository.java`、`crag-storage/src/main/java/ai/cerbur/crag/storage/ChunkFtsDao.java`、`crag-retrieval/src/main/java/ai/cerbur/crag/retrieval/sparse/SparseQueryService.java`、相关测试

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-25 | macOS, Docker | `docker compose up -d --build` + `bash scripts/tests/http/query_stub_success_test.sh` | 失败 | 第四次独立验收 Docker 重跑发现：query 轮询 30 次未命中，`Retrieval search — sparse=0, dense=0` |
| 2026-06-25 | macOS, 代码审查 | `git show 1ee473f` / `git show 38ed9e9` | 通过 | plan_15 未触及检索 SQL/RRF/Rerank 生产逻辑，只改 ID 类型；DAO `((Number)row[0]).longValue()` 映射正确——本缺陷非 plan_15 引入 |
| 2026-06-25 | macOS, Docker | `docker compose exec db psql ... searchSimilar`（stdin 直传 query 向量） | 通过 | DB 层 Dense 对 query 向量返回 `dist=0.1537` 行，证明 SQL 正常，dense=0 在 Java 层 |
| 2026-06-25 | macOS, Docker | `psql ... plainto_tsquery('simple', ...)` 行为分析 | 通过 | Sparse=0 根因为 `plainto_tsquery` AND 语义 + query 含 chunk 未出现 token |

## 阻塞记录

无。本 Hotfix 为非优先项，登记后等待闲时执行；当前不阻塞任何 Plan（详见索引阻塞说明）。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-25 | 创建 Hotfix | plan_15 第四次独立验收 Docker HTTP 重跑发现 query_stub_success_test 失败，根因为 plan_6 检索链路 Sparse AND 语义过严 + Dense Java 层 0 结果（均非 plan_15 引入） | 初始范围；状态 ready，非优先闲时修复 |
