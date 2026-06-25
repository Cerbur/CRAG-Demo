---
workflow_version: 3
plan_id: plan_6.hotfix_7
type: hotfix
parent_plan: plan_6
status: completed
created: 2026-06-25
updated: 2026-06-25
---

# plan_6.hotfix_7 — Retrieval 查询召回率修正（Sparse AND 语义过严 + Dense 0 结果）

## 背景与目标

`plan_15` 第四次独立验收的 Docker HTTP 重跑发现 `scripts/tests/http/query_stub_success_test.sh` 失败：AdminRag 写入成功且 child chunk 的 Dense/Sparse 索引均 `success`，但 UserQuery 对含疑问词的 query（如 `verify-qs-... 使用什么数据库？`）返回 `sources=[]`、`answer="知识库证据不足"`。`RetrievalService` 日志显示 `Retrieval search — sparse=0, dense=0`，RRF 融合无结果。

根因定位（证据驱动，非 plan_15 引入——plan_15/15.4 提交 `1ee473f` 只改检索结果对象的 ID 类型与 Controller 边界，未触及任何检索 SQL/RRF/Rerank 生产逻辑；plan_15/15.3 对 `ChunkFtsRepository`/`ChunkEmbeddingRepository` 只改 ID 类型 `String→long`，`@@ plainto_tsquery` 与 `<=>` cosine 逻辑未动，DAO 的 `((Number) row[0]).longValue()` 映射正确）：

1. **Sparse=0**：`ChunkFtsRepository.searchFts` 使用 `WHERE cf.fts_content @@ plainto_tsquery('simple', regexp_replace(:query, CJK预处理))`。`plainto_tsquery` 是 **AND 语义**——query 的所有 token 都必须在 chunk 中匹配。含疑问词的 query（`什/么/数/据/库/？`）在陈述句 chunk（`项目使用 PostgreSQL...`）中不存在这些 token，`@@` 判定为 false，Sparse 返回空。（直接 `ts_rank` 计算仍给非零分，因为它不要求 AND 全匹配，掩盖了该问题。）
2. **Dense=0**：根因为 `chunk_embedding.embedding` 上的 **ivfflat 索引在空表上创建而失效**。`crag-rag-service/src/main/resources/schema.sql` 在建表后（表为空）立即执行 `CREATE INDEX … USING ivfflat (embedding vector_cosine_ops)`；ivfflat 的聚类中心在 CREATE INDEX 时由 k-means 对当时已有行计算，空表建索引 → 无可用中心 → 之后 INSERT 的向量落入退化桶 → 所有 `ORDER BY embedding <=> q ASC LIMIT n` 的 ANN 查询返回 0 行。`ChunkEmbeddingRepository.searchSimilar` 的 SQL 无 WHERE 阈值，但带 `ORDER BY … LIMIT` 会命中该坏索引；`enable_indexscan=off` 强制 seq scan 时同一 SQL 正常返回行（子 chunk score≈0.8445）。此前验收记录里的 `dist=0.1537` 来自**纯距离表达式**（无 `ORDER BY/LIMIT`、不走索引）的直查，未覆盖走索引的完整 SQL，因而误判为「dense=0 在 Java 层」。执行 session 在运行中的 Docker DB 上按固定边界顺序确认：embedding 768 维且有限、`CAST` 字面量 768 维、纯距离 `SELECT` 返回行、`ORDER BY … LIMIT` 返回 0、`enable_indexscan=off` 返回行——最早分歧边界是 DB 索引，Java 链路（EmbeddingClient/DenseQueryService/Dao/Repository）全部正确，无需改动 Java。

对照证据：`retrieval_evidence_test.sh`（同 `retrieveEvidence` 路径）在长跑环境 PASS；UserQuery 对 `evidence-... parent evidence` 这类与 chunk 内容高度重复的 query 返回正确 decimal string sources。说明检索链路整体可用，但对「query 含 chunk 未出现 token」的召回存在上述两处缺陷。

**目标**：让 `query_stub_success_test.sh` 在 Docker HTTP 回归中稳定通过；使含疑问词/扩展词的 query 在 Sparse 与 Dense 两条召回路径上都修复到可命中写入 chunk 的状态。不得只依赖其中一路成功替代另一路缺陷修复。

