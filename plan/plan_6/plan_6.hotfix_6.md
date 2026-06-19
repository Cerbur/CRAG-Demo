---
workflow_version: 2
plan_id: plan_6.hotfix_6
type: hotfix
parent_plan: plan_6
status: ready
owner: parent-agent
created: 2026-06-19
updated: 2026-06-19
---

# plan_6.hotfix_6 — Retrieval Parent Evidence 输出修正

## 背景与目标

`plan_6` 已完成 child chunk 维度的召回、融合、相邻扩展和重排，并通过 `RetrievalService.retrieve()` 返回最终 child 结果。后续 Query 链路需要把完整 parent chunk 作为 LLM Context，同时保留实际命中的 child 证据；如果 Query 直接读取 Storage，会违反模块边界，如果直接把 child 内容交给 LLM，则会丢失 parent 语境。

本 Hotfix 在 Retrieval 内部完成 child 结果到 parent evidence 的聚合与回表，对 Query 新增稳定的 `retrieveEvidence()` 公共入口。现有 `retrieve()` 保持 child 结果语义，继续服务 Retrieval 内部测试和 Smoke 分阶段诊断。

## 范围

- 在 `retrieval.api` 定义不可变的 `ParentEvidenceResult`。
- 新增 `RetrievalService.retrieveEvidence(query, topN)`。
- 复用既有 child 检索与 Rerank 结果，按 parent 聚合、稳定排序并回表读取完整 parent 内容。
- 保留每个 parent 实际命中的 `matchedChildIds`。
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
- `topN` 表示最终最多返回的不同 parent 数量。
- 内部先获取 `topN × 3` 个 Rerank 后 child，再聚合 parent；不足 `topN` 时返回已有结果，不追加循环检索。
- 按 Rerank 后 child 顺序遍历；某 parent 首次出现时确定 parent 排名，后续命中只追加 child，不改变排名。
- `matchedChildIds` 按 Rerank 顺序稳定去重。
- parent 回表使用批量查询，禁止逐条 N+1 查询。
- parent 缺失或内容为空时跳过并记录 `parentChunkId`、`matchedChildIds`，不记录文本；继续处理后续 parent。
- 全部 parent 无效时返回空列表，不退回 child 内容。
- 公共结果不携带 Sparse、Dense、RRF 或 Rerank 分数。

## 未决问题

无。

## 风险与回滚

- child 候选倍率可能在 parent 高度聚集时返回少于 `topN` 个 parent：该行为已明确接受，以固定查询成本换取确定性；由测试覆盖。
- parent 批量回表可能暴露现有 DAO 查询缺口：只新增最窄的批量读取能力，不扩大 Entity 跨模块传播。
- parent 数据缺失会减少 evidence 数量：记录标识用于诊断，不用脏数据导致整次 Query 失败。
- 每项任务独立提交。失败时逆序撤销新入口、Storage 查询和诊断适配；既有 `retrieve()` 不变，因此回滚不会破坏现有调用方。
- 本 Hotfix 不迁移数据库结构，无不可逆数据变更。

## 测试与验证计划

- 纯单元测试：`./gradlew :crag-retrieval:test :crag-storage:test`，覆盖 parent 聚合顺序、去重、`topN × 3` 候选、缺失/空 parent 和空结果。
- 轻量组件测试：如新增 Spring Data 查询，使用对应 `*ComponentTest` 验证 Bean 与映射；不得把 H2 结果表述为 PostgreSQL 兼容证据。
- 架构测试：`./gradlew :crag-app:test --tests '*ArchitectureTest'`，确认 Query 仍只能依赖 Retrieval `api` 包，新结果不泄漏 Storage 类型。
- Docker HTTP 回归：通过 Compose 启动真实 PostgreSQL、pgvector、Sidecar 和 App，使用 `scripts/tests/http/` 的 Retrieval 诊断脚本验证 parent evidence 顺序、内容和 matched child；不直接查询数据库替代业务入口。
- 全量检查：`./gradlew test`、`./gradlew check`、`python3 scripts/validate_plans.py --strict --verify-git`、`git diff --check`。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 6.hotfix_6.1 | 建立 Parent Evidence 公共契约与聚合规则 | ⏳ 待开始 | — | — |
| 6.hotfix_6.2 | 实现 parent 批量回表与稳定 Evidence 输出 | ⏳ 待开始 | — | — |
| 6.hotfix_6.3 | 补齐架构护栏、Docker 回归与约束同步 | ⏳ 待开始 | — | — |

