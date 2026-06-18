---
workflow_version: 2
plan_id: plan_8.hotfix_1
type: hotfix
parent_plan: plan_8
status: in_progress
owner: parent-agent
created: 2026-06-19
updated: 2026-06-19
---

# plan_8.hotfix_1 — Plan 依赖顺序与约束冲突收敛

## 背景与目标

对 `plan_7`、`plan_9`、`plan_10`、`plan_11` 及项目约束进行交叉审计后，发现状态机无法表达前置阻塞恢复、索引缺少唯一执行队列、校验器不检查依赖环与队列顺序，以及 API 模块、Storage Entity、Smoke、Query 回归和 Docker 收口职责存在冲突或重叠。

本 Hotfix 将已完成 grilling 的决策同步到工作流约束、活跃 Plan、项目方向与专项约束，并增强静态校验器，固定当前执行顺序：

```text
plan_8.hotfix_1 → plan_11 → plan_9 → plan_7 → plan_10
```

## 范围

- 扩展 Plan 阻塞与恢复状态机，明确不同恢复目标。
- 收敛 API、Storage Entity、Smoke、Query HTTP 回归和 Docker 契约职责。
- 重排并校准活跃 Plan 的依赖、任务、文件边界和恢复动作。
- 在索引维护唯一当前执行队列。
- 增强 Plan 校验器的阻塞记录、依赖环、执行队列及索引一致性检查。

## 非目标

- 不执行 `plan_11`、`plan_9`、`plan_7` 或 `plan_10` 的实现任务。
- 不修改生产 Java、Docker Compose、Dockerfile 或数据库结构。
- 不追溯改写已完成历史 Plan。

## 前置依赖

- **执行前置 Plan**：`plan_8`
- `plan_8` 已完成 workflow v2。
- `plan_7`、`plan_9`、`plan_10`、`plan_11` 均未开始实现，可安全调整执行边界。

## 文件边界

- `constraints/**`
- `plan/plan_main.md`
- `plan/index/README.md`
- `plan/plan_7/plan_7.md`
- `plan/plan_8/plan_8.hotfix_1.md`
- `plan/plan_9/plan_9.md`
- `plan/plan_10/plan_10.md`
- `plan/plan_11/plan_11.md`
- `plan/templates/**`
- `scripts/validate_plans.py`
- `scripts/tests/test_validate_plans.py`

## 关联范围与规模说明

- 关联 `plan_7`、`plan_8`、`plan_9`、`plan_10`、`plan_11`，但只修复 workflow v2 对依赖顺序和冲突表达不足的问题。
- 任务数为 4，不修改业务模块；继续归属引入现有校验器与状态机的 `plan_8`，无需升级为新的主 Plan。

## 关键决策

- 状态机允许 `ready ↔ blocked`、`blocked → in_progress` 和 `blocked → draft`；恢复目标取决于内容是否仍完整及任务是否已经开始。
- 当前唯一执行队列为 `plan_8.hotfix_1 → plan_11 → plan_9 → plan_7 → plan_10`。
- `plan_9` 负责最小可用 Smoke 隔离、显式启用和自动化诊断 HTTP 回归；`plan_10` 不重复设计 Smoke，只收口部署契约。
- `plan_7` 只通过正式 Query API 做自动化 HTTP 回归，不新增 Query Smoke Controller；必跑使用确定性 LLM Stub，真实 DeepSeek 调用是完成门槛。
- `plan_10` 同时依赖 `plan_9` 与 `plan_7`。
- Storage Entity 迁移例外只允许已有调用白名单，禁止新增 Entity 泄漏。

## 未决问题

无。

## 风险与回滚

- 依赖解析采用受限 Markdown 约定，若误判历史文字，只扫描 workflow v2 活跃 Plan 的“前置依赖”章节和索引执行队列。
- 活跃 Plan 调整可能遗漏交叉引用，通过全仓检索、严格校验与单元测试发现。
- 所有变更均为文档与开发工具，可通过逆序撤销本 Hotfix 提交回滚，无运行时数据影响。

## 测试与验证计划

- 先为阻塞记录、依赖环、执行队列和索引一致性补充失败单元测试。
- 运行 `python3 -m unittest scripts.tests.test_validate_plans -v`。
- 运行 `python3 scripts/validate_plans.py --strict` 和 `./gradlew check`。
- 运行约束与活跃 Plan 冲突关键词检索、`git diff --check` 和提交范围核对。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 8.hotfix_1.1 | 收敛工作流状态机、专项约束与项目方向 | ⏳ 待验收 | pending | — |
| 8.hotfix_1.2 | 校准活跃 Plan、任务顺序与索引执行队列 | ⏳ 待验收 | pending | — |
| 8.hotfix_1.3 | 增强 Plan 静态校验器及单元测试 | ⏳ 待验收 | pending | — |
| 8.hotfix_1.4 | 完成全量校验与 Hotfix 验收 | 🔄 进行中 | — | — |

整体进度：0 / 4（0%）

## 8.hotfix_1.1 收敛工作流状态机、专项约束与项目方向