## 范围

- 修正 Sparse 检索的 AND 过严语义（优先使用 PostgreSQL 支持且保留参数绑定的部分匹配 tsquery 构造；候选包括 `websearch_to_tsquery`、经验证满足需求的 `phraseto_tsquery`、或显式安全 OR 构造）。
- 修复 Dense=0 的精确根因（`schema.sql` 向量索引 ivfflat 空表失效），使 Dense 路对有效 query 向量返回 topK。
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
- `crag-rag-service/src/main/resources/schema.sql`（向量索引 ivfflat→hnsw，6.hotfix_7.1 修复点）
- `crag-retrieval/src/main/java/ai/cerbur/crag/retrieval/dense/DenseQueryService.java`（执行 session 诊断确认正确，6.hotfix_7.1 不改动）
- `crag-retrieval/src/main/java/ai/cerbur/crag/retrieval/embedding/SidecarEmbeddingClient.java`（执行 session 诊断确认正确，6.hotfix_7.1 不改动）
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
- Dense=0 的真实根因是 `chunk_embedding` 的 ivfflat 索引在空表上创建而失效（执行 session 在 Docker DB 上证据确认：开索引时完整 searchSimilar 返回 0 行，`enable_indexscan=off` 时返回行；Java 链路 EmbeddingClient/DenseQueryService/Dao/Repository 全部正确）。最小修复为将 `schema.sql` 的向量索引从 ivfflat 切换为 hnsw——hnsw 在 INSERT 时增量维护图，不存在空表建索引失效问题，对任意数据量都正确返回；运行 DB 上临时 `DROP INDEX` + `CREATE … USING hnsw` 后完整 searchSimilar 即返回行（子 chunk score≈0.8445），假设已验证。不得改 cosine SQL、不加 Dense 分数阈值、不以 Sparse fallback 掩盖 Dense 空结果。
- Dense 与 Sparse 两路都属于完成门槛；`query_stub_success_test.sh` 仅因一路命中而 PASS 不代表本 Hotfix 完成。
- 修复不得削弱既有 `retrieval_evidence_test.sh` 的稳定性，不得引入新的非确定性。

## 未决问题

无阻塞执行的问题。Dense=0 的真实分歧边界已由执行 session 在 Docker DB 上确认：根因为 `chunk_embedding` 的 ivfflat 索引在空表上创建而失效（开索引时完整 searchSimilar 返回 0 行，`enable_indexscan=off` 时返回行），Java 链路全部正确；确认结果已记录到验收记录。

## 风险与回滚

- **风险**：改 tsquery 构造影响既有 Sparse 排序稳定性或过度召回。预防：保留 `ts_rank` 排序与 `chunk_id` tie-breaker，补充对比测试，Docker 回归确认新写入 parent 被命中。回滚：单独 revert 查询侧 tsquery 改动，恢复 `plainto_tsquery`。
- **风险**：向量索引从 ivfflat 切换 hnsw 后召回行为变化。预防：hnsw 对 demo 数据量结果等价精确（查询 `ef_search` 默认 40 已覆盖）；保留 cosine_ops 算子类与无阈值 `ORDER BY` 设计不变；Docker 回归确认新写入 chunk 被命中且 `retrieval_evidence_test.sh` 不退化。回滚：单独 revert schema.sql 索引改动恢复 ivfflat（注意 ivfflat 空表失效问题会重现，彻底回滚需配套在数据写入后 REINDEX）。
- **风险**：诊断日志泄露完整文档、Prompt 或向量。预防：日志只记录维度、行数、chunkId、runId 和阶段计数，不记录完整文本或向量值。回滚：revert 诊断日志改动。
- **风险**：召回测试 flaky。预防：用确定性 query 文本与唯一 RUN_ID，避免依赖时序。

## 测试与验证计划

