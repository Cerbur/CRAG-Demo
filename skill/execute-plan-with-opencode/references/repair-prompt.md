# Repair Prompt (续会话修复)

codex 用此模板把**单轮验收 findings** 拼成修复 prompt，复用同一 opencode session（省 opencode 自己的 token，codex 只递 findings 不重发规则）。

## 触发条件

逐任务验收 checklist（见 `implementation-prompt.md`）任一项未过，且修复轮数 ≤ 3。

## 拼装后的 prompt（填完占位符发给 opencode）

```text
继续这个 session 修复下列验收 findings。只针对 findings 做最小修复，不扩大改动，不改你 agent 规则禁止的文件。

本轮 findings（来自调用方验收）:
[FINDINGS]

要求
1. 逐条 findings 给出根因 + 最小修复 + 回归测试（每个行为级 finding 一个回归）。
2. 修完重跑相关测试确认通过。
3. 用 git diff 自查改动仍在原 owned scope 内:
[OWNED_SCOPE]
4. 回复只包含结构化报告块（STATUS / CHANGED-FILES / TESTS-RUN / SKIPPED-OR-BLOCKED / SCOPE-CLAIM / NOTES），不输出过程叙述。
```

## codex 调用约定（续会话，复用上下文）

```bash
OPENCODE_CONFIG_DIR="<SKILL_DIR>/opencode-config" \
opencode run \
  -c \
  --session "[SESSION_ID]" \
  -m "[PROVIDER/MODEL]" \
  --dir "[REPO_ROOT]" \
  --format json \
  "[上面拼好的 repair prompt]" \
  2>&1 | tee -a "build/opencode-task-[TASK_ID].log"
```

提取与验收同 `implementation-prompt.md`（结构化报告块 + session id）。

## 轮数限制

- 每任务修复上限 **3 轮**（含首次实现后的验收失败）。
- 达到 3 轮仍未过：停止自动修复，向用户报告根因、剩余 findings、推荐人工介入。
- **不要**为修复失败新建独立 session——那会让 opencode 丢失上下文，反而更费 token，也违背续会话设计。
