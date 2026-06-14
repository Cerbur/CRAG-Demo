# plan_6 — Retrieval + Query 全链路

> 创建日期：2026-06-14  
> 状态：⏳ 待开始  
> 来源：承接原 `plan_4` 中未执行的 4.6-4.14，并补充 Rerank、UserQuery、LLM 全链路范围。

## 范围

本计划覆盖读链路和问答链路：

1. **Retrieval**：Sparse/Dense 查询、RRF 融合、回表 parent chunk。
2. **Rerank**：调用 sidecar `/rerank` 对候选上下文重新排序。
3. **Query**：实现 UserQuery API、Context 工程、LLM 调用、answer + sources 返回。

**前置依赖**：

- `plan_4` 已完成 ingestion 侧 Sparse 索引写入。
- `plan_5` 需要先确定 module 拆分边界，避免 Retrieval / Query 新代码落入马上要迁移的位置。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 6.1 | 通用查询结果类型 ChunkSearchResult | ⏳ | — | — |
| 6.2 | ChunkEmbeddingRepository — 向量相似度查询 | ⏳ | — | — |
| 6.3 | ChunkEmbeddingDao — searchSimilar 方法 | ⏳ | — | — |
| 6.4 | DenseQueryService 实现 | ⏳ | — | — |
| 6.5 | ChunkFtsRepository — FTS 全文检索查询 | ⏳ | — | — |
| 6.6 | ChunkFtsDao — searchFts 方法 | ⏳ | — | — |
| 6.7 | SparseQueryService 实现 | ⏳ | — | — |
| 6.8 | RrfFusionService 实现 | ⏳ | — | — |
| 6.9 | Retrieval 冒烟验证端点 | ⏳ | — | — |
| 6.10 | RerankClient / RerankService 接入 | ⏳ | — | — |
| 6.11 | Context 工程与 sources 结构 | ⏳ | — | — |
| 6.12 | LLM Client 接入与 QueryService 编排 | ⏳ | — | — |
| 6.13 | UserQueryController 实现 | ⏳ | — | — |
| 6.14 | 单元测试与端到端冒烟测试 | ⏳ | — | — |

整体进度：0 / 14（0%）

## 6.1 通用查询结果类型 ChunkSearchResult

定义 Sparse / Dense / RRF 统一检索结果类型，字段包含：

- `chunkId`：child chunk ID；RRF parent 回表后可为 parent chunk ID。
- `parentChunkId`：父 chunk ID。
- `score`：当前阶段相关性分数。
- `content`：chunk 文本内容。

**验收**：编译通过；如果新增共享包，需要同步更新 `constraints/package-structure.md`。

## 6.2 ChunkEmbeddingRepository — 向量相似度查询

使用 pgvector `<=>` 运算符查询 child chunk 向量相似度，JOIN `chunk` 表返回 child content 和 parent chunk ID。

**验收**：native SQL 参数顺序明确，返回列顺序与 Dao 映射一致。

## 6.3 ChunkEmbeddingDao — searchSimilar 方法

将 query embedding 转为 pgvector 字面量，委托 repository 查询，并映射为 `ChunkSearchResult`。

**验收**：空向量保护、列索引映射和分数类型转换正确。

## 6.4 DenseQueryService 实现

基于 `ChunkEmbeddingDao.searchSimilar(...)` 实现 Dense 查询召回。

**验收**：`topK` 生效，空输入返回空列表。

## 6.5 ChunkFtsRepository — FTS 全文检索查询

使用 PostgreSQL FTS 查询 `chunk_fts`，并 JOIN `chunk` 表返回 child content、parent chunk ID 和 `ts_rank` 分数。

**验收**：查询侧 CJK 预处理与 `plan_4` 写入侧保持一致。

## 6.6 ChunkFtsDao — searchFts 方法

封装 FTS 查询并映射为 `ChunkSearchResult`。

**验收**：空查询返回空列表，列映射顺序与 repository SELECT 对齐。

## 6.7 SparseQueryService 实现

基于 `ChunkFtsDao.searchFts(...)` 实现 Sparse 查询召回。

**验收**：`topK` 生效，空输入返回空列表。

## 6.8 RrfFusionService 实现

融合 Sparse 和 Dense 两路结果：

- 按每路结果排名计算 `1 / (60 + rank)`。
- 同一 child 在多路出现时累加分数。
- 回表 parent chunk。
- 同一 parent 下多个 child 命中时取最高 RRF 分数去重。
- 返回按融合分数降序排列的 parent chunk 结果。

**验收**：RRF 计算正确，parent 去重正确，结果排序稳定。

## 6.9 Retrieval 冒烟验证端点

新增临时测试端点验证 query embedding、Sparse 查询、Dense 查询和 RRF 融合可跑通。

**验收**：返回 query、sparseCount、denseCount、fusedCount 和 fusedResults。

## 6.10 RerankClient / RerankService 接入

对接 sidecar `/rerank`，将 RRF 候选上下文重新排序。

**验收**：sidecar 不可用时错误可观测；返回结果顺序与 rerank score 对齐。

## 6.11 Context 工程与 sources 结构

将 rerank 后的 parent chunks 组装为 LLM prompt context，并保留 sources。

**验收**：sources 可追溯到 chunk/document 元信息；context 长度有上限保护。

## 6.12 LLM Client 接入与 QueryService 编排

接入 DeepSeek / Spring AI，并在 QueryService 中串联 embedding、retrieval、rerank、LLM 生成。

**验收**：正常返回 answer；LLM 失败时返回可理解错误。

## 6.13 UserQueryController 实现

实现 `POST /api/v1/query`。

**验收**：请求校验、响应结构和错误响应与现有 API 风格一致。

## 6.14 单元测试与端到端冒烟测试

补充核心服务单测，并通过 Docker Compose 或本地依赖完成端到端冒烟验证。

**验收**：遵守 `constraints/test-workflow.md`；测试结果回填本计划。
