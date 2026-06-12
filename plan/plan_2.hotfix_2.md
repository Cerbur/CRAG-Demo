# Plan_2.hotfix_2 — Core 能力包拆分与 ChunkSplit 命名收敛

> 创建时间：2026-06-12
> 归属：plan_2 任务 2.1（ChunkService 实现）及后续 2.4-2.5 Dense 链路前置整理

---

## 背景

准备继续推进 plan_2 后续 AdminRag / Dense 异步链路前，发现 `core` 层当前包名更偏技术步骤堆叠，后续如果按 DDD 或逻辑微服务拆分，会出现边界不够清晰的问题：

- `ChunkService` 当前本质是“文本切分 / ChunkSplit”，不是完整的 chunk 业务编排服务。
- `core.embedding` 和 `core.denseQuery` 都服务于 Dense 检索通道：无论是写入阶段的 embedding，还是查询阶段的 dense query，本质都是 Dense 层逻辑，后续如果拆为独立 Dense 逻辑服务，应先在包结构上收敛到同一能力边界。
- `core.sparseQuery` 服务于 Sparse 检索通道，后续 BM25 / FTS 写入与查询也应收敛到独立 Sparse 能力包，而不是只把 Query 当成一个孤立步骤。
- plan_2 后续任务会继续接入 `AdminRagService`、`EmbeddingClient`、Cron Dense 处理，若现在不整理命名，后面改动面会更大。

---

## 目标

在不改变业务行为的前提下，先完成 `core` 包结构的能力域整理：

1. 将 Chunk 分块能力明确命名为 `ChunkSplit`，避免 `ChunkService` 泛化语义。
2. 将 Dense 作为独立能力包：embedding 入库编排、dense query 查询都归入 `core.dense`。
3. 将 Sparse 作为独立能力包：后续 FTS/BM25 入库编排、sparse query 查询都归入 `core.sparse`。
4. 保留 `integration.embedding` 作为外部 Sidecar 接入层，不把外部客户端混入 `core.dense`。
5. 更新测试包名、引用、计划文档和 README 包结构说明，保持代码与项目索引一致。

---

## 建议包结构

调整前：

```text
com.crag.demo.core
├── chunk
│   ├── ChunkService
│   ├── ChunkResult
│   ├── ChunkGroup
│   └── ChunkData
├── embedding
│   └── EmbeddingService
├── denseQuery
│   └── DenseQueryService
├── sparseQuery
│   └── SparseQueryService
├── rrf
│   └── RrfFusionService
└── rerank
    └── RerankService
```

调整后：

```text
com.crag.demo.core
├── chunk
│   ├── ChunkSplitService
│   ├── ChunkSplitResult
│   ├── ChunkSplitGroup
│   └── ChunkSplitData
├── dense
│   ├── DenseEmbeddingService
│   └── DenseQueryService
├── sparse
│   └── SparseQueryService
├── rrf
│   └── RrfFusionService
└── rerank
    └── RerankService
```

说明：

- `core.chunk` 只表达“文档分块领域能力”，当前不再使用泛化的 `ChunkService` 命名。
- `core.dense` 是 Dense 通道的独立能力包，包含写入侧 embedding 编排与查询侧 dense query；`integration.embedding.EmbeddingClient` 仍表示外部模型服务调用。
- `core.sparse` 是 Sparse 通道的独立能力包，后续包含写入侧 FTS/BM25 索引编排与查询侧 sparse query。
- `rrf`、`rerank` 暂不拆，后续如果形成更强领域边界，再单独规划。

---

## 修正范围

本 hotfix 只做结构与命名整理，不改业务算法：

1. Rename Chunk 分块相关类：
   - `ChunkService` → `ChunkSplitService`
   - `ChunkResult` → `ChunkSplitResult`
   - `ChunkGroup` → `ChunkSplitGroup`
   - `ChunkData` → `ChunkSplitData`
2. 更新 Chunk 单测：
   - `ChunkServiceTest` → `ChunkSplitServiceTest`
   - 测试描述同步从 “ChunkService” 调整为 “ChunkSplitService”
3. 移动并收敛 Dense 核心包：
   - `core.embedding.EmbeddingService` → `core.dense.DenseEmbeddingService`
   - `core.denseQuery.DenseQueryService` → `core.dense.DenseQueryService`
4. 移动并收敛 Sparse 核心包：
   - `core.sparseQuery.SparseQueryService` → `core.sparse.SparseQueryService`
5. 更新所有 import、包声明、JavaDoc、README、`AGENTS.md` / `CLAUDE.md` 中的包结构索引。
6. 运行测试确认 rename 不改变行为。

---

## 非目标

- 不调整 ChunkSplit 的 token 分块算法。
- 不改变 `ChunkSplitResult` 的 parent/child group 数据结构语义。
- 不实现 AdminRagService、Controller 接线、Cron Dense 入库或 DenseQuery 查询逻辑。
- 不移动 `integration.embedding.EmbeddingClient`；它仍归外部服务接入层。
- 不引入 DDD 框架、领域事件或模块化构建，当前只先整理包边界。

---

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| H2.1 | 复核 core 当前包结构与引用关系 | ✅ 完成 | — | 2026-06-12 |
| H2.2 | Rename ChunkService 及相关 record 为 ChunkSplit 命名 | ✅ 完成 | — | 2026-06-12 |
| H2.3 | 将 core.embedding / core.denseQuery 收敛为 core.dense | ✅ 完成 | — | 2026-06-12 |
| H2.4 | 将 core.sparseQuery 收敛为 core.sparse | ✅ 完成 | — | 2026-06-12 |
| H2.5 | 更新测试、README、AGENTS.md、CLAUDE.md 与计划索引 | ✅ 完成 | — | 2026-06-12 |
| H2.6 | 运行 ChunkSplit 单测与全量测试验证 | ✅ 完成 | — | 2026-06-12 |

> 状态图例：⏳ 待开始 / 🔄 进行中 / ✅ 完成 / ❌ 阻塞

---

## 验收标准

- 项目中不再出现 `core.denseQuery`、`core.sparseQuery` 包声明。
- `core.embedding.EmbeddingService` 不再作为核心包存在；embedding 入库编排与 dense query 查询统一位于 `core.dense`。
- Sparse 相关核心逻辑统一位于 `core.sparse`，后续 FTS/BM25 写入和查询都不再散落到 query-only 包。
- `ChunkService` 相关命名收敛为 `ChunkSplit*`，语义更贴近“切分能力”。
- `integration.embedding.EmbeddingClient` 保持不变，外部模型接入层边界清晰。
- `./gradlew test --tests com.crag.demo.core.chunk.ChunkSplitServiceTest` 通过。
- `./gradlew test` 通过。
- README / AGENTS / CLAUDE 包结构索引与实际代码一致。

---

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-06-12 | 创建 hotfix 计划，记录 core 包边界整理、ChunkSplit rename 与 Dense 包收敛范围；本次仅写计划，不执行代码修改 |
| 2026-06-12 | 修正包边界表达：Dense / Sparse 应理解为独立检索通道能力包，embedding 与 dense query 都属于 Dense 层逻辑 |
| 2026-06-12 | 执行完成：Chunk → ChunkSplit 重命名（4 个类 + 测试），embedding/denseQuery → core.dense 收敛，sparseQuery → core.sparse 收敛，README/CLAUDE 包结构索引同步更新。`./gradlew test --tests ChunkSplitServiceTest` 与 `./gradlew test` 均通过 |
