---
workflow_version: 3
plan_id: plan_9.hotfix_5
type: hotfix
parent_plan: plan_9
status: completed
created: 2026-06-19
updated: 2026-06-19
---

# plan_9.hotfix_5 — 四类异常测试迁移到 @WebMvcTest

## 背景与目标

`plan_9.hotfix_4` 将 `AdminRagControllerComponentTest` 切换为 `@WebMvcTest`，但 `GlobalExceptionHandlerComponentTest` 仍使用 `standaloneSetup`。Review 指出 `@Import(TestExceptionController.class)` 可将测试 Controller 加入 MVC Slice，消除剩余偏差。

## 范围

- `GlobalExceptionHandlerComponentTest` 从 `MockMvcBuilders.standaloneSetup` 迁移为 `@WebMvcTest` + `@Import({TestExceptionController.class, GlobalExceptionHandler.class})`
- Bean Validation 三个测试从本类移除（由 `AdminRagControllerComponentTest` 覆盖），保持三类异常测试（`IllegalArgumentException`、`NoResourceFoundException`、兜底 `RuntimeException`）
- `TestExceptionController` 重复路径确认无冲突

## 非目标

- 不修改 AdminRagControllerComponentTest（已完成）
- 不新增测试场景
- 不修改生产代码

## 前置依赖

- **执行前置 Plan**：`plan_9.hotfix_4`
- `plan_9.hotfix_4` 已完成构造器注入和字段断言修正

## 文件边界

- `crag-api/src/test/java/ai/cerbur/crag/api/controller/advice/GlobalExceptionHandlerComponentTest.java`
- `plan/plan_9/plan_9.hotfix_5.md`
- `plan/index/README.md`

## 关键决策

- 使用 `@WebMvcTest` + `@Import({TestExceptionController.class, GlobalExceptionHandler.class})`。`@Import` 显式注册 test source set 中的 Controller 和 main source set 中的 Advice，`@WebMvcTest` 提供完整 Spring MVC Slice。
- Bean Validation 测试保留在 `AdminRagControllerComponentTest` 中，该类已使用 `@WebMvcTest` 并通过 `@Import(StubConfig.class)` 提供完整 MVC 上下文。

## 未决问题

无。

## 风险与回滚

- `TestExceptionController` 的 `/api/v1/test/exception/**` 路径确认不与任何生产端点冲突。
- 失败时可回退为 `standaloneSetup`。

## 测试与验证计划

- `./gradlew :crag-api:test` 验证 7 个组件测试全部通过（AdminRag × 4 + ExceptionHandler × 3）
- `./gradlew check` 全量回归

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 9.hotfix_5.1 | 四类异常测试迁移到 @WebMvcTest | ✅ 完成 | 2bb9236 | 2026-06-19 |

整体进度：1 / 1（100%）

## 9.hotfix_5.1 四类异常测试迁移到 @WebMvcTest

**目标**：GlobalExceptionHandlerComponentTest 使用 @WebMvcTest + @Import 替代 standaloneSetup。
**前置任务**：无
**范围**：GlobalExceptionHandlerComponentTest 重写为 @WebMvcTest + @Import；移除 standaloneSetup、LocalValidatorFactoryBean、手工 stub AdminRagService；Bean Validation 测试移除（由 AdminRagControllerComponentTest 覆盖）。
**非目标**：不新增测试场景；不修改 AdminRagControllerComponentTest。
**验收标准**：三类异常映射通过真实 Spring MVC DispatcherServlet → Advice 链路；测试全部通过。
**验证方式**：`./gradlew :crag-api:test`
**涉及文件**：`crag-api/src/test/java/ai/cerbur/crag/api/controller/advice/GlobalExceptionHandlerComponentTest.java`

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-19 | 本地 macOS | `./gradlew :crag-api:test --rerun-tasks` | ✅ 通过 | 13 个任务重新执行；异常映射通过 `@WebMvcTest` MVC Slice 验证 |
| 2026-06-19 | 本地 macOS | `./gradlew check` | ✅ 通过 | 全量 Gradle、格式、模块依赖、约束与 Plan 校验通过 |
| 2026-06-19 | 本地 macOS | `python3 scripts/validate_constraints.py` | ✅ 通过 | 0 errors |
| 2026-06-19 | 本地 macOS | `python3 scripts/validate_plans.py --strict --verify-git` | ✅ 通过 | 0 errors；仅历史 Plan workflow v2 警告 |
| 2026-06-19 | Docker Compose | `bash scripts/tests/http/admin_rag_contract_test.sh http://localhost:8080` | ✅ 通过 | 14/14 断言通过；runId=contract-20260619-185110-25663 |
| 2026-06-19 | 本地 macOS | `git show --stat 2bb9236`、`git diff --check` | ✅ 通过 | 实现提交仅修改目标组件测试；无空白错误 |

## 阻塞记录

无。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-19 | 创建并转为 ready | plan_9.hotfix_4 review 发现 GlobalExceptionHandlerComponentTest 未使用 @WebMvcTest | 建立 1 项修复 |
| 2026-06-19 | 开始执行 9.hotfix_5.1 | ready Plan 与索引已提交，进入实现阶段 | Plan 转为 in_progress，任务转为进行中 |
| 2026-06-19 | 9.hotfix_5.1 进入待验收 | MVC Slice 测试实现已提交，等待最终全量验证与提交范围核对 | 任务提交栏暂记 pending |
| 2026-06-19 | 验收通过并完成 | 实现提交 `2bb9236` 范围正确，模块测试、全量门禁和 Docker HTTP 回归全部通过 | 回填实现提交，任务与 Hotfix 标记完成，执行队列恢复至 plan_7 |
