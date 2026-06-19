---
workflow_version: 2
plan_id: plan_9.hotfix_2
type: hotfix
parent_plan: plan_9
status: ready
owner: parent-agent
created: 2026-06-19
updated: 2026-06-19
---

# plan_9.hotfix_2 — 模块约束当前事实收敛

## 背景与目标

`plan_9` 已完成 Java 模块边界收紧，但 `constraints/package-structure.md` 仍保留执行过程中的目标标记、已消除偏差和过期实现索引，并同时声称 `crag-app` 仍承载 `TestController`、ArchUnit 尚有待清理例外。`constraints/api-style.md` 也保留了 `crag-admin` 迁移过程说明。

本 Hotfix 将约束文档收敛为“当前有效规则与当前事实”，完成历史继续由 Plan 保存。

## 范围

- 修正 `package-structure.md` 中与当前源码不一致的实现索引。
- 删除已完成的 `plan_9` 过程标记和已消除偏差。
- 保留并明确仍然有效的 Storage API 迁移例外。
- 精简 `api-style.md` 中已经完成的模块迁移历史。

## 非目标

- 不修改 Java、Gradle、Docker 或测试代码。
- 不修改约束结构或引入新的自动校验器。
- 不处理 `docker-structure.md`；部署契约漂移继续由 `plan_10` 负责。
- 不要求改写历史 Plan。

## 前置依赖

- **执行前置 Plan**：`plan_9`
- `plan_9` 已完成，当前模块结构和架构测试结果可作为事实基线。

## 文件边界

- `constraints/package-structure.md`
- `constraints/api-style.md`
- `plan/plan_9/plan_9.hotfix_2.md`
- `plan/index/README.md`

## 关联范围与规模说明

- 仅修正 `plan_9` 完成后的两份约束文档，共 1 个任务，不超过 Hotfix 规模上限。

## 关键决策

- `constraints/` 只维护当前有效规则、当前实现索引和仍存在的偏差，不保存已经完成的迁移过程。
- “已知偏差”章节继续保留，但只记录当前仍有效的 Storage API 迁移例外。
- 不扩大本次范围去自动判断文档与源码是否漂移。

## 未决问题

无。

## 风险与回滚

- 风险仅限文档表述遗漏；通过逐项对照源码路径、ArchUnit 与模块依赖校验降低风险。
- 修改不影响运行时；如需回滚，可撤销本 Hotfix 的约束文档提交。

## 测试与验证计划

- 当前事实核对：检查各模块源码路径与公开 API。
- 过期内容检查：确认不再出现 `crag-app` 下的 `TestController`、待清理 ArchUnit 例外及已消除偏差。
- Plan 校验：`python3 scripts/validate_plans.py --strict`。
- 全量检查：`./gradlew check`。
- 文档检查：`git diff --check`。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 9.hotfix_2.1 | 收敛模块与 API 约束的当前事实 | ⏳ 待开始 | — | — |

整体进度：0 / 1（0%）

## 9.hotfix_2.1 收敛模块与 API 约束的当前事实

**目标**：让模块和 API 约束只表达当前规则、当前实现与仍存在的偏差。  
**前置任务**：无  
**范围**：修正 `crag-app` 当前索引，删除 ArchUnit 待清理说明、已完成 Plan 标记和已消除偏差，保留 Storage API 迁移例外，精简 `crag-admin` 迁移历史。  
**非目标**：不修改部署约束、校验器、源码或历史 Plan。  
**验收标准**：约束与当前源码和架构测试事实一致；已完成迁移历史不再混入有效规则；Storage API 例外仍有明确边界。  
**验证方式**：运行 `rg` 核对过期标记，执行 `python3 scripts/validate_plans.py --strict`、`./gradlew check` 和 `git diff --check`。  
**涉及文件**：`constraints/package-structure.md`、`constraints/api-style.md`

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
| 2026-06-19 | 创建 Hotfix | `plan_9` 完成后约束仍混有过期实现事实和完成历史 | 建立 1 项文档收敛任务 |
