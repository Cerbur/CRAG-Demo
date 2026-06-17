# plan_4 — Sparse 索引写入链路

> 创建日期：2026-06-14  
> 状态：✅ 完成  
> 范围调整：2026-06-14 将原 4.6-4.14 的 Retrieval 查询、RRF、查询冒烟验证迁移到 `plan_6`；本计划只保留已完成的 ingestion 侧 Sparse 索引写入链路。

## 范围

本计划覆盖 ingestion 侧 Sparse 索引写入链路：

1. 为 child chunk 增加 sparse 状态 CAS 扫描与状态推进能力。
2. 为 `chunk_fts` 增加幂等写入能力。
3. 新增 `SparseEmbeddingCron`，定时扫描 child chunk 并写入 PostgreSQL FTS 索引。

**不包含**：

- Sparse/Dense 查询召回、child chunk 维度 RRF、Rerank 候选扩展：已迁移到 `plan_6`。
- Rerank、UserQuery、LLM 调用：已迁移到 `plan_6`。
- Java module 拆分：作为 `plan_5` 独立讨论和规划。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 4.1 | ChunkRepository — Sparse CAS 方法补充 | ✅ | 80150f6 | 2026-06-14 |
| 4.2 | ChunkFtsRepository — INSERT / existsByChunkId | ✅ | 80150f6 | 2026-06-14 |
| 4.3 | ChunkFtsDao — 写入方法补充 | ✅ | 80150f6 | 2026-06-14 |
| 4.4 | ChunkDao — Sparse 读写方法透传 | ✅ | 80150f6 | 2026-06-14 |
| 4.5 | SparseEmbeddingCron — 定时任务 | ✅ | 80150f6 | 2026-06-14 |

整体进度：5 / 5（100%）

## 4.1 ChunkRepository — Sparse CAS 方法补充

**文件**：`dao/repository/ChunkRepository.java`

为 Sparse 链路新增与 Dense 侧对称的状态扫描和 CAS 更新能力：

- `findSparseCandidates(...)`
- `tryMarkSparseProcessing(...)`
- `tryMarkSparseProcessingTimeout(...)`
- `updateSparseStatus(...)`

**验收**：4 个方法编译通过，JPQL 语法正确，`sparseStatus` 枚举引用路径正确。

## 4.2 ChunkFtsRepository — INSERT / existsByChunkId

**文件**：`dao/repository/ChunkFtsRepository.java`

新增：

- `existsByChunkId(String chunkId)`
- `insert(String chunkId, String rawContent)`

写入侧使用 PostgreSQL `to_tsvector('simple', ...)` 构建 `chunk_fts`，并在 SQL 侧对 CJK 文本做空格预处理。

**验收**：编译通过，`existsByChunkId` 方法签名正确，`insert` 的 native SQL 参数顺序与调用方一致。

## 4.3 ChunkFtsDao — 写入方法补充

**文件**：`dao/ChunkFtsDao.java`

新增 `existsByChunkId(...)` 和 `insert(...)`，Dao 层负责幂等检查，Repository 层负责 native SQL 写入。

**验收**：`existsByChunkId` 和 `insert` 正确委托 Repository，幂等逻辑与 `ChunkEmbeddingDao` 对齐。

## 4.4 ChunkDao — Sparse 读写方法透传

**文件**：`dao/ChunkDao.java`

新增 Sparse 状态推进方法透传，供 Cron 层编排使用：

- `findSparseCandidates(...)`
- `tryMarkSparseProcessing(...)`
- `tryMarkSparseProcessingTimeout(...)`
- `updateSparseStatus(...)`

**验收**：4 个方法签名与 Repository 对齐，编译通过。

## 4.5 SparseEmbeddingCron — 定时任务

**文件**：`cron/SparseEmbeddingCron.java`

新增 Sparse 索引写入定时任务，镜像 `DenseEmbeddingCron` 的批量扫描、CAS 抢占、超时回收和终态更新结构。差异是 Sparse 不调用 sidecar，而是直接通过 `ChunkFtsDao` 写入 `chunk_fts`。

**验收**：

- 编译通过，`@Scheduled` 注解正确。
- 仅处理 child chunk。
- 幂等写入 `chunk_fts`，成功后推进 `sparseStatus=SUCCESS`，失败后允许下轮重试。
