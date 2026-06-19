---
workflow_version: 3
plan_id: plan_3.hotfix_6
type: hotfix
parent_plan: plan_3
status: completed
created: 2026-06-19
updated: 2026-06-19
---

# plan_3.hotfix_6 — Java 工程规范分层与自动格式化

## 背景与目标

现有 `constraints/code-style.md` 同时承载格式、API、持久化和 Retrieval 专项约束，且混用“优先”“重要”“复杂”等难以稳定审查的词语。本 hotfix 将已完成 grilling 的决策落为分层、可执行的工程规范，并使用 Spotless + google-java-format 自动执行机械格式规则。

## 范围

- 重写通用 Java 代码风格约束，建立“必须 / 推荐 / 说明”三级语义。
- 新增 API、持久化与 Retrieval 专项约束文档。
- 更新 `AGENTS.md`、`CLAUDE.md` 和计划索引中的约束路由。
- 在 Gradle 根构建中接入 Spotless，并使用 google-java-format 格式化 Java 源码。
- 首次格式化全仓 Java 文件，验证格式、计划和单元测试。

## 非目标

- 不在本 hotfix 中批量迁移字段注入为构造器注入。
- 不批量删除历史模板 Javadoc 或 `@since`。
- 不重构现有异常体系、DTO 包结构、DAO 接口或业务实现。
- 不引入 Checkstyle、PMD、Error Prone 或 ArchUnit。

## 前置依赖

- `plan_3` 已建立项目文档与协作约束入口。
- `plan_8` 已建立 workflow v2 计划校验器。
- 项目使用 Gradle Kotlin DSL 与 Java 21。

## 文件边界

- `constraints/code-style.md`
- `constraints/api-style.md`
- `constraints/persistence-style.md`
- `constraints/retrieval-style.md`
- `AGENTS.md`
- `CLAUDE.md`
- `build.gradle.kts`
- `**/src/**/*.java`
- `plan/plan_3/plan_3.hotfix_6.md`
- `plan/index/README.md`

## 关键决策

- 规范分为“必须 / 推荐 / 说明”；只有“必须 / 禁止”直接阻塞合并。
- 新增生产代码使用构造器注入，存量代码渐进迁移。
- google-java-format 决定 Java 机械格式与 import 排列，文档不重复维护。
- Repository 只能由同模块 DAO 调用，Service 不得直接访问 Repository。
- HTTP 状态码表达协议结果，`Response.code` 表达稳定业务错误码。
- 进程内并发使用 Spring 管理且显式配置的执行器，禁止裸线程和公共线程池。
- 本轮文档与工具链落地，不借机扩大为存量架构重构。

## 未决问题

无。

## 风险与回滚

- 首次格式化会产生较大机械 diff：限制为 Java 文件，业务语义不变，并通过全量单元测试验证。
- Spotless 插件解析依赖网络：失败时保留文档变更，记录构建阻塞，不绕过格式校验宣称完成。
- 新规则与存量代码存在差异：采用渐进治理，不将历史违规视为本 hotfix 必须修复的范围。
- 回滚时可独立移除 Spotless 配置与格式提交，不影响业务数据和运行时配置。

## 测试与验证计划

- `python3 scripts/validate_plans.py --strict`
- `./gradlew spotlessApply`
- `./gradlew spotlessCheck`
- `./gradlew test`
- `./gradlew check`
- `git diff --check`

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 3.hotfix_6.1 | 拆分并重写工程规范文档 | ✅ 完成 | e3ca3b9 | 2026-06-19 |
| 3.hotfix_6.2 | 更新项目约束路由 | ✅ 完成 | e3ca3b9 | 2026-06-19 |
| 3.hotfix_6.3 | 接入 Spotless 并首次格式化 Java | ✅ 完成 | e3ca3b9 | 2026-06-19 |
| 3.hotfix_6.4 | 完成计划、格式与测试验收 | ✅ 完成 | e3ca3b9 | 2026-06-19 |

整体进度：4 / 4（100%）

## 3.hotfix_6.1 拆分并重写工程规范文档

**目标**：将通用 Java、API、持久化和 Retrieval 约束拆成职责单一的文档。

**前置任务**：无

**范围**：落地 grilling 已确认的分级语义、依赖注入、注释、异常、日志、事务、并发、测试与专项边界规则。

