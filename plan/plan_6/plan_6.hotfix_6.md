---
workflow_version: 3
plan_id: plan_6.hotfix_6
type: hotfix
parent_plan: plan_6
status: verifying
created: 2026-06-19
updated: 2026-06-20
---

# plan_6.hotfix_6 — Retrieval Parent Evidence 输出修正

## 背景与目标

`plan_6` 已完成 child chunk 维度的召回、融合、相邻扩展和重排，并通过 `RetrievalService.retrieve()` 返回最终 child 结果。后续 Query 链路需要把完整 parent chunk 作为 LLM Context，同时保留实际命中的 child 证据；如果 Query 直接读取 Storage，会违反模块边界，如果直接把 child 内容交给 LLM，则会丢失 parent 语境。

本 Hotfix 在 Retrieval 内部完成 child 结果到 parent evidence 的聚合与回表，对 Query 新增稳定的 `retrieveEvidence()` 公共入口。现有 `retrieve()` 保持 child 结果语义，继续服务 Retrieval 内部测试和 Smoke 分阶段诊断。

## 范围

- 在 `retrieval.api` 定义不可变的 `ParentEvidenceResult`。
- 新增 `RetrievalService.retrieveEvidence(query, topN)`。
- 复用既有 child 检索与 Rerank 结果，显式区分真实 RRF 命中与相邻扩展候选，按 parent 聚合、稳定排序并回表读取完整 parent 内容。
- 保留每个 parent 在最终 Evidence 候选窗口内的真实命中 `matchedChildIds`。
- 覆盖 parent 去重、候选倍率、缺失 parent、空内容和稳定顺序。
- 同步 Retrieval 约束、包结构索引、架构测试和自动化 HTTP 诊断回归。

## 非目标

- 不实现 Query Context、Prompt、LLM、引用编号或正式 UserQuery API。
- 不改变 Sparse、Dense、RRF、Rerank 算法及分数计算。
- 不改变既有 `retrieve()` 的返回类型和 child 语义。
- 不把检索分数、Entity 或 DAO 类型暴露到新的公共契约。
- 不循环扩大候选集以强行凑满 `topN`。

## 前置依赖

- **执行前置 Plan**：`plan_6`
- `plan_6` 已完成 Retrieval 查询链路。
- `plan_9` 已完成 Retrieval 公开 API 包和模块边界收紧。
- `plan_11` 已建立测试分层与 Docker HTTP 回归规则。

## 文件边界

- `crag-retrieval/src/main/java/ai/cerbur/crag/retrieval/api/**`
- `crag-retrieval/src/test/**`
- `crag-storage/src/main/java/ai/cerbur/crag/storage/ChunkDao.java`
- `crag-storage/src/main/java/ai/cerbur/crag/storage/repository/**`（仅在现有查询不足时增加 parent 批量读取）
- `crag-storage/src/test/**`
- `crag-smoke/src/**`（仅诊断响应适配）
- `crag-app/src/test/java/ai/cerbur/crag/app/arch/**`
- `scripts/tests/http/**`
- `constraints/retrieval-style.md`
- `constraints/package-structure.md`
- `plan/plan_6/plan_6.hotfix_6.md`
- `plan/index/README.md`

## 关联范围与规模说明

- 关联已完成主计划：`plan_6`。
- 业务模块限于 Retrieval 与其既有 Storage 查询边界；`crag-smoke` 只承担诊断适配，不新增业务逻辑。
- 共 3 个任务，未超过 Hotfix 的 5 任务和 3 业务模块门槛。

## 关键决策

