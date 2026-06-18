# plan_6.hotfix_5 — OpenCode Plan 执行 Skill

> 创建日期：2026-06-19  
> 状态：✅ 完成  
> 归属：`plan_6` 项目级 Skill 工具链

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 6.hotfix_5.1 | 建立项目内 `execute-plan-with-opencode` Skill 结构 | ✅ | `a9ddc3c` | 2026-06-19 |
| 6.hotfix_5.2 | 固化 ParentAgent、SubAgent 与 OpenCode 的执行状态机 | ✅ | `a9ddc3c` | 2026-06-19 |
| 6.hotfix_5.3 | 固化实现与 Review 修复提示词 | ✅ | `a9ddc3c` | 2026-06-19 |
| 6.hotfix_5.4 | 更新项目 Skill 索引并完成静态校验 | ✅ | `a9ddc3c` | 2026-06-19 |

整体进度：4 / 4（100%）

## 背景

项目已经通过 `plan_6.hotfix_2` 至 `plan_6.hotfix_4` 建立项目级 Skill 目录、索引和校验方式，但尚未沉淀“按 Plan 逐任务执行”的实现工作流。当前需要新增一个仅显式调用的维护工具：由 ParentAgent 负责 Plan 完整度、测试流程、代码 Review 和最终验收；由隔离的 SubAgent 驱动外部 OpenCode CLI 编写或修复代码。

该 Skill 只在本仓库 `skill/` 目录维护并纳入 Git，不安装到全局 Skill 目录，也不修改 `AGENTS.md` 增加自动触发路由。

## 6.hotfix_5.1 建立项目内 Skill 结构

新增：

```text
skill/execute-plan-with-opencode/
├── SKILL.md
├── agents/
│   └── openai.yaml
└── references/
    ├── implementation-prompt.md
    └── repair-prompt.md
```

`agents/openai.yaml` 必须设置 `policy.allow_implicit_invocation: false`，确保只有显式 `$execute-plan-with-opencode` 调用才启用。

**验收**：Skill 名称、目录结构、元数据与显式调用策略符合 Skill 规范。

## 6.hotfix_5.2 固化执行状态机

在 `SKILL.md` 中定义以下硬约束：

- 启动时双重检查 OpenCode CLI、配置、凭据和所选模型可用性；本地未安装时立即停止并提示安装。
- ParentAgent 获取 `opencode models` 结果并让用户为整个 Plan 选择一次模型。
- Plan 通过九项完整度硬门槛后，按任务逐个闭环，最后执行整体验收。
- 每个任务新建一个实现 SubAgent 和 OpenCode session；补测试复用原 SubAgent/session。
- 每轮代码 Review 失败都新建修复 SubAgent/session，最多自动修复三轮。
- ParentAgent 独占 Plan 状态更新、代码 Review、测试执行和最终验收职责。
- 保护已有工作区改动，禁止自动 stash、reset、checkout、clean 或覆盖无关改动。
- 常规实现权限可由 SubAgent 在 OpenCode 交互界面逐次批准；高风险、系统级或范围外权限上浮 ParentAgent。
- 支持中断恢复，但必须先从 Plan、Git diff、测试证据和会话状态重建事实。

**验收**：所有进入条件、循环、停止条件、权限边界和恢复语义均明确且不存在互相矛盾的分支。

## 6.hotfix_5.3 固化 OpenCode 提示词

新增两个独立模板：

- `implementation-prompt.md`：首次实现及同一任务补测试使用。
- `repair-prompt.md`：代码 Review 失败后，新修复 SubAgent 使用。

模板必须包含任务与验收标准、工作目录、约束读取、文件边界、Git 与 Plan 禁止项、多人工作区保护、测试要求、权限上浮协议和结构化回传格式。

**验收**：SubAgent 能用模板驱动 `opencode run -i -m <provider/model>`，并返回 session ID、变更、测试流程、实际结果、风险和权限请求。

## 6.hotfix_5.4 索引与静态校验

更新 `skill/README.md`，只登记工具用途和显式调用示例，不增加关键词自动路由。

执行：

- 官方 `quick_validate.py`。
- 资源路径、关键状态机和显式调用策略检索。
- Git diff 与工作区范围检查。

本 hotfix 不使用该 Skill 执行真实业务 Plan，不修改 `plan_7` 业务代码。

**验收**：静态校验通过，变更仅包含本 hotfix、Skill 目录及项目 Skill 索引。

## 变更记录

- 2026-06-19：完成需求 grilling，确定严格 Plan 门槛、逐任务闭环、模型选择、SubAgent/OpenCode 会话边界、权限上浮、三轮 Review 修复上限、最终验收和中断恢复策略。
- 2026-06-19：创建 hotfix 计划，开始实现。
- 2026-06-19：通过官方 `init_skill.py` 创建 `skill/execute-plan-with-opencode`，加入 `SKILL.md`、`agents/openai.yaml`、实现提示词和 Review 修复提示词。
- 2026-06-19：在 `agents/openai.yaml` 设置 `policy.allow_implicit_invocation: false`，并在 `skill/README.md` 只登记显式调用入口。
- 2026-06-19：完成静态验证：
  - `.venv/bin/python /Users/yuancheng/.codex/skills/.system/skill-creator/scripts/quick_validate.py skill/execute-plan-with-opencode` 通过，输出 `Skill is valid!`。
  - 关键规则检索命中 OpenCode 双重检查、模型选择、九项 Plan 门槛、同会话补测、新会话修复、三轮上限、权限协议、Docker-only 测试和结构化回传。
  - `git diff --check` 通过。
  - 未执行真实业务 Plan，符合本 hotfix 验证边界。
- 2026-06-19：实现提交 hash 回填为 `a9ddc3c`。
