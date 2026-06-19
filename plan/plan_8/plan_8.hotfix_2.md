---
workflow_version: 3
plan_id: plan_8.hotfix_2
type: hotfix
parent_plan: plan_8
status: in_progress
created: 2026-06-19
updated: 2026-06-20
---

# plan_8.hotfix_2 — Plan 独立验收 Session 工作流

## 背景与目标

workflow v2 假定 Parent Agent 持续管理执行流程，并将 SubAgent 限定为只报告结果。后续执行不再采用 Parent Agent / SubAgent 编排，需要将工作流改为可跨独立 agent session 接力的模型：执行 session 完成实现、自测、提交和交接，独立验收 session 审查提交、运行最终验证并拥有 Plan 完成权。

本 Hotfix 将工作流升级到 v3，删除失真的 `owner` 元信息，新增 Plan 级 `verifying` 状态与独立验收队列，并迁移全部 workflow v2 Plan。

## 范围

- 将 Plan 工作流升级到 v3，定义执行 session、独立验收 session 和失败退回边界。
- 新增 Plan 级 `verifying` 状态，并拆分执行队列与验收队列。
- 删除全部 workflow Plan、模板和校验器中的 `owner`。
- 修改待验收任务的提交记录规则：交接前回填真实实现 hash，不再使用 `pending`。
- 更新测试工作流、模板、校验器及其测试。
- 将全部 workflow v2 Plan 迁移为 v3；已完成历史计划不重新验收。

## 非目标

- 不修改 Java、Docker、数据库或业务运行时行为。
- 不重新验收已完成的历史 Plan。
- 不自动创建或调度新的 agent session。
- 验收 session 不修改实现代码。

## 前置依赖

- **执行前置 Plan**：`plan_8`
- `plan_8` 已完成并建立 workflow v2；本 Hotfix 是 v2 → v3 的引导迁移。

## 文件边界

- `constraints/plan-workflow.md`
- `constraints/test-workflow.md`
- `plan/index/README.md`
- `plan/templates/**`
- `plan/plan_*/plan_*.md`
- `scripts/validate_plans.py`
- `scripts/tests/test_validate_plans.py`

## 关联范围与规模说明

- 关联全部 workflow v2 Plan 文件，但仅迁移工作流元信息和共享状态规则，不改变历史任务范围或业务模块。
- 共 4 个治理任务，继续归属引入 workflow v2 的 `plan_8`，无需升级为新主 Plan。

## 关键决策

- 执行与验收必须由两个独立 session 承担；只有未参与实现的新验收 session 拥有最终完成权。
- Plan 状态增加 `verifying`，主路径为 `draft → ready → in_progress → verifying → completed`。
- 全部有效任务进入待验收后，Plan 才整体交接；验收失败时相关任务及 Plan 退回 `in_progress`。
- `verifying ↔ blocked` 用于验收环境或外部依赖阻塞；实现或测试缺陷使用 `verifying → in_progress`。
- 索引分别维护执行队列和验收队列；前置 Plan 只有 `completed` 才算依赖完成。
- 执行 session 在交接前创建实现提交和独立交接提交，任务提交栏记录真实实现 hash。
- 验收 session 只审查、验证和更新 Plan/索引；发现实现问题时记录证据并退回，不直接修改实现代码。
- 验收通过后创建最终验收提交；该提交不属于任务实现证据。
- v3 删除 `owner`；所有 workflow v2 文件统一迁移，已完成历史 Plan 保持完成且不补造验收记录。

## 未决问题

无。

## 风险与回滚

- 全量迁移可能遗漏旧字段或状态文案，通过全仓检索、校验器测试和严格仓库校验发现。
- 双队列解析依赖受限 Markdown 结构，通过模板约束和针对缺失、重复、错放及依赖顺序的测试固定。
- 本 Hotfix 使用 v3 自举，旧校验器在 8.hotfix_2.1 完成前会把本文件视为历史 Plan；这是迁移窗口，不允许其他 Plan 在该窗口并行修改工作流文件。
- 所有变更均为文档和开发工具，可通过逆序撤销本 Hotfix 提交回滚，无运行时数据影响。

## 测试与验证计划

