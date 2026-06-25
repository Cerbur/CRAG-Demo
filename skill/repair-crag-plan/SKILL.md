---
name: repair-crag-plan
description: Use when fixing CRAG-Demo Plan or Hotfix implementation after independent acceptance failed, including “修复 plan15 验收失败项”, “处理验收退回”, and “fix failed acceptance for plan_15”.
---

# Repair CRAG Plan

验收失败后修复 `plan/plan_N/plan_N.md` 或 `plan/plan_N/plan_N.hotfix_M.md` 时使用本 Skill。本 Skill 是 `skill/execute-crag-plan/SKILL.md` 的验收退回修复入口；具体实现提交和交接规则仍以 `execute-crag-plan` 与 `constraints/plan-workflow.md` 为准。

## Superpowers 启动协议

1. 先读取并遵守 `superpowers:using-superpowers`。
2. 必须使用 `superpowers:systematic-debugging`，先根据验收失败证据定位根因。
3. 涉及代码或测试修复时使用 `superpowers:test-driven-development`：复现失败、最小修复、重新验证。
4. 声称修复完成、测试通过或准备提交前，必须使用 `superpowers:verification-before-completion`。
5. 多文件或高风险修复完成后，使用 `superpowers:requesting-code-review` 的审查思路自查。

## 硬边界

- 只修复独立验收明确退回的问题。
- 不执行最终验收，不把任务或 Plan 标为 `completed / 完成`。
- 不创建 Hotfix，除非用户明确要求。
- 不删除或改写历史失败证据；新的修复 hash 追加到原任务提交栏。
- 未经明确要求，不 push、不创建 PR、不合并、不改写历史。
- 保留用户已有工作区变更；不得覆盖、清理或混入无关改动。

## 必读入口

先读取并遵守：

1. `skill/execute-crag-plan/SKILL.md`。
2. `AGENTS.md`。
3. `constraints/plan-workflow.md`。
4. `constraints/test-workflow.md`。
5. `plan/index/README.md`。
6. 目标 Plan 全文、验收失败记录、直接相关 Hotfix。
7. 按影响范围读取专项约束：`constraints/code-style.md`、`constraints/api-style.md`、`constraints/persistence-style.md`、`constraints/retrieval-style.md`、`constraints/package-structure.md`、`constraints/docker-structure.md`。

必须运行 `git status --short`，并核对相关 `git log`、声明的实现 hash、失败记录与当前 diff。若工作区存在与修复范围重叠的用户改动，停止并请用户决定。

## 修复流程

1. 解析目标 Plan，确认文件存在且目标唯一。
2. 确认 Plan 状态允许修复：通常为 `in_progress`，或 `blocked` 且阻塞解除条件已被事实证明满足。`verifying` 状态不得修复，除非验收记录和索引已退回。
3. 从验收记录提取失败项：
   - 失败任务。
   - 失败命令或证据。
   - 未满足的验收标准。
   - 相关文件和预期行为。
4. 对每个失败项定位根因；修复范围不得超出退回问题和任务边界。
5. 测试先行：
   - 先新增或调整能复现失败的测试/回归脚本。
   - 运行并确认失败由目标缺陷引起。
   - 写最小实现修复。
   - 运行受影响检查和任务级完整验证。
6. 检查 diff，确认无无关文件、秘密、完整 Prompt、敏感响应或范围蔓延。

## 提交与交接

1. 创建修复实现提交，主题通常为 `fix(plan_N/N.X): repair failed acceptance item`。
2. 从 Git 读取真实短 hash，并用 `git show --stat <hash>` 核对范围。
3. 创建独立交接提交：
   - 将相关任务状态改为 `⏳ 待验收`。
   - 在任务提交栏追加真实修复实现 hash。
   - 完成时间保持为空。
   - 追加修复记录、验证命令、结果和残余风险。
   - 若整份 Plan 已重新具备验收条件，将 Plan 状态改为 `verifying`。
   - 同步 `plan/index/README.md`。
4. 交接提交主题通常为 `docs(plan_N): hand off acceptance retry`。

交接提交和最终验收提交都不是实现证据，不得写入任务提交栏。

## 交付前检查

- [ ] 已读取失败记录、目标 Plan、索引、适用约束和 Git 状态。
- [ ] 已用失败证据定位根因，而不是猜测修复。
- [ ] 已补充或更新覆盖失败场景的测试。
- [ ] 已运行任务要求和测试工作流要求的验证命令。
- [ ] 已创建实现提交与独立交接提交。
- [ ] Plan 与索引重新进入待验收状态且没有被标为完成。