**目标**：让工作流、API、持久化、模块边界和项目方向对同一事实给出一致规则。  
**前置任务**：无  
**范围**：扩展阻塞恢复状态机；明确 `crag-api` 目标与 `crag-admin` 当前偏差；限定 Storage Entity 迁移例外；修正 `plan_main` 的模块职责和阶段路线表达。  
**非目标**：不实现目标模块结构，不消除已有 Entity 跨模块调用。  
**验收标准**：专项约束不存在互相否定的硬规则；状态机覆盖已确认恢复路径；`plan_main` 不再声称每个领域都拥有 Controller、DAO。  
**验证方式**：检索状态转换、`crag-admin`、Entity 跨模块和 Controller/Repository 职责并逐项核对；运行严格 Plan 校验。  
**涉及文件**：`constraints/plan-workflow.md`、`constraints/api-style.md`、`constraints/persistence-style.md`、`constraints/package-structure.md`、`plan/plan_main.md`

## 8.hotfix_1.2 校准活跃 Plan、任务顺序与索引执行队列

**目标**：让四个活跃主 Plan 的依赖、职责、任务和恢复动作共同表达唯一执行顺序。  
**前置任务**：8.hotfix_1.1  
**范围**：更新 `plan_7`、`plan_9`、`plan_10`、`plan_11`；将 Query 重拆为 5 项；在索引增加当前执行队列和 Hotfix 登记。  
**非目标**：不执行任何活跃主 Plan 的代码或部署变更。  
**验收标准**：执行顺序为 `plan_11 → plan_9 → plan_7 → plan_10`；仅 `plan_11` 的 11.2 与 11.3 可并行；共享文件职责没有未解释重叠。  
**验证方式**：逐个核对前置依赖、任务“前置任务”、文件边界、阻塞恢复动作和索引队列。  
**涉及文件**：`plan/plan_7/plan_7.md`、`plan/plan_9/plan_9.md`、`plan/plan_10/plan_10.md`、`plan/plan_11/plan_11.md`、`plan/index/README.md`

## 8.hotfix_1.3 增强 Plan 静态校验器及单元测试

**目标**：自动发现阻塞记录缺失、显式依赖环、执行队列逆序和索引状态漂移。  
**前置任务**：8.hotfix_1.1、8.hotfix_1.2  
**范围**：先补失败测试，再扩展标准库校验器和必要模板约定。  
**非目标**：不引入 Markdown/YAML 第三方解析库，不从任意自然语言推断依赖。  
**验收标准**：新增测试经过 red-green；合法仓库通过，依赖环、队列缺失或逆序、blocked 字段缺失会失败。  
**验证方式**：运行 `python3 -m unittest scripts.tests.test_validate_plans -v` 和仓库严格校验。  
**涉及文件**：`scripts/validate_plans.py`、`scripts/tests/test_validate_plans.py`、`plan/templates/main-plan-template.md`、`plan/templates/hotfix-template.md`

## 8.hotfix_1.4 完成全量校验与 Hotfix 验收

**目标**：证明约束、活跃 Plan、索引和校验器无已知冲突，并为 `plan_11` 进入执行清除前置条件。  
**前置任务**：8.hotfix_1.1、8.hotfix_1.2、8.hotfix_1.3  
**范围**：执行全量验证，记录证据，回填提交并完成 Hotfix。  
**非目标**：不开始 `plan_11`。  
**验收标准**：单元测试、`./gradlew check`、严格 Plan 校验和 diff 检查通过；所有任务提交回填；索引将下一个计划显示为 `plan_11`。  
**验证方式**：运行本计划测试与验证计划中的全部命令，并使用 `git show --stat` 核对提交。  
**涉及文件**：`plan/plan_8/plan_8.hotfix_1.md`、`plan/index/README.md`

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-19 | macOS / Python 3 | `python3 -m unittest scripts.tests.test_validate_plans -v` | 通过 | 12 个校验器测试通过；新增测试已先确认红灯再完成 green |
| 2026-06-19 | macOS / Git repository | `python3 scripts/validate_plans.py --strict` | 通过 | 0 error；24 个 warning 均为历史 Plan 兼容提示 |
| 2026-06-19 | sandbox / Gradle | `./gradlew check` | 未执行 | 沙箱禁止 Gradle 文件锁通信；沙箱外执行因当前工具额度限制被拒绝，未将其记为通过 |
| 2026-06-19 | Git working tree | `git diff --check` 与冲突关键词检索 | 通过 | 无 whitespace error；活动约束和 Plan 未发现新的职责或顺序冲突 |

## 阻塞记录

无。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-19 | 创建 Hotfix 并设为待开始 | Plan 与约束交叉审计确认多处状态、依赖和职责冲突 | 建立 4 项治理任务；先完成本 Hotfix 再执行 plan_11 |
| 2026-06-19 | 开始执行 8.hotfix_1.1 至 8.hotfix_1.3 | Plan 与索引基线已提交 | 状态转为进行中，开始规则、活跃 Plan 与校验器修改 |