整体进度：0 / 3（0%）

## 6.hotfix_6.1 建立 Parent Evidence 公共契约与聚合规则

**目标**：定义 Query 可依赖的 parent evidence 公开结果和 `retrieveEvidence()` 行为边界。
**前置任务**：无
**范围**：新增不可变 `ParentEvidenceResult`；新增 `retrieveEvidence(query, topN)` 入口；提取可纯单元测试的 child-to-parent 聚合逻辑；固定首次命中排名、matched child 稳定去重和 `topN` 语义。
**非目标**：不完成真实 parent 回表，不修改既有 `retrieve()`，不实现 Query。
**验收标准**：公共类型不含 Entity、DAO、Spring Web 或检索分数；相同 parent 的多个 child 聚合为一项；parent 顺序和 matched child 顺序确定；非法输入返回空集合。
**验证方式**：运行 `./gradlew :crag-retrieval:test`，覆盖单 parent、多 parent、重复 child、同 parent 多 child、空输入和 topN 边界。
**涉及文件**：`crag-retrieval/src/main/java/ai/cerbur/crag/retrieval/api/**`、`crag-retrieval/src/test/**`

## 6.hotfix_6.2 实现 parent 批量回表与稳定 Evidence 输出

**目标**：从 Rerank 后 child 结果生成包含完整 parent 内容的可用 evidence。
**前置任务**：6.hotfix_6.1
**范围**：以 `topN × 3` 获取 child 候选；批量读取 parent chunk；按聚合顺序组装 `ParentEvidenceResult`；跳过缺失或空内容 parent；记录 parent 与 matched child 标识。
**非目标**：不循环扩大候选集，不逐条查询 parent，不以 child 内容兜底，不改变检索算法。
**验收标准**：最多返回 `topN` 个不同 parent；结果包含完整 parent 内容；批量回表无 N+1；缺失/空 parent 不导致整体失败；日志不包含文档内容。
**验证方式**：运行 `./gradlew :crag-storage:test :crag-retrieval:test`；检查 DAO 调用次数和参数；覆盖候选高度聚集、parent 缺失、空内容和不足 topN。
**涉及文件**：`crag-retrieval/src/main/**`、`crag-retrieval/src/test/**`、`crag-storage/src/main/**`、`crag-storage/src/test/**`

## 6.hotfix_6.3 补齐架构护栏、Docker 回归与约束同步

**目标**：证明新公共边界在真实 Retrieval 链路可用，并让约束与实现一致。
**前置任务**：6.hotfix_6.2
**范围**：增加或调整 Architecture 规则；扩展 Smoke Retrieval 诊断但不新增正式业务能力；沉淀 parent evidence Docker HTTP 回归；同步 Retrieval 与包结构约束、Plan 和索引。
**非目标**：不实现 UserQuery API，不把 Smoke 端点作为 Query 正式入口，不修改 Docker 部署契约。
**验收标准**：普通模块只能从 `retrieval.api` 访问 Parent Evidence；真实 Docker 链路返回 parent 内容和 matched child；约束当前实现索引准确；全量检查通过。
**验证方式**：运行 `./gradlew :crag-app:test --tests '*ArchitectureTest'`、`./gradlew test`、`./gradlew check`；通过 Compose 执行 Retrieval parent evidence HTTP 回归；运行 Plan 严格校验和 `git diff --check`。
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