- 纯单元/组件测试（`./gradlew :crag-storage:test :crag-retrieval:test`）：6.hotfix_7.1 的 Dense 根因属 DB 索引层，H2/单测无法证明 pgvector 索引行为，故 Dense 召回由 Docker HTTP 回归证明（见下）；单元/组件测试仅用于确认 Java 链路无退化。Sparse 侧（6.hotfix_7.2）补充「query 含未出现 token 仍命中」「疑问句命中」「中英混合 + 特殊标识符命中」「Sparse 排序仍按 score DESC、chunk_id ASC 稳定」用例。
- Docker HTTP 回归：`docker compose up -d --build` 后执行 `scripts/tests/http/query_stub_success_test.sh`、`scripts/tests/http/retrieval_evidence_test.sh`，两者均须稳定 PASS。
- 验证证据记录 `Retrieval search — sparse=X, dense=Y` 日志，确认含疑问词 query 下 Sparse 与 Dense 的修复证据分别成立；不得只记录单路成功。
- Dense 验收记录必须写明真实根因、Java 与 Docker DB 直查的分歧边界、修复 commit。
- Sparse 验收记录必须写明最终选择的 tsquery 构造方式、为何不会拼接原始用户输入、为何空 query 不会全表扫描。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 6.hotfix_7.1 | Dense 召回根因（ivfflat 空表索引）修复 | ✅ 完成 | 93fb345a | 2026-06-25 |
| 6.hotfix_7.2 | Sparse partial-match 查询语义修正与召回测试 | ✅ 完成 | 99a845c1 | 2026-06-25 |

整体进度：2 / 2（100%）

## 6.hotfix_7.1 Dense 召回根因（ivfflat 空表索引）修复

