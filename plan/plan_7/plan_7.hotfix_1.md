---
workflow_version: 3
plan_id: plan_7.hotfix_1
type: hotfix
parent_plan: plan_7
status: ready
created: 2026-06-25
updated: 2026-06-25
---

# plan_7.hotfix_1 — 修正 query_stub_failure_test.sh 未 seed evidence 导致 LLM 失败路径不可达

## 背景与目标

`scripts/tests/http/query_stub_failure_test.sh`（plan_7 任务 7.7 引入的 Stub failure Docker 回归）Phase 1 直接以 `{"question":"测试问题"}` 发起 failure 模式查询，未先写入任何证据文档。`UserQueryService.answer()` 在检索证据为空时短路返回 `"知识库证据不足"`（HTTP 200、`code=0`），**不调用 LLM**，因此 Stub failure 模式从不触发，脚本永远拿不到期望的 502，Docker 回归稳定失败。

该缺陷在 plan_16 独立验收时定位并复核：plan_16 对 `UserQueryService`、`StubLlmAdapter` 0 行改动（纯文件迁移），失败路径（LLM 不可用 → 502/50201）本身经独立复跑与 `UserQueryControllerComponentTest.llmUnavailableReturns502` 验证正确；问题纯粹是脚本未准备 evidence，属 plan_7 引入的测试设计遗漏。

**目标**：使 `query_stub_failure_test.sh` 在 failure 模式查询前先 seed 可命中的证据，从而真正触达 LLM 失败路径并稳定断言 502/50201。

## 范围

- 修改 `scripts/tests/http/query_stub_failure_test.sh`：在 failure 模式重建前增加 evidence 准备步骤（经 `/api/v1/smoke/admin/rag` 写入含唯一标识的文档，并轮询确认其进入 Query sources），并将 failure 查询改用该唯一标识问题；在注释中说明 `UserQueryService` 空上下文短路的必要性。

## 非目标

- 不修改任何生产代码（`UserQueryService`、`StubLlmAdapter`、`GlobalExceptionHandler` 行为均已正确）。
- 不修改 failure 路径的 HTTP 契约、错误码或异常映射。
- 不改动脚本的服务端口、断言结构（502/50201/`success=false`）、Phase 2 恢复逻辑或 `RUN_ID` 数据隔离约定。
- 不触碰其他 HTTP 回归脚本。

## 前置依赖

- **执行前置 Plan**：无
- 所属主 Plan `plan_7` 已完成；脚本当前形态为 plan_16 URL 迁移后的 `/api/v1/smoke/**` 版本（plan_16 已完成）。
- 执行前需有可用的 Docker Compose 栈（`rag-service-smoke` 默认 success 模式可达）。

## 文件边界

- `scripts/tests/http/query_stub_failure_test.sh`

## 关联范围与规模说明

- 关联主 Plan：`plan_7`（脚本与 Stub failure 路径测试的引入者，任务 7.7）；脚本 URL 经 `plan_14`（14.8 五服务拓扑迁移）与 `plan_16`（16.4/16.5 smoke 命名空间迁移）演进，但「未 seed evidence」缺陷源自 plan_7 原始测试设计。
- 规模：1 个有效任务、1 个测试脚本，远小于主 Plan 升级门槛。

## 关键决策

- 修复策略：在 failure 重建**之前**（服务仍处 success 模式）写入证据并轮询确认索引完成，复用 `query_stub_success_test.sh` 已验证的 write+poll 模式；之后再重建为 failure 模式查询，确保 evidence 已落库、failure 路径可达。
- 不改 failure 路径断言（仍 502/50201/`success=false`），仅补 evidence 准备，保持脚本回归语义稳定。
- 不引入新的清理逻辑：沿用脚本既有 `RUN_ID` 数据隔离约定（保留测试数据，当前无安全精确删除入口）。

## 未决问题

无。

## 风险与回滚

- 风险：write+poll 引入新的索引时序依赖。预防：复用 `query_stub_success_test.sh` 的成熟轮询参数（最多 30 次 ×3s），且在 success 模式下轮询（响应结构无歧义）。
- 风险：failure 重建与 evidence 写入顺序错误会导致 failure 路径仍不可达。预防：固定「先 seed（success）→ 再重建（failure）→ 再查询」顺序，并在注释中标注。
- 回滚：`git revert` 本 hotfix 提交即可恢复脚本原状（原脚本仅是 502 断言失败，不影响其他链路或部署）。

## 测试与验证计划

- Docker HTTP 回归（必需）：通过 Docker Compose 执行 `scripts/tests/http/query_stub_failure_test.sh`，确认 Phase 1 返回 502/50201/`success=false`、Phase 2 恢复 success 后 `code=0`。
- 回归不破坏：执行 `query_stub_success_test.sh` 确认 success 路径仍通过。
- 无 Gradle/单元测试变更（纯 shell 脚本）。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 7.hotfix_1.1 | 为 query_stub_failure_test.sh 补 evidence 准备使失败路径可达 | ⏳ 待开始 | — | — |

整体进度：0 / 1（0%）

## 7.hotfix_1.1 为 query_stub_failure_test.sh 补 evidence 准备使失败路径可达

**目标**：`query_stub_failure_test.sh` 在 failure 模式查询前先 seed 可命中 evidence，使 LLM 失败路径真正被触发并稳定断言 502/50201。  
**前置任务**：无  
**范围**：在脚本 failure 重建步骤前新增 evidence 准备（写入含唯一 `VERIFICATION_CODE` 的文档 + 轮询 `/api/v1/smoke/query` 直到写入 chunk 出现在 sources）；failure 查询改用该 `VERIFICATION_CODE` 问题；补充注释说明 `UserQueryService` 空上下文短路为何要求先 seed evidence。  
**非目标**：不改 failure 断言（502/50201/`success=false`）、Phase 2 恢复逻辑、端口、`RUN_ID` 隔离；不修改其他脚本或生产代码。  
**验收标准**：通过 Docker Compose 执行 `query_stub_failure_test.sh` 退出码 0，Phase 1 断言 502/50201/`success=false` 全部 PASS，Phase 2 恢复 success `code=0` PASS；`query_stub_success_test.sh` 仍通过；脚本 diff 仅含 evidence 准备与注释，无断言/端口/恢复逻辑改写。  
**验证方式**：`bash scripts/tests/http/query_stub_failure_test.sh http://localhost:8083`（经 Docker Compose，`rag-service-smoke` 可用）；`bash scripts/tests/http/query_stub_success_test.sh http://localhost:8083`；人工检查 `git diff -- scripts/tests/http/query_stub_failure_test.sh` 确认仅 evidence 准备与注释。  
**涉及文件**：`scripts/tests/http/query_stub_failure_test.sh`

## 验收记录

> 待执行 session 完成实现与自测后，由未参与实现的独立验收 session 给出最终判定。

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |

## 阻塞记录

无。发生阻塞时记录原因、当前进度、解除条件、解除方、下一步与日期。

## 废弃任务记录

无。任务废弃时记录原因、日期及替代任务或决策。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-25 | 创建 Hotfix | plan_16 独立验收定位 query_stub_failure_test.sh 因未 seed evidence 导致 failure 路径不可达；缺陷源自 plan_7 任务 7.7 原始测试设计 | 初始范围为单脚本 evidence 准备修复 |