- 测试先行：先增加 v3 元信息、Plan 待验收状态、真实提交 hash、双队列和 owner 禁止规则的失败测试。
- 运行 `python3 -m unittest scripts.tests.test_validate_plans -v`。
- 运行 `python3 scripts/validate_plans.py --strict`。
- 运行 `./gradlew check`。
- 运行 `rg -n '^workflow_version: 2$|^owner:|提交必须为 pending|load_v2_plans|workflow v2 规则' constraints plan/templates scripts`。
- 运行 `git diff --check` 并核对实现提交范围。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 8.hotfix_2.1 | 以测试先行升级 v3 校验规则 | 🚧 进行中 | 823df0a, a2c4ce8 | — |
| 8.hotfix_2.2 | 重写执行与独立验收工作流约束 | ✅ 完成 | 823df0a | 2026-06-20 |
| 8.hotfix_2.3 | 迁移模板、索引和全部 v2 Plan | ✅ 完成 | 823df0a | 2026-06-20 |
| 8.hotfix_2.4 | 完成实现验证并交接独立验收 | ✅ 完成 | 823df0a | 2026-06-20 |

整体进度：3 / 4（75%）

## 8.hotfix_2.1 以测试先行升级 v3 校验规则

**目标**：让机械校验准确表达 workflow v3 的元信息、状态、提交证据和双队列规则。  
**前置任务**：无  
**范围**：先增加失败测试，再修改 Python 校验器支持 v3、禁止 `owner`、校验 Plan `verifying`、待验收任务真实 hash，以及执行/验收队列完整性。  
**非目标**：不引入第三方 Markdown 或 YAML 解析依赖。  
**验收标准**：新增测试先因缺少 v3 行为失败，修改后全部通过；v3 文件出现 `owner`、待验收任务使用 `pending`、Plan/任务状态不匹配或队列错放时校验失败。  
**验证方式**：运行 `python3 -m unittest scripts.tests.test_validate_plans -v` 并保留 red-green 结果摘要。  
**涉及文件**：`scripts/validate_plans.py`、`scripts/tests/test_validate_plans.py`

## 8.hotfix_2.2 重写执行与独立验收工作流约束

**目标**：删除 Parent Agent / SubAgent 编排假设，建立可由独立 session 接力且职责封闭的执行与验收流程。  
**前置任务**：8.hotfix_2.1  
**范围**：升级 `plan-workflow.md` 到 v3；同步 `test-workflow.md` 的最终验收责任；明确状态机、提交、交接、失败退回、阻塞和完成门槛。  
**非目标**：不定义具体 agent 产品或自动调度工具。  
**验收标准**：约束明确执行 session 无完成权、验收 session 无实现修改权；Plan 级待验收、双队列和独立性定义没有冲突。  
**验证方式**：逐条核对本 Hotfix 关键决策，并运行冲突关键词检索。  
**涉及文件**：`constraints/plan-workflow.md`、`constraints/test-workflow.md`

## 8.hotfix_2.3 迁移模板、索引和全部 v2 Plan

**目标**：让仓库内所有受严格管理的 Plan 使用同一 v3 元信息和索引结构。  
**前置任务**：8.hotfix_2.1、8.hotfix_2.2  
**范围**：模板改为 v3；全部 v2 Plan 升级版本并删除 `owner`；索引新增验收队列并登记本 Hotfix。  
**非目标**：不更改历史任务状态、提交 hash、完成日期和既有验收证据。  
**验收标准**：仓库不存在 `workflow_version: 2` 或 `owner` 元信息；已完成 Plan 保持完成；所有未完成 Plan 恰好位于执行或验收队列之一。
**验证方式**：运行全仓检索和 `python3 scripts/validate_plans.py --strict`。  
**涉及文件**：`plan/templates/**`、`plan/plan_*/plan_*.md`、`plan/index/README.md`

## 8.hotfix_2.4 完成实现验证并交接独立验收

