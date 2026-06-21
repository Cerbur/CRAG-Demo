---
workflow_version: 3
plan_id: plan_8.hotfix_3
type: hotfix
parent_plan: plan_8
status: verifying
created: 2026-06-21
updated: 2026-06-21
---

# plan_8.hotfix_3 — 项目级 Plan 执行 Skill

## 背景与目标

现有 `execute-plan-with-opencode` 已停用，且其显式触发、外部编排和任务完成规则不再适合 workflow v3。创建项目级 `execute-crag-plan` Skill，让“执行 plan7”“继续 plan_7”及验收退回修复等请求稳定路由到同一执行流程，并强制完成实现提交与独立交接。

## 范围

- 创建 `execute-crag-plan` 项目级 Skill 及 UI 元数据。
- 路由 Plan 执行、恢复和验收退回修复意图。
- 更新 `AGENTS.md` 与项目 Skill 索引。
- 将旧 OpenCode Skill 标记为停用，不删除历史目录。

## 非目标

- 不修改 workflow v3 的权威规则。
- 不实现自动 agent 调度、push、PR 或最终独立验收。
- 不修改 Java、Docker 或业务运行时行为。

## 前置依赖

- **执行前置 Plan**：`plan_8`
- `plan_8` 与 `plan_8.hotfix_2` 已完成。

## 文件边界

- `skill/execute-crag-plan/**`
- `skill/README.md`
- `AGENTS.md`
- `plan/plan_8/plan_8.hotfix_3.md`
- `plan/index/README.md`

## 关联范围与规模说明

- 归属引入 Plan 执行 Skill 与 workflow 治理的 `plan_8`。
- 仅涉及项目协作入口、Skill 和 Plan 簿记，不跨业务模块。

## 关键决策

- `constraints/plan-workflow.md` 继续作为唯一权威；Skill 只固化读取顺序和执行闭环。
- Skill 允许隐式触发，覆盖首次执行、恢复执行和独立验收退回修复。
- 执行 session 只能交接到 `verifying`，不得把任务或 Plan 标记为完成。
- 代码实现与 Plan 簿记使用两个独立提交；交接提交回填真实实现 hash 并同步索引验收队列。
- 旧 OpenCode Skill 仅标记停用，避免删除历史文件。

## 未决问题

无。

## 风险与回滚

- Skill 触发过宽可能误入执行流程；description 仅覆盖明确 Plan 执行、继续和修复意图。
- 规则重复可能漂移；Skill 不复制完整状态机，只引用权威约束并保留强制检查点。
- 可逆序撤销 Skill、路由和索引提交恢复原状，无运行时数据影响。

## 测试与验证计划

- 按用户要求不做模型前向测试。
- 仅检查 Skill 目录结构、YAML 元数据和 Git diff。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 8.hotfix_3.1 | 创建并路由项目级 Plan 执行 Skill | 🔍 待验收 | 8f3baf1 | — |

整体进度：0 / 1（0%）

## 8.hotfix_3.1 创建并路由项目级 Plan 执行 Skill

**目标**：让低成本模型也能按 workflow v3 完成 Plan 实现、提交和独立验收交接。  
**前置任务**：无  
**范围**：创建 Skill 与元数据；更新项目入口和 AGENTS 路由；停用旧 Skill 索引。  
**非目标**：不修改 workflow v3 权威规则，不执行具体业务 Plan。  
**验收标准**：明确 Plan 执行、恢复与验收退回修复均触发新 Skill；Skill 强制读取 Plan、索引与约束，重建状态，完成实现提交和独立交接提交，且禁止执行 session 标记完成。  
**验证方式**：检查文件结构、frontmatter、路由文本和 Git diff。  
**涉及文件**：`skill/execute-crag-plan/**`、`skill/README.md`、`AGENTS.md`

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-21 | macOS / Git | 目录结构、frontmatter、路由文本与实现提交范围检查 | 通过 | `execute-crag-plan` 由官方初始化脚本创建；Skill、UI 元数据、项目索引和 AGENTS 路由均已提交到 `8f3baf1`。按用户要求未执行模型前向测试或额外 Skill 验证。 |

## 阻塞记录

无。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-21 | 创建 Hotfix 并设为待开始 | 旧 OpenCode Skill 已停用，通用路由不足以约束便宜模型完成 workflow v3 交接 | 暂时中断 plan_7，先建立项目级执行入口 |
| 2026-06-21 | 开始执行 Skill 创建任务 | Plan 基线提交 `0b39b1d` 已创建 | 创建新 Skill、更新项目入口并停用旧索引 |
| 2026-06-21 | 完成实现并交接独立验收 | Skill 与路由实现提交 `8f3baf1` 已创建 | 任务与 Hotfix 转为待验收；恢复 plan_7 执行队列 |
