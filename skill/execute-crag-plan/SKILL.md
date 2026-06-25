---
name: execute-crag-plan
description: Use when executing, continuing, resuming, or implementing a CRAG-Demo Plan or Hotfix, including “执行 plan7”, “继续 plan_7”, and implementation handoff before independent acceptance.
---

# Execute CRAG Plan

用于执行 `plan/plan_N/plan_N.md` 或 `plan/plan_N/plan_N.hotfix_M.md`。本 Skill 是实现 session 入口：负责实现、测试、提交和交接验收；不负责最终独立验收。

`constraints/plan-workflow.md` 是 Plan 状态、提交、进度和验收边界的唯一权威；冲突时以该文件为准。

## Workflow

1. 必须使用 superpowers 的 skill workflow：
   - 先读取并遵守 `superpowers:using-superpowers`。
   - 实现代码或测试前使用 `superpowers:test-driven-development`。
   - 如果是验收失败后的执行退回修复，同时使用 `superpowers:systematic-debugging`，先根据失败证据定位根因。
   - 在声称实现完成、测试通过或准备提交前，必须使用 `superpowers:verification-before-completion`，以新鲜命令输出作为证据。
   - 多文件、高风险或跨模块任务完成后，使用 `superpowers:requesting-code-review` 的审查思路自查变更。

2. 必须遵守 CRAG-Demo 仓库约束：
   - 读取 `AGENTS.md`。
   - 读取并遵守 `constraints/plan-workflow.md`。
   - 查询计划状态时优先读取 `plan/index/README.md`。
   - 读取目标 Plan 全文；Hotfix 必须解析到具体 `plan_N.hotfix_M`。
   - 涉及 Java 代码时读取 `constraints/code-style.md`。
   - 涉及 Controller、HTTP DTO、响应结构或异常映射时读取 `constraints/api-style.md`。
   - 涉及 Entity、Repository、DAO、事务或 CAS 时读取 `constraints/persistence-style.md`。
   - 涉及 Sparse、Dense、RRF、Rerank 或检索结果类型时读取 `constraints/retrieval-style.md`。
   - 涉及包结构调整时读取并同步 `constraints/package-structure.md`。
   - 涉及 Docker 部署结构调整时读取并同步 `constraints/docker-structure.md`。
   - 涉及测试命令和验证方式时读取 `constraints/test-workflow.md`。

## Preflight

执行前必须完成：

1. 将用户输入中的 `plan7`、`Plan 7`、`plan_7` 等解析为唯一 Plan 文件；无法消歧或文件不存在时停止。
2. 运行 `git status --short`，确认工作区安全。与当前任务重叠的用户改动、其他 Plan 改动或归属不明改动必须先询问用户；无关改动保留且不得纳入提交。
3. 核对 Plan YAML 状态、`plan/index/README.md` 状态、进度表、任务详情和前置依赖是否一致。
4. Plan 通常必须是 `ready` 或 `in_progress` 才能执行：
   - `ready`：开始首个任务前按约束转为 `in_progress`。
   - `in_progress`：执行未完成任务，或修复验收退回任务。
   - `verifying`：不得实现，提示用户启动独立验收 session。
   - `draft`：不得编码，先补全并提交为 `ready`。
   - `blocked`：只有阻塞解除条件已被事实证明满足时才可恢复。
   - `completed / abandoned`：不得继续实现；如需修复，按约束创建 Hotfix 或新 Plan。
5. 从进度表和任务详情交叉确认本次只执行一个合法任务。前置任务未完成时停止。

## Implementation

执行当前任务时：

1. 先向用户简要说明：准备执行的任务编号和名称、范围、非目标、涉及文件、验收标准和验证方式。
2. 严格测试先行：
   - 写测试或回归检查。
   - 运行并确认预期失败；失败必须来自目标缺失行为，而不是测试自身错误。
   - 写最小实现。
   - 运行并确认通过。
   - 必要时重构，并重新运行相关验证。
3. 只修改当前任务允许范围。发现必须越界、现有无关测试失败、Plan 目标不足或约束冲突时停止并报告，不要顺手扩张。
4. 运行任务“验证方式”和测试计划要求的全部命令。涉及 Docker HTTP 回归、真实供应商边界或外部依赖时，按 `constraints/test-workflow.md` 处理，不得用轻量测试替代。
5. 检查 diff，确认没有无关文件、秘密、完整 Prompt、敏感响应或用户未授权改动。

## Commits And Handoff

实现完成后：

1. 创建实现提交：
   - 主题通常为 `feat(plan_N/N.X): ...`。
   - 修复验收退回项通常为 `fix(plan_N/N.X): repair failed acceptance item`。
   - 提交只包含当前任务实现、测试和直接相关文档。
   - 不混入 Plan 进度、hash 回填、交接状态或无关改动。
2. 读取真实短 hash，并用 `git show --stat <hash>` 核对实现提交范围。
3. 创建独立交接提交：
   - 将任务状态更新为 `⏳ 待验收`。
   - 在任务提交栏写入真实实现短 hash；验收退回修复时追加新 hash，不删除旧证据。
   - 完成时间保持为空。
   - 追加实际验证记录：命令、结果、日期、环境、未执行项和风险。
   - 若整份 Plan 已无待执行有效任务，将 Plan YAML 状态改为 `verifying`。
   - 同步 `plan/index/README.md` 的状态、进度和队列。
   - 交接提交主题通常为 `docs(plan_N): hand off implementation` 或 `docs(plan_N): hand off acceptance retry`。

交接提交和最终验收提交都不是实现证据，不得写入任务提交栏。

## Final Response

输出必须包含：

- 执行的 Plan 和任务。
- 修改过的关键文件。
- 新增或更新的测试。
- 实际运行的验证命令和结果。
- 实现提交 hash。
- 交接提交 hash。
- 当前 Plan 状态，以及是否已交给独立验收。

## Boundaries

- 不执行最终验收。
- 不把任务或 Plan 标记为 `completed / 完成`。
- 不 push，不开 PR，不合并，不改写历史，除非用户明确要求。
- 每次只执行一个任务；交接后等待用户确认再继续下一任务。
- 用户要求独立验收时，改用 `skill/accept-crag-plan/SKILL.md`。
- 用户要求修复验收失败项时，改用 `skill/repair-crag-plan/SKILL.md`。
