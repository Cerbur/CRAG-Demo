# Implementation Prompt (opencode run)

codex 用此模板拼出**每任务一次**的 `opencode run` prompt。规则已固化在 `opencode-config/agents/crag-plan-implementer.md`，所以这里**只含任务上下文占位符**——这是省 codex token 的核心（规则不重复进 codex 上下文）。

## 拼装后的 prompt（填完占位符发给 opencode）

```text
实现 CRAG-Demo Plan 中的单个任务。严格按你的 agent 规则执行，最后只回结构化报告。

任务上下文
- 仓库根（--dir 指向它）: [REPO_ROOT]
- Plan: [PLAN_PATH]
- 任务: [TASK_ID_AND_TITLE]
- 验收标准:
[ACCEPTANCE_CRITERIA]
- owned scope（你只能动这些）:
[OWNED_SCOPE]
- 受保护、禁止改动的前置文件:
[PROTECTED_FILES]
- 相关约束文件（开工前读）:
[CONSTRAINT_PATHS]

要求
1. 开工前读 [PLAN_PATH]、AGENTS.md 和上面列出的约束文件。
2. 只实现这一任务及其测试，不扩大到其它任务。
3. 测试按 constraints/test-workflow.md 四层分类：纯单元 / 轻量组件 / 架构测试用 ./gradlew test 跑（不启动 Docker）；只有 Docker HTTP 回归按风险触发规则，如需触发就回 needs-info 交给调用方。
4. 改完用 git diff 自查没越出 owned scope。
5. 回复**只**包含你 agent 规则里定义的结构化报告块（STATUS / CHANGED-FILES / TESTS-RUN / SKIPPED-OR-BLOCKED / SCOPE-CLAIM / NOTES），不要输出过程叙述。
```

## codex 调用约定（见 SKILL.md「opencode 调用约定」一节）

完整 Bash 模板（codex 填占位符后执行）：

```bash
OPENCODE_CONFIG_DIR="<SKILL_DIR>/opencode-config" \
opencode run \
  --agent crag-plan-implementer \
  -m "[PROVIDER/MODEL]" \
  --dir "[REPO_ROOT]" \
  --format json \
  "[上面拼好的 prompt]" \
  2>&1 | tee "build/opencode-task-[TASK_ID].log"
```

提取关键字段（codex 只 parse 这些，不读全文；已在 opencode 1.17.4 上验证）：

```bash
# 最终文本（含结构化报告块）
jq -r 'select(.type=="text") | .part.text' \
  "build/opencode-task-[TASK_ID].log" | tail -n 80

# session id（用于续会话修复）
jq -r '.sessionID // empty' "build/opencode-task-[TASK_ID].log" | head -n1

# 退出原因与 token 用量（日志审计用）
jq -r 'select(.type=="step_finish") | {reason: .part.reason, tokens: .part.tokens}' \
  "build/opencode-task-[TASK_ID].log"
```

> `--format json` 的字段名随版本可能不同；若 `jq` 取不到，降级用 `tail` 取日志末尾的结构化报告块 + `git diff --stat` 交叉验证，不硬依赖精确 schema。

## codex 验收 checklist（轻量，逐任务）

收到 opencode 报告后，codex 只做：

1. **STATUS** 是否 `completed`？否则进续会话修复（见 `references/repair-prompt.md`）。
2. **越界检查**：`git diff --stat` 的文件列表是否全在 owned scope 内？越界即挂。
3. **测试审计**：TESTS-RUN 是否覆盖验收标准要求的范围？有无 SKIPPED 未说明理由？
4. **scope 未扩**：SCOPE-CLAIM 是否确认在 owned scope 内？
5. **Plan 校验**：`python3 scripts/validate_plans.py --strict [PLAN_PATH]`（轻，仅查格式）。

全过 → codex 提交实现 commit + 更新 plan 簿记（`verifying → completed`）→ 下一任务。
任一挂 → 拼进 repair-prompt 模板，续同一 session（`-c`）≤3 轮。