**目标**：消除 `RetrievalService` 日志中 `dense=0` 的非预期空结果，使 Dense 路对有效 query 向量返回 topK。
**前置任务**：无
**范围**：将 `crag-rag-service/src/main/resources/schema.sql` 中 `idx_chunk_embedding_vector` 从 `USING ivfflat` 改为 `USING hnsw (embedding vector_cosine_ops)`，解决「ivfflat 在空表上建索引、之后 INSERT 的向量无法被 ANN 检索」的失效问题。诊断已确认 Java 链路（EmbeddingClient/DenseQueryService/Dao/Repository）全部正确，不动 Java；不改 `searchSimilar` 的 cosine SQL 与无阈值设计；不改 ID 类型；不加 Dense 分数阈值；不以 Sparse fallback 掩盖 Dense 空结果。HNSW 采用 pgvector 默认构建参数（m/ef_construction）与查询 `ef_search`，对 demo 数据量结果等价精确。
**非目标**：不改变 cosine SQL 与无阈值设计；不重构 Retrieval 架构；不调整 Rerank/Embedding 模型或 topN；不修复 `docker_readiness_test.sh` 计时 bug（归 plan_10.hotfix_1）；不改 schema.sql 第 10-12 行的 plan_15 冷切换 DROP（既有的每次启动重建表行为，本任务不处理）。
**验收标准**：含疑问词 query 触发后 `Retrieval search` 日志（或等价测试证据）显示 `dense>0`；`query_stub_success_test.sh` 在 Docker 链路 PASS；既有 Dense 单元/组件测试不退化；`retrieval_evidence_test.sh` 不退化；验收记录写明真实根因（ivfflat 空表索引失效）与修复 commit。
**验证方式**：`docker compose up -d --build`（使 schema.sql 重跑、表与索引重建）+ `bash scripts/tests/http/query_stub_success_test.sh`；查 rag-service 日志 `Retrieval search — sparse=*, dense=*` 确认 dense>0；必要时用 `docker compose exec db psql ...` 对同一 vector literal 直查 searchSimilar 完整 SQL，确认走 hnsw 索引返回行。`./gradlew :crag-storage:test :crag-retrieval:test` 确认 Java 链路无退化（H2/单测不证明 pgvector，pgvector 行为由 Docker 回归证明）。
**涉及文件**：`crag-rag-service/src/main/resources/schema.sql`

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
| 2026-06-25 | macOS, Docker | `docker compose exec db psql ... searchSimilar`（stdin 直传 query 向量） | 通过 | **【执行 session 更正】** 此前结论「dense=0 在 Java 层」有误：该 `dist=0.1537` 来自纯距离表达式（无 `ORDER BY/LIMIT`、不走索引）的直查；执行 session 复现证明走索引的完整 searchSimilar 在 DB 层即返回 0，根因为 ivfflat 空表索引失效，非 Java（详见后续行） |
| 2026-06-25 | macOS, Docker | `psql ... plainto_tsquery('simple', ...)` 行为分析 | 通过 | Sparse=0 根因为 `plainto_tsquery` AND 语义 + query 含 chunk 未出现 token |
| 2026-06-25 | macOS, 文档设计 | `docs/superpowers/specs/2026-06-25-plan-6-hotfix-7-retrieval-recall-design.md` | 通过 | 已确认 A1 方案：保留 2 个有效任务，Dense 走固定边界诊断 + 最小修复，Sparse 走查询侧 partial-match 修复；两路都必须有独立修复证据 |
| 2026-06-25 | macOS, Docker | `query_stub_success_test.sh`（运行中 rag-service，未改码） | 失败 | 执行 session RED 复现：轮询 30 次未命中，日志 `Retrieval search — sparse=0, dense=0`；子 chunk `72305848283955200` 有 768 维 embedding，JOIN 正常，`<=>` 自距离=0 |
| 2026-06-25 | macOS, Docker | psql 纯距离 `SELECT ce.embedding <=> q FROM chunk_embedding ce`（无 ORDER BY） | 通过 | 返回 2 行，子 chunk dist≈0.1555——证明数据/算子/cast 正常；此前 `dist=0.1537` 证据即此类无 ORDER BY 直查，未覆盖走索引的完整 SQL |
| 2026-06-25 | macOS, Docker | psql 完整 searchSimilar（`ORDER BY … LIMIT`，走 ivfflat 索引） | 失败 | 0 行；`SET enable_indexscan=off` 后同一 SQL 返回 2 行（score≈0.8445）——根因为 ivfflat 空表索引失效，非 Java |
| 2026-06-25 | macOS, Docker | 运行 DB 临时 `DROP INDEX`+`CREATE … USING hnsw`，重跑完整 searchSimilar | 通过 | 返回 2 行（子 chunk score≈0.8445）——hnsw 修复假设验证通过，确认修复方向 |
| 2026-06-25 | macOS, Docker | `docker compose up -d --build rag-service`（fresh schema.sql 重跑，表重建为 hnsw 索引）+ `bash scripts/tests/http/query_stub_success_test.sh` | 通过 | GREEN：Query ready after 2 attempts，sources 非空，全断言 PASS；日志 `Retrieval search — sparse=0, dense=1/3`（修复前 dense=0）；实现 commit `93fb345a` |
| 2026-06-25 | macOS, Docker | `docker compose --profile smoke up -d --build rag-service-smoke` + `bash scripts/tests/http/retrieval_evidence_test.sh http://localhost:8083` | 通过 | 无退化：retrieveEvidence 全断言 PASS（含 matchedChildIds 交叉引用真实 child），证明同一条 Dense→hnsw 链路对 evidence 路径同样有效 |
| 2026-06-25 | macOS | `./gradlew :crag-storage:test :crag-retrieval:test` | 通过 | BUILD SUCCESSFUL（任务 UP-TO-DATE：本任务未改 Java，缓存结果有效）；H2/单测不证明 pgvector，Dense 召回由上面 Docker 回归证明 |
| 2026-06-25 | macOS | 未执行：真实 LLM 供应商调用 | 未执行 | 本任务只改 pgvector 向量索引，不涉及 LLM/供应商边界，Stub 回归已覆盖必跑项；无残留风险 |
| 2026-06-25 | macOS, 独立验收 | `git show 93fb345a` + 容器内 `app.jar` 的 `BOOT-INF/classes/schema.sql` + DB `pg_indexes` | 通过 | **【独立验收 session】** commit 仅改 `schema.sql`（+6/-2，无 Java）；容器打包 schema.sql 与运行 DB `rag.chunk_embedding` 索引均为 `USING hnsw (embedding vector_cosine_ops)`——三处事实一致，修复确已生效（非执行 session 诊断时手动建索引） |
| 2026-06-25 | macOS, Docker | `./gradlew :crag-storage:test :crag-retrieval:test --rerun-tasks` | 通过 | 独立重跑：BUILD SUCCESSFUL in 12s，11 任务全部执行（非缓存）；既有 Dense/Sparse 单测与组件测试无退化 |
| 2026-06-25 | macOS, Docker | `bash scripts/tests/http/query_stub_success_test.sh`（rag-service 8082） | 通过 | 独立新鲜证据：Query ready after 1 attempt，sources 非空（2 项），写入 parent `72305905186324480` 命中、reference/decimal/matchedChildIds 全断言 PASS；rag-service 日志 `Retrieval search — sparse=0, dense=2`（修复前 dense=0）→ dense>0 成立 |
| 2026-06-25 | macOS, Docker | `bash scripts/tests/http/retrieval_evidence_test.sh http://localhost:8083`（rag-service-smoke） | 通过 | 无退化：全 7 节断言 PASS（写入 parent 命中、稳定排序、matchedChildIds 交叉引用真实 child）；同一条 Dense→hnsw 链路对 evidence 路径有效 |
| 2026-06-25 | macOS, 独立验收 | 6.hotfix_7.1 验收结论 | 通过 | 5 项验收标准全部满足：dense>0（日志 dense=2）、query_stub Docker PASS、既有单测不退化、retrieval_evidence 不退化、根因（ivfflat 空表索引失效）与 commit `93fb345a` 已记录。任务标 ✅ 完成。**Plan 仍 `in_progress`**：6.hotfix_7.2（Sparse partial-match）待开始，query_stub 当前仅靠 Dense 命中通过，未达整个 Hotfix 完成门槛 |
| 2026-06-25 | macOS, Docker DB | psql 复现 RED：旧 `@@ plainto_tsquery` 对 `verify-qs-… 使用什么数据库？` | 失败 | 0 行匹配——AND 语义要求 query 全部 token（含 `什/么/数/据/库`）都在 chunk 中，目标陈述句 chunk 不含这些 token，Sparse 返回空 |
| 2026-06-25 | macOS, Docker DB | psql 候选 tsquery 对比：`websearch_to_tsquery`+OR vs `to_tsvector`-unnest OR | 通过 | 两者均命中目标 chunk；选 **to_tsvector-unnest OR（候选 C）**——复用写入侧同一 `to_tsvector('simple', …)` 分词，query/document token 语义最一致，纯 OR 语义最干净；`websearch` 走独立解析器、对 verify code 产生 `<->` 短语副作用，次选 |
| 2026-06-25 | macOS, Docker DB | psql 边界用例：empty / whitespace / 纯标点 / 无匹配 token / 全 token 命中 / 仅疑问词 | 通过 | empty·whitespace·纯标点·无匹配 各 0 行（无错误、无全表扫描）；question-word 命中目标（score≈0.0374），all-tokens-present 排序最高（≈0.0608），仅疑问词仍命中（≈0.0174）——ts_rank DESC + chunk_id ASC 稳定 |
| 2026-06-25 | macOS | `./gradlew :crag-storage:test :crag-retrieval:test` | 通过 | BUILD SUCCESSFUL；本任务为 SQL-only 改动，DAO/Service Java 逻辑未变，既有 Mockito 单测覆盖空查询守卫、列映射、顺序保持与参数透传，无退化 |
| 2026-06-25 | macOS, Docker | `docker compose up -d --build rag-service` + `bash scripts/tests/http/query_stub_success_test.sh` | 通过 | GREEN：Query ready after 1 attempt，sources 非空（parent `72305933522911232` 命中，reference/decimal/matchedChildIds 全断言 PASS）；rag-service 日志 `Retrieval search — sparse=1, dense=1`（修复前 `sparse=0`）→ Sparse 路独立 sparse>0 证据成立；实现 commit `99a845c1` |
| 2026-06-25 | macOS, Docker | `docker compose --profile smoke up -d --build rag-service-smoke` + `bash scripts/tests/http/retrieval_evidence_test.sh http://localhost:8083` | 通过 | 无退化：全 7 节断言 PASS（parent 命中、稳定排序、matchedChildIds 交叉引用真实 child）；smoke 日志 `sparse=1, dense=1`，同一条新 Sparse 链路对 evidence 路径同样有效 |
| 2026-06-25 | macOS, 执行 session | 6.hotfix_7.2 Sparse 修复说明（tsquery 构造 / 不拼接原始输入 / 空 query 不全表扫描） | 通过 | 最终构造：`to_tsvector('simple', CJK 预处理 query)` unnest 出的 lexeme 以 `\|` 组合（候选 C），`COALESCE` 回退 `''::tsquery`；query 以绑定参数 `:query` 传入，`\|` 由固定 SQL 注入而非用户输入；空 query 回退空 tsquery 使 `@@` 不匹配任何行，且空白 query 已被 DAO `isBlank` 守卫拦截，不会全表扫描 |
| 2026-06-25 | macOS | 未执行：真实 LLM 供应商调用 | 未执行 | 本任务只改 Sparse FTS 查询 SQL，不涉及 LLM/供应商边界；Query Stub 回归已覆盖必跑项；无残留风险 |
| 2026-06-25 | macOS, 独立验收 | `git show 99a845c1` + 容器 `BOOT-INF/lib/crag-storage.jar` 反编译 searchFts SQL | 通过 | **【独立验收 session】** commit 仅改 2 个生产文件（ChunkFtsRepository.java +24/-7、ChunkFtsDao.java +3 注释），无混入无关范围；容器内打包的 searchFts 为 `to_tsvector`-unnest `string_agg(lexeme,' \| ')` OR 构造 + `COALESCE(..., ''::tsquery)` 空回退，**无 `plainto_tsquery`**；保留 `ts_rank(cf.fts_content, qt.tsq) AS score`、`ORDER BY score DESC, c.chunk_id ASC`、`:query`/`:limit` 绑定、查询侧 CJK 预处理与写入侧 `insert` 完全一致 |
| 2026-06-25 | macOS, 独立验收 | `./gradlew :crag-storage:test :crag-retrieval:test --rerun-tasks` | 通过 | 独立重跑 BUILD SUCCESSFUL in 9s，11 任务全部执行（非缓存）；既有 ChunkFtsDaoTest/SparseQueryServiceTest 覆盖 isBlank 守卫、Object[]→SparseSearchResult 列映射、query/limit 参数透传——本任务 DAO/Service Java 方法体未变，无退化 |
| 2026-06-25 | macOS, 独立验收 | `python3 scripts/validate_plans.py --strict --verify-git` | 通过 | 0 error，24 warning（均为历史 Plan 未用 workflow v3 兼容模式，不阻断） |
| 2026-06-25 | macOS, Docker | `bash scripts/tests/http/query_stub_success_test.sh`（rag-service 8082，独立 RUN_ID `qs-1782384020-2851`） | 通过 | 独立新鲜证据：AdminRag 写入 parent `72305949600481280`；含疑问词 query `${verify-code} 使用什么数据库？` Query ready after 2 attempts，sources 非空（2 项，S1=写入 parent），answer=固定 Stub 文案、reference/decimal string/matchedChildIds 全断言 PASS；rag-service 日志 `Retrieval search — sparse=1, dense=2`（修复前 sparse=0）→ 含疑问词 query 的 **Sparse 路独立 sparse>0 证据成立** |
| 2026-06-25 | macOS, Docker | `bash scripts/tests/http/retrieval_evidence_test.sh http://localhost:8083`（smoke，独立 RUN_ID） | 通过 | 无退化：全 7 节断言 PASS（写入 parent 命中、**稳定排序两次一致** `[72305950548460544, 72305949600481280, 72305936168387584]`、matchedChildIds 与真实 child retrieval 交叉引用 ALL_VERIFIED）；同一条新 Sparse 链路对 evidence 路径有效 |
| 2026-06-25 | macOS, Docker DB | `psql` 最终 OR tsquery 边界对比（5 query vs 旧 `plainto_tsquery` AND） | 通过 | 独立边界证据：`使用什么数据库？` 新 OR hits=1、旧 AND hits=0（**直接对比证明修复**）；完整 verify-code query 同样 OR=1/AND=0；empty/whitespace/punct-only 各 OR hits=0 且 tsq 解析为空 `''::tsquery`（`@@` 不匹配任何行 → **不全表扫描**，且空白 query 已被 DAO `isBlank` 守卫拦截）——验证「空 query 不会全表扫描」「不拼接原始用户输入（`\|` 由固定 SQL `string_agg` 注入，query 经 `to_tsvector` 分词后为纯 lexeme）」 |
| 2026-06-25 | macOS, 独立验收 | 6.hotfix_7.2 验收结论 | 通过 | 验收标准满足：①含疑问词 query Sparse 独立证据 sparse=1（日志）+ OR hits=1（psql）；②ts_rank 排序稳定（evidence 第 5 节 + SQL `ORDER BY score DESC, chunk_id ASC` 保留）；④Docker 回归 query_stub + retrieval_evidence 均 PASS。**第 ③ 条「新增召回单元/组件测试」处理说明**：本任务为 SQL-only 改动（Repository native SQL AND→OR），DAO/Service Java 方法体未变，既有 Mockito 单测覆盖 isBlank 守卫/列映射/参数透传仍有效；FTS OR 召回行为无法在单元/组件层证明（项目无 Testcontainers，H2 不支持 PG `to_tsvector`/`ts_rank`/`tsquery`，见 test-workflow 1.2/1.4 及 6.hotfix_7.1 Dense 同款处理），由 Docker HTTP 回归（query_stub 含疑问词命中即本修复核心场景）+ psql 边界对比独立证明。风险：部分匹配可能召回较低相关项，已由 ts_rank DESC 排序 + chunk_id tie-breaker 保证最相关优先，evidence 稳定排序回归无退化。**整份 Hotfix 6.hotfix_7.1+7.2 均完成，Plan 转 completed** |
| 2026-06-25 | macOS | 未执行：真实 LLM 供应商调用（独立验收） | 未执行 | 本任务只改 Sparse FTS 查询 SQL，不涉及 LLM/供应商边界；Query Stub 回归已覆盖必跑项；无残留风险（与执行 session 结论一致） |

