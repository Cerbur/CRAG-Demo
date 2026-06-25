---
name: accept-crag-plan
description: Use when independently accepting, re-accepting, verifying, approving, or rejecting a CRAG-Demo Plan or Hotfix after implementation handoff, including “验收 plan15”, “重新验收 plan_15”, and failed acceptance recording.
---

# Accept CRAG Plan

对 `plan/plan_N/plan_N.md` 或 `plan/plan_N/plan_N.hotfix_M.md` 做独立验收时使用本 Skill。`constraints/plan-workflow.md` 是唯一权威；冲突时以约束文件为准。

## Superpowers 启动协议

1. 先读取并遵守 `superpowers:using-superpowers`。
2. 使用 `superpowers:requesting-code-review` 的审查思路核对实现结果、提交证据、测试覆盖和范围风险。
3. 声称验收通过、验收失败、测试通过或完成前，必须使用 `superpowers:verification-before-completion`。
4. 发现测试失败、行为异常或实现与 Plan 不一致时，使用 `superpowers:systematic-debugging` 定位事实；验收 session 不实现修复。

## 硬边界

- 只充当独立验收 session，不充当实现或修复 session。
- 不写产品代码、不补测试、不重构。
- 不创建 Hotfix，除非用户明确要求“发现问题后创建 hotfix plan”。
- 不把验收提交或交接提交写入任务实现提交栏。
- 未经明确要求，不 push、不创建 PR、不合并、不改写历史。
- 保留用户已有工作区变更；不得覆盖、清理或混入无关改动。

## 验收前读取

按顺序读取：

1. `AGENTS.md`。
2. `constraints/plan-workflow.md`。
3. `constraints/test-workflow.md`。
4. `plan/index/README.md`。
5. 目标 Plan 全文及直接相关 Hotfix。
6. Plan 引用或变更范围涉及的约束：`constraints/code-style.md`、`constraints/api-style.md`、`constraints/persistence-style.md`、`constraints/retrieval-style.md`、`constraints/package-structure.md`、`constraints/docker-structure.md`。
7. 任务提交栏记录的实现 hash、验收失败记录、变更记录和必要代码/测试。

必须运行 `git status --short`。若存在与验收文件或目标代码重叠的未提交改动，停止并请用户决定；无关改动不得纳入提交。

## 验收流程

1. 解析目标 Plan，确认文件存在且目标唯一。
2. 确认 Plan 状态适合验收：通常为 `verifying`，或存在待重新验收的退回记录。
3. 逐个核对待验收任务：
   - 读取任务目标、范围、非目标、验收标准、验证方式和涉及文件。
   - 对任务提交栏的每个实现 hash 运行 `git show --stat <hash>`，必要时阅读 diff。
   - 确认实现提交服务对应任务，且没有明显混入无关范围。
4. 按 Plan 验收标准和 `constraints/test-workflow.md` 运行必要验证命令。Docker HTTP 回归、真实供应商条件验收和跳过项必须按约束处理。
5. 核对 Plan、索引、任务状态、整体进度、完成日期、验收记录、变更记录一致性。

## 验收通过

只有全部有效任务满足验收标准、必需验证有新鲜证据且无阻塞项时，才能通过：

1. 将通过验收的任务标为 `✅ 完成`，填写完成日期。
2. 将 Plan YAML `status` 更新为 `completed`，同步 `updated`。
3. 追加具体验收记录：日期、命令、结果、关键证据、未执行项及原因。
4. 更新 `plan/index/README.md` 的状态、进度、队列和摘要。
5. 创建最终验收提交，主题通常为 `plan(plan_N): complete acceptance` 或 `plan(plan_N.hotfix_M): complete acceptance`。

## 验收失败

若任一必需验收标准不满足、命令失败、证据不足或环境阻塞：

1. 不修代码。
2. 在验收记录中写明失败任务、失败证据、命令输出摘要、未满足标准和日期。
3. 按 `constraints/plan-workflow.md` 将相关任务和 Plan 退回 `in_progress` 或 `blocked`。
4. 同步 `plan/index/README.md`。
5. 创建状态更新提交，主题通常为 `plan(plan_N): record failed acceptance`；重新验收失败可用 `plan(plan_N): record failed re-acceptance`。

## 交付前检查

- [ ] 已读取目标 Plan、索引、适用约束和 Git 状态。
- [ ] 已核对所有任务实现 hash。
- [ ] 已运行或准确记录所有必需验证。
- [ ] 验收结论逐条对应验收标准。
- [ ] Plan 与索引状态、进度和队列一致。
- [ ] 没有修改实现代码或把验收提交写入实现提交栏。