**非目标**：不修改生产代码以消除所有存量违规。

**验收标准**：四份文档职责清晰；已确认决策均有明确规则；不再使用无法判断的行数、固定句数等标准。

**验证方式**：逐项对照 grilling 决策，并检索新文档中的路由、规则等级和关键禁止项。

**涉及文件**：`constraints/code-style.md`、`constraints/api-style.md`、`constraints/persistence-style.md`、`constraints/retrieval-style.md`

## 3.hotfix_6.2 更新项目约束路由

**目标**：让协作入口准确路由到拆分后的规范文档。

**前置任务**：3.hotfix_6.1

**范围**：更新 `AGENTS.md`、`CLAUDE.md` 和计划索引。

**非目标**：不在入口文件重复展开规范正文。

**验收标准**：Java、API、持久化和 Retrieval 修改意图均能从入口文件定位到唯一维护文档；索引登记本 hotfix。

**验证方式**：检索入口文件中的四类路由，并运行严格计划校验。

**涉及文件**：`AGENTS.md`、`CLAUDE.md`、`plan/index/README.md`

## 3.hotfix_6.3 接入 Spotless 并首次格式化 Java

**目标**：使用 google-java-format 自动执行 Java 机械格式与 import 规则。

**前置任务**：3.hotfix_6.1

**范围**：在根 Gradle 构建应用 Spotless 8.7.0，为所有 Java 子项目配置 google-java-format、移除未使用 import，并将格式检查接入根 `check`；执行首次格式化。

**非目标**：不格式化 Markdown、Gradle Kotlin DSL、Python 或 YAML；不引入其他静态分析器。

**验收标准**：`spotlessApply` 可执行；`spotlessCheck` 通过；根 `check` 依赖格式检查。

**验证方式**：运行 `./gradlew spotlessApply`、`./gradlew spotlessCheck` 和 `./gradlew check`。

**涉及文件**：`build.gradle.kts`、`**/src/**/*.java`

## 3.hotfix_6.4 完成计划、格式与测试验收

**目标**：确认文档、格式工具和现有 Java 行为共同通过验证。

**前置任务**：3.hotfix_6.1、3.hotfix_6.2、3.hotfix_6.3

**范围**：运行严格计划校验、全量单元测试、根检查和 diff 检查，回填实际证据。

**非目标**：不执行需要 Docker 的集成或端到端测试，因为本 hotfix 不改变运行时行为。

**验收标准**：计划校验、Spotless、全量单元测试、根检查和 diff 检查均通过。

**验证方式**：执行测试与验证计划中的全部命令，并将结果写入验收记录。

**涉及文件**：`plan/plan_3/plan_3.hotfix_6.md`、`plan/index/README.md`

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-19 | macOS / Java 21 / Gradle 9.4.1 | `./gradlew spotlessApply` | 通过 | 7 个模块共 14 个 Spotless apply 任务成功 |
| 2026-06-19 | macOS / Java 21 / Gradle 9.4.1 | `./gradlew check` | 通过 | 41 个任务成功；覆盖严格 Plan 校验、Spotless、编译与模块单元测试 |
| 2026-06-19 | ParentAgent 独立验收 | 逐项核对 24 项 grilling 决策并重新执行 `./gradlew check` | 通过 | 规范内容、文档职责、路由、Gradle 接入与存量迁移边界符合已确认决策；无阻塞问题 |
| 2026-06-19 | Git | 实现提交 `e3ca3b9` | 通过 | 69 个文件纳入实现提交，供任务状态与验收证据回填 |

## 阻塞记录

无。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-19 | 创建 `plan_3.hotfix_6` 并开始执行 | grilling 已完成 24 项工程规范决策 | 建立规范拆分、路由、格式化和验收边界 |
| 2026-06-19 | 完成规范拆分、路由更新、Spotless 接入与首次格式化 | 按已确认范围执行 | 等待提交后回填 commit hash 并转为完成状态 |
| 2026-06-19 | ParentAgent 独立验收通过 | 24 项决策均已覆盖，完整 Gradle check 再次通过 | 因尚无实现提交，任务按工作流继续保持待验收 |
| 2026-06-19 | 回填实现提交并完成 hotfix | 实现提交 `e3ca3b9` 已创建且验收通过 | 四项任务与 Plan 状态转为完成 |