- 新增 `retrieveEvidence()`，不改变 `retrieve()` 的 child 返回语义。
- `ParentEvidenceResult` 位于 `ai.cerbur.crag.retrieval.api.result`，只包含 `parentChunkId`、完整 `content` 和 `matchedChildIds`。
- `ParentEvidenceResult` 使用 Java `record`；构造时复制 `matchedChildIds`，并拒绝 null、blank、空证据列表等非法状态。
- `topN` 表示最终最多返回的不同 parent 数量。
- 内部检索显式接收召回、RRF 和最终 child 三个限额；`retrieve()` 保持“召回 `3N`、RRF `N`、最终 `N`”，`retrieveEvidence()` 使用“召回 `3N`、RRF `3N`、最终 `3N`”，避免 Evidence 路径意外扩大到 `9N` 召回。
- `3N` 使用饱和乘法防止整数溢出；不额外新增任意业务上限。
- Evidence 路径允许 RRF `3N` 加相邻扩展后最多约 `9N` 个 child 进入 Rerank，再截取 Rerank 后前 `3N` 个 child 作为 Evidence 候选窗口。
- 内部检索结果同时携带 Rerank 后 child 列表与真实 RRF 命中 child ID 的有序集合；禁止通过分数是否为 null 或零反推来源。
- parent 排名按 Evidence 候选窗口内该 parent 的最高 Rerank 名次确定，相邻扩展 child 可以影响 parent 排名，但不能进入 `matchedChildIds`。
- `matchedChildIds` 只包含 Evidence 候选窗口内的真实 RRF 命中 child，按最终 Rerank 顺序稳定去重；主动 `3N` 截断排除的 child 不追加。
- Rerank 部分返回时继承现有 `RerankService` 语义：未返回候选以 0 分保留，并按原候选相对顺序稳定排列在已评分候选之后；Evidence 直接消费该完整结果，不另行追加或重排。
- 只有相邻扩展、没有任何真实命中 child 的 parent 不返回；若真实命中 child 被主动截断而只剩相邻 child，该 parent 同样丢弃。
- Rerank 返回空并按既有逻辑回退时，Evidence 沿用回退后的顺序，不另行定义排序。
- parent 回表使用批量查询，禁止逐条 N+1 查询。
- Storage 新增最窄的 `ParentChunkContent` 投影和 `ChunkDao.findParentContentsByIds(...)`，只返回 `chunkId`、`content`，查询限定 parent 行，不新增 Entity 跨模块传播。
- Retrieval 不依赖批量查询返回顺序；按 `chunkId` 建立映射，再按已确定的 parent 排名组装。重复 parent 投影保留第一项并记录警告。
- parent 缺失、内容为 null 或 blank 时跳过；使用候选列表中的后续有效 parent 补位，最终再截取前 `topN` 个有效 parent，不追加检索。
- 无效 parent 每项最多记录一次安全 `WARN`，包含 `parentChunkId`、命中 child 数量及最多前 10 个 child ID；调用结束记录无效 parent 总数，不记录文本。
- 真实命中 child 的 `parentChunkId` 为 null 或 blank 时跳过该 child 并记录安全警告，不把 child 自身当作 parent。
- 全部 parent 无效时返回空列表，不退回 child 内容。
- 公共结果不携带 Sparse、Dense、RRF 或 Rerank 分数。
- 新增独立 Smoke 诊断端点 `/api/v1/test/retrieval/evidence`，直接返回 `Response<List<ParentEvidenceResult>>`；不改变既有 `/retrieval` child 诊断契约。
- `retrieveEvidence()` 对 blank query 或非正 `topN` 返回空列表；Smoke 端点不额外发明 HTTP 参数校验语义。
- Docker HTTP 回归使用写入内容中的唯一 `runId` 隔离测试数据；当前 Demo 不新增清理接口，允许保留可识别残留，禁止清表、删除 volume 或清理其他运行数据。

## 未决问题

无。

## 风险与回滚

- child 候选倍率可能在 parent 高度聚集时返回少于 `topN` 个 parent：该行为已明确接受，以固定查询成本换取确定性；由测试覆盖。
- parent 批量回表可能暴露现有 DAO 查询缺口：只新增最窄的批量读取能力，不扩大 Entity 跨模块传播。
- parent 数据缺失会减少 evidence 数量：记录标识用于诊断，不用脏数据导致整次 Query 失败。
- 每项任务独立提交。失败时逆序撤销新入口、Storage 查询和诊断适配；既有 `retrieve()` 不变，因此回滚不会破坏现有调用方。
- 本 Hotfix 不迁移数据库结构，无不可逆数据变更。