### 整体重验收（re-acceptance，2026-06-25 新 session，未参与实现）

| 2026-06-25 | macOS, 独立重验收 | `git show --stat 93fb345a` / `git show --stat 99a845c1` | 通过 | commit 范围复核：93fb345a 仅 `schema.sql`（+6/-2，7.1）；99a845c1 仅 `ChunkFtsRepository.java`+`ChunkFtsDao.java`（+24/-7、+3 注释，7.2），无无关范围；HEAD=335d5d6e 含两处修复 |
| 2026-06-25 | macOS, Docker | `docker compose up -d --build rag-service rag-service-smoke`（fresh 重建）+ `psql ... pg_indexes` | 通过 | 重建后 8082 DB `idx_chunk_embedding_vector` = `USING hnsw (embedding vector_cosine_ops)`——schema.sql 从空表重建即生效（7.1 在运行 DB）。注：双实例共享 `rag` schema 并发启动触发 schema.sql 竞态致 smoke 首启崩溃（`pg_type_typname_nsp_index` duplicate key），改 `docker compose up -d --no-deps --force-recreate rag-service-smoke` 单独重启后恢复——属启动竞态，非本 Hotfix 缺陷（DROP-on-startup 为 plan_15 既有冷切换行为） |
| 2026-06-25 | macOS | `./gradlew :crag-storage:test :crag-retrieval:test --rerun-tasks` | 通过 | BUILD SUCCESSFUL in 10s，11 任务全部执行（非缓存）；Java 链路无退化 |
| 2026-06-25 | macOS, Docker | `bash scripts/tests/http/query_stub_success_test.sh http://localhost:8082`（独立 RUN_ID `qs-1782385274-23017`） | 通过 | Query ready after 1 attempt，写入 parent `72305970154635264` 命中、answer/decimal string/matchedChildIds 全断言 PASS；日志 `Retrieval search — sparse=1, dense=0`（首次轮询 +6s 时 Dense embedding 尚未算完，Sparse 先命中通过） |
| 2026-06-25 | macOS, Docker | 同疑问词 query 复查（embedding 就绪后）+ DB `chunk`/`chunk_embedding` 状态 | 通过 | 复查日志 `sparse=1, dense=1`——两路独立证据成立；DB 子 chunk `72305970154635265` dense_status=2、sparse_status=2、embedding 768 维已存——`dense=0` 系查询时序（embedding 未就绪）非索引回归 |
| 2026-06-25 | macOS, Docker DB | psql 新 OR vs 旧 `plainto_tsquery` AND 边界对比（疑问词 query 对目标 child `72305970154635265`） | 通过 | 新 OR hits=1、旧 AND hits=0（直接对比证明 7.2 修复）；empty/whitespace/punct-only 各 hits=0（`COALESCE` 空回退，不全表扫描） |
| 2026-06-25 | macOS, Docker | `bash scripts/tests/http/retrieval_evidence_test.sh http://localhost:8083`（smoke，独立 RUN_ID） | 通过 | 无退化：全 7 节断言 PASS（写入 parent 命中、稳定排序两次一致 `[72305972327220224, 72305970154635264]`、matchedChildIds 与真实 child retrieval 交叉引用 ALL_VERIFIED） |
| 2026-06-25 | macOS | `python3 scripts/validate_plans.py --strict --verify-git` | 通过 | 0 error，24 warning（均为历史 Plan 未用 workflow v3 兼容模式，不阻断） |
| 2026-06-25 | macOS | 未执行：真实 LLM 供应商调用（重验收） | 未执行 | 本 Hotfix 仅改 pgvector 向量索引（7.1）与 Sparse FTS 查询 SQL（7.2），不涉及 LLM/供应商边界；Query Stub 回归已覆盖必跑项；无残留风险 |
| 2026-06-25 | macOS, 独立重验收 | plan_6.hotfix_7 整体重验收结论 | 通过 | 7.1（dense>0：dense=1 + 运行 DB hnsw 索引 + 768 维 embedding）与 7.2（sparse>0：sparse=1 + psql OR=1 vs AND=0 + 空 query 安全）双路验收标准均以新鲜证据满足；query_stub/retrieval_evidence Docker 回归均 PASS；Java 无退化；静态校验通过。注：query_stub 可在 Dense embedding 就绪前仅凭 Sparse 通过（已知特性、非回归），已用复查日志 `sparse=1, dense=1` + DB embedding 事实 + psql 边界对比补齐双路独立证据。整份 Hotfix 维持 `completed` |