**目标**：证明 v3 迁移在仓库级验证中通过，并留下独立验收 session 可复现的完整交接。  
**前置任务**：8.hotfix_2.1、8.hotfix_2.2、8.hotfix_2.3  
**范围**：运行全部计划验证命令；创建实现提交；回填真实 hash；将全部任务与 Plan 标为待验收；同步验收队列。  
**非目标**：本 session 不执行最终独立验收，不将 Hotfix 标记完成。  
**验收标准**：单元测试、严格 Plan 校验、Gradle check、关键词检索和 diff 检查通过；实现 hash 已回填；工作区干净；索引把本 Hotfix列入验收队列。  
**验证方式**：执行测试与验证计划全部命令，并由后续独立验收 session 使用 `git show --stat <hash>` 复核。  
**涉及文件**：`plan/plan_8/plan_8.hotfix_2.md`、`plan/index/README.md`

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-19 | macOS / Python 3 | `python3 -m unittest scripts.tests.test_validate_plans -v` | 通过 | 新增 v3 测试先确认 12 项预期失败，完成实现后 18/18 通过 |
| 2026-06-19 | macOS / Git repository | `python3 scripts/validate_plans.py --strict` | 通过 | 0 error；24 条 warning 均为缺少版本元信息的兼容历史 Plan |
| 2026-06-19 | macOS / Gradle 9.4.1 / Java 21 | `./gradlew check` | 通过 | 沙箱内受文件锁通信限制；获准在沙箱外执行后 `BUILD SUCCESSFUL in 10s`，51 个任务完成 |
| 2026-06-19 | Git working tree | v3 残留关键词检索与 `git diff --check` | 通过 | 现行约束、模板和校验器无 v2 元信息、owner 或 pending 提交规则残留；无 whitespace error |
| 2026-06-20 | macOS / Python 3 | `python3 -m unittest scripts.tests.test_validate_plans -v` | 通过 | 独立验收复跑 18/18 通过 |
| 2026-06-20 | macOS / Git repository | `python3 scripts/validate_plans.py --strict --verify-git` | 通过 | 0 error；24 条 warning 均为允许保留的兼容历史 Plan |
| 2026-06-20 | macOS / Gradle 9.4.1 / Java 21 | `./gradlew check` | 通过 | 沙箱内文件锁通信受限；沙箱外 `BUILD SUCCESSFUL in 6s`，51 项任务中 3 项执行、48 项缓存命中 |
| 2026-06-20 | 临时双队列 fixture | 验收中构造 `plan_9` 待验收、`plan_10` 依赖 `plan_9` 却进入执行队列并调用 `validate_index` | 失败 | 校验结果为空；`validate_index` 只对执行队列内依赖排序，忽略验收队列中的未完成前置 Plan，违反“前置 Plan 只有 completed 才放行” |
| 2026-06-20 | macOS / Python 3 | 新增跨队列依赖回归测试并运行单测 | 通过 | 新用例先稳定失败；修复后目标用例通过，完整套件 19/19 通过 |
| 2026-06-20 | macOS / Git repository | `python3 scripts/validate_plans.py --strict --verify-git` | 通过 | 0 error；24 条 warning 均为允许保留的兼容历史 Plan |
| 2026-06-20 | macOS / Gradle 9.4.1 / Java 21 | `./gradlew check` | 通过 | `BUILD SUCCESSFUL in 3s`；51 项任务中 3 项执行、48 项缓存命中 |
| 2026-06-20 | 临时依赖状态 fixture | 构造 `plan_9` 为 `abandoned`、`plan_10` 依赖 `plan_9` 且进入执行队列并调用 `validate_index` | 失败 | 校验结果为空；修复只拦截 `verifying`，仍把 `abandoned` 前置视作可放行，未实现“只有 `completed` 才算依赖完成” |

## 阻塞记录

无。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-19 | 创建 v3 引导 Hotfix 并设为待开始 | Parent Agent / SubAgent 执行模型不再适用 | 建立独立执行与验收 session 的迁移范围 |
| 2026-06-19 | 开始执行全部迁移任务 | 执行基线提交 `03dddcc` 已创建 | Plan 转为进行中，开始校验器、约束和全量元信息迁移 |
| 2026-06-19 | 完成实现并交接独立验收 | 实现提交 `823df0a` 已创建，执行侧验证全部通过 | 四项任务与 Plan 转为待验收，本 session 不拥有最终完成权 |
| 2026-06-20 | 独立验收退回校验器任务 | 发现待验收前置 Plan 未完成时，依赖它的后续 Plan 可进入执行队列且不报错 | 8.hotfix_2.1 与 Plan 退回进行中；其余三项验收通过并完成 |
| 2026-06-20 | 修复跨队列依赖闸门并重新交接 | 回归测试与实现提交 `a2c4ce8` 已创建，仓库级验证通过 | 8.hotfix_2.1 与 Plan 返回待验收；其余三项保持完成 |
| 2026-06-20 | 独立复验再次退回校验器任务 | `abandoned` 前置仍可静默放行，修复未覆盖“非 completed 一律不得放行”的完整规则 | 8.hotfix_2.1 与 Plan 再次转为进行中；需按状态事实统一判断并补回归测试 |