## 测试与验证计划

- 纯单元测试：`./gradlew :crag-retrieval:test :crag-storage:test`，覆盖三个内部限额、饱和乘法、真实命中与相邻扩展区分、parent 排名、`matchedChildIds` 顺序与截断、缺失/空 parent、补位和空结果。
- 轻量组件测试：新增 `ChunkRepositoryComponentTest` 验证 parent 投影字段映射和 parent 行过滤；`ChunkDaoTest` 验证委托与参数。H2 只证明映射，不作为 PostgreSQL 兼容证据。
- 架构测试：`./gradlew :crag-app:test --tests '*ArchitectureTest'`，确认 Query 仍只能依赖 Retrieval `api` 包，新结果不泄漏 Storage 类型。
- Docker HTTP 回归：通过 Compose 启动真实 PostgreSQL、pgvector、Sidecar 和 App，使用 `scripts/tests/http/` 的 Retrieval 诊断脚本写入带唯一 `runId` 的单 parent 文档，轮询索引完成后验证完整 parent 内容、真实 matched child，并以相同请求重复调用验证顺序稳定；不直接查询数据库替代业务入口，不新增清理接口。
- 全量检查：`./gradlew test`、`./gradlew check`、`python3 scripts/validate_plans.py --strict --verify-git`、`git diff --check`。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 6.hotfix_6.1 | 建立 Parent Evidence 公共契约与聚合规则 | ✅ 完成 | 3b4cc6f | 2026-06-20 |
| 6.hotfix_6.2 | 实现 parent 批量回表与稳定 Evidence 输出 | ✅ 完成 | 1eb8cbb | 2026-06-20 |
| 6.hotfix_6.3 | 补齐架构护栏、Docker 回归与约束同步 | ⏳ 待验收 | 5c2d27d | — |

整体进度：2 / 3（67%）

## 6.hotfix_6.1 建立 Parent Evidence 公共契约与聚合规则

**目标**：定义 Query 可依赖的 parent evidence 公开结果和 `retrieveEvidence()` 行为边界。
**前置任务**：无
**范围**：新增强不变量的 `ParentEvidenceResult` record；提取带召回/RRF/最终 child 三限额的内部检索方法；内部结果显式携带 Rerank 列表与真实 RRF 命中 ID；实现可纯单元测试的 Evidence 候选聚合逻辑；固定 parent 排名、matched child 稳定去重、Rerank 部分返回与主动截断语义。
**非目标**：不新增尚不能真实回表的半成品 `retrieveEvidence()` 公共入口，不完成 parent 批量回表，不修改既有 `retrieve()`，不实现 Query。
**验收标准**：公共结果类型不含 Entity、DAO、Spring Web 或检索分数；record 防御性复制集合并拒绝非法状态；既有 `retrieve()` 成本与语义不变；Evidence 召回不扩大到 `9N`；相邻 child 可影响 parent 排名但不进入 matched child；只有相邻 child 的 parent 不返回；Rerank 未评分候选继承现有稳定尾排语义。
**验证方式**：运行 `./gradlew :crag-retrieval:test`，覆盖单/多 parent、重复 child、同 parent 多 child、真实命中与相邻扩展、Rerank 空回退、部分返回、主动 `3N` 截断和饱和乘法。
**涉及文件**：`crag-retrieval/src/main/java/ai/cerbur/crag/retrieval/api/**`、`crag-retrieval/src/test/**`

## 6.hotfix_6.2 实现 parent 批量回表与稳定 Evidence 输出

