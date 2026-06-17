# plan_6 — Retrieval 查询链路

> 创建日期：2026-06-14  
> 状态：✅ 完成
> 来源：承接原 `plan_4` 中未执行的 Retrieval 查询能力，并补充 Rerank 接入。

## 范围

本计划覆盖 Retrieval 读链路：

1. Sparse/Dense 查询。
2. child chunk 维度 RRF 融合。
3. 相邻 child 扩展。
4. Retrieval 模块内部 Rerank。

Query 侧 UserQuery API、Prompt 拼接、LLM 接入、answer + sources 返回迁移到 `plan_7`。

**前置依赖**：

- `plan_4` 已完成 ingestion 侧 Sparse 索引写入。
- `plan_5` 已确定 module 拆分边界。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 6.1 | 通用查询结果类型 ChunkSearchResult | ✅ | `bd34143` | 2026-06-15 |
| 6.2 | ChunkEmbeddingRepository — 向量相似度查询 | ✅ | `bd34143` | 2026-06-15 |
| 6.3 | ChunkEmbeddingDao — searchSimilar 方法 | ✅ | `bd34143` | 2026-06-15 |
| 6.4 | DenseQueryService 实现 | ✅ | `bd34143` | 2026-06-15 |
| 6.5 | ChunkFtsRepository — FTS 全文检索查询 | ✅ | `bd34143` | 2026-06-15 |
| 6.6 | ChunkFtsDao — searchFts 方法 | ✅ | `bd34143` | 2026-06-15 |
| 6.7 | SparseQueryService 实现 | ✅ | `bd34143` | 2026-06-15 |
| 6.8 | RrfFusionService 实现 | ✅ | `e8ea240` | 2026-06-15 |
| 6.9 | Retrieval 冒烟验证端点 | ✅ | `e8ea240` | 2026-06-15 |
| 6.10 | Retrieval 内部 RerankClient / RerankService 接入 | ✅ | `e8ea240` | 2026-06-15 |

整体进度：10 / 10（100%）

## 6.1 通用查询结果类型 ChunkSearchResult

定义 Sparse / Dense / RRF 统一检索结果类型，字段包含：

- `chunkId`：child chunk ID；RRF 与 Rerank 均保持 child chunk 维度。
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
- 保留 child chunk 内容和 sparse / dense 原始得分。
- 同一 parent 下多个 child 命中时分别保留，不做 parent 去重。
- 返回按融合分数降序排列的 child chunk 结果。

**验收**：RRF 计算正确，child 维度保留正确，结果排序稳定。

## 6.9 Retrieval 冒烟验证端点

新增临时测试端点验证 query embedding、Sparse 查询、Dense 查询和 RRF 融合可跑通。

**验收**：返回 query、sparseCount、denseCount、fusedCount 和 fusedResults。

## 6.10 Retrieval 内部 RerankClient / RerankService 接入

在 `crag-retrieval` 内对接 sidecar `/rerank`，将 RRF 候选 chunk 重新排序。Rerank 属于 retrieval 内部实现细节，`crag-query` 不直接依赖 RerankClient 或 RerankService。

**验收**：sidecar 不可用时错误可观测；返回结果顺序与 rerank score 对齐。

**2026-06-17 修正**：

- `RetrievalService` 内部 RRF 以 child chunk 为融合粒度，不再在编排层提前漂移到 parent chunk。
- Rerank 候选集由 top RRF child chunk 及其同 parent 下前后相邻 child chunk 组成，最终结果仍按 rerank 分数截断为 `topN`。
- 相邻 child 仅参与 rerank 候选扩展，不伪造 sparse / dense 原始召回分数。
