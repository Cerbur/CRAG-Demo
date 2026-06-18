# plan_8 — Plan 工作流 v2 工程治理

> 创建日期：2026-06-19  
> 状态：🔄 进行中
> 生效边界：本计划按修改前的 Plan 工作流创建；workflow v2 在本计划完成提交后正式生效。

## 背景与目标

现有 Plan 工作流已经约束目录、命名和进度表，但缺少计划分级、状态机、完成证据、提交边界、模板与自动校验。长期执行后出现了微小修改也需要 Hotfix、全局治理内容被塞入旧 Plan、索引状态漂移、完成任务缺少 commit hash，以及 Plan 执行 Skill 与约束文档定义不同等问题。

本计划建立 workflow v2：让计划既能被人清楚阅读，也能通过受限 YAML front matter 和静态校验器稳定验证。

## 范围

1. 重写 `constraints/plan-workflow.md`，固化本次 grilling 达成的工作流决策。
2. 新增主 Plan、Hotfix、方向变更归档模板。
3. 新增仅依赖 Python 标准库的 Plan 静态校验器及单元测试。
4. 将校验器接入根 Gradle `check`。
5. 同步 `AGENTS.md`、`CLAUDE.md` 和 `skill/execute-plan-with-opencode/SKILL.md`。
6. 将尚未开始的 `plan_7` 迁移为 workflow v2。

## 非目标

- 不重写所有历史 Plan，也不补造历史提交或测试证据。
- 不执行 `plan_7` 的业务实现。
- 不引入 Git hook、第三方 YAML 库或 CI 平台配置。
- 不自动生成或覆盖 `plan/index/README.md` 的人工摘要。

## 前置依赖

- 当前工作区无未提交改动。
- `plan_1` 至 `plan_6` 作为历史 Plan 保持兼容。
- `plan_7` 尚未开始，可安全迁移结构。

## 文件边界

- `constraints/plan-workflow.md`
- `plan/templates/**`
- `plan/plan_7/plan_7.md`
- `plan/plan_8/plan_8.md`
- `plan/index/README.md`
- `plan/plan_archive/README.md`
- `scripts/validate_plans.py`
- `scripts/tests/test_validate_plans.py`
- `build.gradle.kts`
- `AGENTS.md`
- `CLAUDE.md`
- `skill/execute-plan-with-opencode/SKILL.md`

## 关键决策

- workflow v2 Plan 使用受限 YAML front matter；YAML 只存 Plan 级元信息。
- 新规则对新建和未完成 Plan 生效，已完成历史 Plan 使用兼容模式。
- Plan 状态使用 `draft / ready / in_progress / blocked / completed / abandoned`。
- 任务额外支持 `verifying`，实现提交 hash 回填后才能完成。
- Plan 状态和任务事实来自 Plan 文件；索引是人工维护的汇总视图。
- 执行 ready Plan 视为授权创建必要本地提交，但不授权 push、PR 或改写历史。

## 风险与回滚

- 校验规则过严可能阻塞历史项目检查：通过 workflow 版本和兼容模式隔离。
- Markdown 解析容易受格式漂移影响：模板固定列名和章节，解析器仅支持明确格式。
- Gradle 接入可能影响常规 `check`：校验器保持标准库实现，失败输出提供文件与规则编号。
- 本计划为文档与开发工具治理，无运行时数据迁移；失败时可通过撤销对应提交恢复旧工作流。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 8.1 | 重写 Plan 工作流约束并明确 workflow v2 生效边界 | 🔄 进行中 | — | — |
| 8.2 | 新增主 Plan、Hotfix 和方向变更归档模板 | 🔄 进行中 | — | — |
| 8.3 | 实现计划静态校验器及单元测试 | ⏳ 待开始 | — | — |
| 8.4 | 接入 Gradle check | ⏳ 待开始 | — | — |
| 8.5 | 同步项目路由文档与 Plan 执行 Skill | ⏳ 待开始 | — | — |
| 8.6 | 迁移 plan_7 并完成全量验收 | ⏳ 待开始 | — | — |

整体进度：0 / 6（0%）

## 8.1 重写 Plan 工作流约束

**前置任务**：无  
**验收**：计划分级、状态机、完成门槛、提交协议、并行与恢复、索引和生效版本均有唯一且无冲突的定义。

## 8.2 新增模板

**前置任务**：8.1  
**验收**：主 Plan、Hotfix 和归档模板覆盖 workflow v2 必填元信息、章节和任务结构。

## 8.3 实现静态校验器

**前置任务**：8.1、8.2  
**验收**：校验器支持默认扫描、`--strict`、指定文件和 `--verify-git`；单元测试覆盖合法与非法 Plan、任务状态、进度及 commit 规则。

## 8.4 接入 Gradle check

**前置任务**：8.3  
**验收**：根项目存在 `validatePlans`，且 `check` 会执行计划校验。

## 8.5 同步路由文档与执行 Skill

**前置任务**：8.1  
**验收**：`AGENTS.md`、`CLAUDE.md` 不再重复维护状态；执行 Skill 不再允许无 commit 的完成状态，并以工作流约束为最高权威。

## 8.6 迁移 plan_7 并全量验收

**前置任务**：8.1 至 8.5  
**验收**：

- `plan_7` 通过 workflow v2 严格校验，但不执行其业务代码。
- 校验器单元测试、`python3 scripts/validate_plans.py --strict --verify-git` 和 `./gradlew check` 通过。
- 所有实现提交 hash 回填，索引同步，实际 diff 未越出文件边界。

## 验收记录

待执行后回填验证日期、环境、命令、结果摘要及未执行项。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-19 | 创建 plan_8，状态为待开始 | 完成 Plan 工作流优化 grilling | 建立 workflow v2 实施基线 |
| 2026-06-19 | 开始任务 8.1 与 8.2 | Plan 基线已提交 | 工作流约束与模板进入实施 |