**目标**：从 Rerank 后 child 结果生成包含完整 parent 内容的可用 evidence。
**前置任务**：6.hotfix_6.1
**范围**：新增 `ParentChunkContent` 投影和限定 parent 行的批量查询；落地 `retrieveEvidence(query, topN)` 公共入口；按 parent 排名建立映射并组装 `ParentEvidenceResult`；跳过并以后续候选补位缺失、null 或 blank 内容 parent；处理重复投影和无 parent ID child；记录受限安全诊断信息。
**非目标**：不循环扩大候选集，不逐条查询 parent，不以 child 内容兜底，不改变检索算法。
**验收标准**：blank query 或非正 `topN` 返回空集合；最多返回 `topN` 个不同有效 parent；结果保留完整原文且不 trim；批量回表无 N+1、不泄漏 Entity；不依赖数据库返回顺序；缺失/空 parent 不导致整体失败并由后续有效候选补位；日志不包含文档内容且 child ID 最多记录 10 个。
**验证方式**：运行 `./gradlew :crag-storage:test :crag-retrieval:test`；通过 `ChunkDaoTest` 检查委托、调用次数和参数，通过 `ChunkRepositoryComponentTest` 检查投影映射与 parent 行过滤；覆盖空输入、topN 边界、乱序/重复投影、候选高度聚集、parent 缺失、blank 内容、补位和不足 topN。
**涉及文件**：`crag-retrieval/src/main/**`、`crag-retrieval/src/test/**`、`crag-storage/src/main/**`、`crag-storage/src/test/**`

## 6.hotfix_6.3 补齐架构护栏、Docker 回归与约束同步

**目标**：证明新公共边界在真实 Retrieval 链路可用，并让约束与实现一致。
**前置任务**：6.hotfix_6.2
**范围**：增加或调整 Architecture 规则；新增 `/api/v1/test/retrieval/evidence` Smoke 诊断端点并保持既有 `/retrieval` 不变；沉淀 parent evidence Docker HTTP 回归；同步 Retrieval 与包结构约束、Plan 和索引。
**非目标**：不实现 UserQuery API，不把 Smoke 端点作为 Query 正式入口，不修改 Docker 部署契约。
**验收标准**：普通模块只能从 `retrieval.api` 访问 Parent Evidence；Smoke 直接序列化公共契约且不改变既有 child 诊断；真实 Docker/PostgreSQL 链路返回完整 parent 内容和真实命中 child，相同请求重复调用顺序一致；测试数据包含唯一 `runId` 且不破坏性清理；约束当前实现索引准确；全量检查通过。
**验证方式**：运行 `./gradlew :crag-app:test --tests '*ArchitectureTest'`、`./gradlew test`、`./gradlew check`；通过 Compose 执行 Retrieval parent evidence HTTP 回归并记录可识别残留 `runId`；运行 Plan 严格校验和 `git diff --check`。
**涉及文件**：`crag-smoke/src/**`、`crag-app/src/test/**`、`scripts/tests/http/**`、`constraints/retrieval-style.md`、`constraints/package-structure.md`、`plan/plan_6/plan_6.hotfix_6.md`、`plan/index/README.md`

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |

## 阻塞记录

无。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-19 | 创建 Hotfix 并设为待开始 | Plan 7 grilling 发现 Query 必须消费 parent 维度 Context，而现有 Retrieval 只公开 child 结果 | 新增 3 项修正任务；执行队列置于 plan_13 与 plan_7 之前 |
| 2026-06-19 | 完成度 grilling 后退回草稿 | 原计划未区分真实命中与相邻扩展，内部限额、Storage 投影、无效数据补位和诊断边界仍不充分 | 保持实现进度 0/3；重划三项任务并补齐 28 项执行决策，重新提交后方可恢复 ready |
| 2026-06-19 | 二次 grilling 完成并恢复待开始 | 校准 Rerank 部分返回语义、公共入口任务边界和 Demo 测试数据策略；确认下游只需要三字段 Evidence 契约 | 计划达到 ready 完整度；不新增清理接口，编码前需先提交 Plan 与索引 |