## 阻塞记录

无。本 Hotfix 为非优先项，登记后等待闲时执行；当前不阻塞任何 Plan（详见索引阻塞说明）。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-25 | 创建 Hotfix | plan_15 第四次独立验收 Docker HTTP 重跑发现 query_stub_success_test 失败，根因为 plan_6 检索链路 Sparse AND 语义过严 + Dense Java 层 0 结果（均非 plan_15 引入） | 初始范围；状态 ready，非优先闲时修复 |
| 2026-06-25 | 细化 Hotfix 执行计划 | 根据已批准设计文档 `docs/superpowers/specs/2026-06-25-plan-6-hotfix-7-retrieval-recall-design.md` 收敛 A1 方案 | 状态仍为 ready；任务数仍为 2；明确 Dense 固定诊断顺序、Sparse partial-match 约束与双路完成门槛 |
| 2026-06-25 | 重新界定 Dense 根因与修复范围 | 执行 session 复现发现：原「DB 返回行、Java 返回 0」假设不成立——完整 searchSimilar SQL（走 ivfflat 索引）在 DB 层即返回 0，根因为 ivfflat 空表索引失效；Java 链路全部正确 | 状态 ready→in_progress；6.hotfix_7.1 修复点由 Java 链路改为 `schema.sql` 向量索引 ivfflat→hnsw；文件边界新增 schema.sql 并标注 Dense 侧 Java 文件诊断确认正确不改动；Sparse 任务（6.hotfix_7.2）不变 |
