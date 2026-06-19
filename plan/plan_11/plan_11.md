---
workflow_version: 2
plan_id: plan_11
type: main
status: in_progress
owner: parent-agent
created: 2026-06-19
updated: 2026-06-19
---

# plan_11 — 测试分层与回归工作流治理

## 背景与目标

当前 `constraints/test-workflow.md` 以“单元测试 / 非单元测试”二分测试类型，导致 `@SpringBootTest + H2`、Spring Slice、ArchUnit 等测试处于语义灰区；同时，文档没有明确何时必须执行 Docker HTTP 回归、稳定回归能否只依赖手工 `curl`、测试数据如何隔离，以及跳过和 flaky 测试如何处理。

本计划在 `plan_9` 开始模块迁移前完成测试工作流治理：建立纯单元测试、轻量组件测试、架构测试和 Docker HTTP 回归的清晰边界，统一命名、Gradle 任务语义、风险触发规则、数据隔离、外部 LLM、失败处理与验收证据要求，并让现有测试与活跃 Plan 使用同一套术语。

## 范围

- 重写 `constraints/test-workflow.md`，建立四类测试及其执行边界。
- 明确 `./gradlew test`、`./gradlew check` 与 Docker HTTP 回归的职责。
- 统一 `*Test`、`*ComponentTest`、`*ArchitectureTest` 和 `scripts/tests/http/` 命名约定。
- 明确 H2 能证明与不能证明的行为，禁止用 H2 替代 PostgreSQL、pgvector 和 native SQL 的真实验证。
- 定义按变更风险触发 Docker HTTP 回归的最低门槛。
- 定义稳定业务回归的自动化、测试数据隔离、LLM Stub、真实供应商条件验收、跳过与 flaky 处理规则。
- 对齐 `constraints/code-style.md`、活跃 Plan 和执行 Plan Skill 中重复或冲突的测试术语。
- 将现有 Spring Context 测试迁移到轻量组件测试命名，确认默认 Gradle 任务仍可发现并执行。

## 非目标

- 不执行 `plan_9` 的 `crag-api`、公开 API 包、`crag-smoke` 或 ArchUnit 迁移。
- 不执行 `plan_10` 的应用健康检查、Compose 就绪链或 Docker 部署契约升级。
- 不实现 `plan_7` 的 Query、DeepSeek 或 LLM Stub。
- 不在本计划中建立完整业务 HTTP 回归脚本；稳定链路脚本由首次触及对应链路的 `plan_9`、`plan_7` 或后续业务 Plan 按新约束补齐。
- 不引入 JaCoCo 或全局覆盖率百分比门禁。
- 不使用 Testcontainers，不新增第二套数据库测试环境。

## 前置依赖

- **执行前置 Plan**：`plan_8.hotfix_1`
- `plan_8` 已完成 workflow v2 和计划静态校验。
- `plan_8.hotfix_1` 必须先完成依赖顺序、状态机和约束冲突收敛。
- 本计划必须先于 `plan_9` 执行；后续顺序固定为 `plan_9 → plan_7 → plan_10`。
- `plan_7` 继续等待 `plan_9`，但其测试术语与验收要求在本计划中提前对齐。

## 文件边界

- `constraints/test-workflow.md`
- `constraints/code-style.md`
- `crag-app/src/test/**`
- `build.gradle.kts`
- `skill/execute-plan-with-opencode/SKILL.md`
- `plan/plan_7/plan_7.md`
- `plan/plan_9/plan_9.md`
- `plan/plan_10/plan_10.md`
- `plan/plan_11/plan_11.md`
- `plan/index/README.md`

本计划不得修改 `docker-compose.yml`、Dockerfile、生产 Java 代码或业务测试端点。

## 关键决策

- 测试分为纯单元测试、轻量组件测试、架构测试和 Docker HTTP 回归，不再使用“单元 / 非单元”二分法。
- 纯单元测试不启动 Spring，不访问真实网络、数据库或文件系统，外部依赖使用替身。
- 轻量组件测试允许 Spring Context、Spring Slice、H2 和 Mock HTTP Client，用于验证 Bean 装配、配置绑定、Controller 校验、业务编排和通用映射。
- H2 通过不能证明 PostgreSQL 方言、native SQL、JSONB、pgvector、锁、CAS 或真实事务隔离正确；这些能力必须由 Docker 中的真实依赖和 HTTP 入口验证。
- 架构测试用于验证包、模块和依赖规则，不属于业务流程回归。
- 业务链路最终回归必须从 Docker 暴露的 HTTP 入口进入。`docker compose ps/logs/exec` 和数据库查询只可作为健康或结果辅助证据，不得替代 HTTP 业务入口。
- `./gradlew test` 执行纯单元、轻量组件和架构测试，不启动 Docker；`./gradlew check` 在此基础上执行格式、静态和 Plan 校验，也不隐式启动 Docker。
- 文件命名统一为：`*Test` 表示纯单元测试，`*ComponentTest` 表示轻量组件测试，`*ArchitectureTest` 表示架构测试；Docker HTTP 回归位于 `scripts/tests/http/`。
- Docker HTTP 回归按风险触发：Controller、配置、Spring 装配、持久化、Sidecar Client、业务链路或 Docker 变更至少回归受影响链路；新增链路或跨模块大改必须执行完整相关链路回归。
- 稳定核心链路必须沉淀为自动化 HTTP 回归脚本，以退出码和明确断言表达结果；手工 `curl` 只用于探索，不能作为最终唯一证据。
- HTTP 回归每次生成唯一 `runId`，只清理本次数据；禁止清表、重建数据库、删除共享 volume 或执行 `docker compose down -v`。无法精确清理时，数据必须可识别并在验收记录说明。
- Query 必跑回归使用确定性 LLM Stub；真实 DeepSeek 调用为条件验收。涉及供应商配置、依赖或协议变更时，真实调用是完成门槛，缺少条件不得静默跳过。
- 临时跳过必须关联未完成 Plan 任务并注明移除条件。失败后无修正重跑通过视为疑似 flaky，不能直接作为稳定通过证据。
- 不设置全局覆盖率百分比；使用正常、边界、关键分支、失败路径和缺陷复现的行为覆盖门槛。
- 迁移例外：11.1 修改旧规则前，现有 `SpringBootTest + H2` 可通过宿主机 Gradle 运行，仅用于证明测试发现、命名和 Spring 装配，不作为 PostgreSQL 或业务链路证据；11.1 完成后立即由新规则接管。
- 任务顺序固定为 `11.1 →（11.2 与 11.3 可并行）→ 11.4`，并行任务文件边界独立。

## 未决问题

无。测试分类、命名、执行入口、Docker 触发规则、数据隔离、LLM 验收、flaky 处理和覆盖率策略均已确认。

## 风险与回滚

- 术语调整可能使活跃 Plan、Skill 和代码风格文档继续使用旧的“非单元测试”表述：通过全仓检索活跃文件并逐项对齐，历史已完成 Plan 不追溯改写。
- `*Test` 的严格定义可能暴露现有 Spring 测试命名不准确：本计划只迁移当前 `CragDemoApplicationTests`，其他新增测试必须按新规则命名。
- HTTP 自动化要求先于脚本基础设施全面落地：在约束中明确“首次触及稳定链路时补齐”，并把 `plan_9` 与 `plan_7` 的对应验收标准更新为自动化脚本，避免制造无人负责的存量债务。
- 过度扩大本计划会与 `plan_9`、`plan_10` 共享文件冲突：本计划只修改测试语义、测试命名和活跃计划文本，不修改 Compose、Smoke 实现或健康检查。
- 所有修改可通过撤销本计划提交回滚；不涉及生产代码、数据库或部署状态，无运行时数据回滚。

## 测试与验证计划

- 计划校验：`python3 scripts/validate_plans.py --strict`。
- 测试发现与分类：运行 `./gradlew test`，确认重命名后的轻量组件测试仍被执行。
- 全量工程检查：运行 `./gradlew check`，确认测试、Spotless 和 Plan 校验通过。
- 术语检查：使用 `rg` 检查活跃约束、Plan 和 Skill 中的“单元测试”“非单元测试”“H2”“Docker HTTP 回归”等表述是否与新分类一致。
- 文档质量检查：确认不存在 `TODO`、`TBD`、无责任方例外或把 H2 描述为真实数据库保证的内容。
- 最终执行 `python3 scripts/validate_plans.py --strict --verify-git`、`git diff --check` 并核对任务提交。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 11.1 | 重写测试分层与执行约束 | ✅ 完成 | 0703beb | 2026-06-19 |
| 11.2 | 对齐代码风格、Skill 与活跃 Plan | ✅ 完成 | f365189 | 2026-06-19 |
| 11.3 | 迁移现有轻量组件测试命名 | ✅ 完成 | 064578d | 2026-06-19 |
| 11.4 | 完成全量校验与治理验收 | 🔄 进行中 | — | — |

整体进度：3 / 4（75%）

## 11.1 重写测试分层与执行约束

**目标**：让 `constraints/test-workflow.md` 成为无灰区、可执行的测试工作流唯一入口。
**前置任务**：无
**范围**：按“文档定位、测试分层、命名与目录、Gradle 任务语义、行为覆盖、Docker HTTP 触发规则、HTTP 自动化、数据隔离、LLM 验收、失败与跳过、Plan 证据、Benchmark 路由、维护同步”重写文档；明确每层能证明和不能证明的行为。
**非目标**：不新增测试脚本，不修改 Gradle 配置，不迁移生产代码。
**验收标准**：四类测试边界互斥且完整；H2 限制、HTTP 最终保证、风险触发、runId、LLM Stub、flaky 和行为覆盖规则均有明确措辞；不存在“所有非单元测试一律 Docker”这类会误伤组件与架构测试的旧二分规则。
**验证方式**：逐项对照本计划关键决策；运行 `rg -n 'TODO|TBD|待定|非单元测试' constraints/test-workflow.md`，确认无占位符且旧术语只在必要解释中出现；运行 `git diff --check`。
**涉及文件**：`constraints/test-workflow.md`

## 11.2 对齐代码风格、Skill 与活跃 Plan

**目标**：消除新测试分层与其他活跃规则、执行工具和计划验收之间的冲突。
**前置任务**：11.1
**范围**：更新 `code-style.md` 的测试代码定义；让执行 Plan Skill 路由到四层测试与风险触发规则，不复制完整约束；更新 `plan_7`、`plan_9`、`plan_10` 的测试名称、命令和自动化 HTTP 验收责任；保持历史已完成 Plan 原文不变。
**非目标**：不改变三个活跃 Plan 的业务目标、模块边界或任务数量；不修改 Skill 的权限、Git 或 SubAgent 工作流。
**验收标准**：`code-style.md` 不再把轻量组件测试误称为单元测试；Skill 不再要求所有 Spring Boot 检查都走 Docker；`plan_9` 明确 Architecture/Component 测试命名并负责首次稳定 Smoke HTTP 脚本；`plan_7` 明确 Query 的 Stub 与真实 DeepSeek 条件验收；`plan_10` 只负责部署健康和 Docker 契约，不重复定义测试分类。
**验证方式**：运行 `rg -n -C 2 '非单元测试|单元测试|ComponentTest|ArchitectureTest|HTTP 回归|手工.*curl|DeepSeek' constraints/code-style.md skill/execute-plan-with-opencode/SKILL.md plan/plan_7/plan_7.md plan/plan_9/plan_9.md plan/plan_10/plan_10.md` 并逐项核对；运行 `python3 scripts/validate_plans.py --strict`。
**涉及文件**：`constraints/code-style.md`、`skill/execute-plan-with-opencode/SKILL.md`、`plan/plan_7/plan_7.md`、`plan/plan_9/plan_9.md`、`plan/plan_10/plan_10.md`

## 11.3 迁移现有轻量组件测试命名

**目标**：让当前 Spring Context + H2 测试的名称与新分类一致，并证明默认 Gradle 测试发现规则兼容命名约定。
**前置任务**：11.1
**范围**：将 `CragDemoApplicationTests` 重命名为 `CragDemoApplicationComponentTest`，同步类名和说明；检查其 H2 配置只被描述为轻量组件测试，不声称覆盖真实 PostgreSQL 行为；仅在现有 Gradle 发现规则无法执行时做最小配置修正。
**非目标**：不新增 Spring 场景、不替换 H2、不连接真实数据库、不修改生产配置。
**验收标准**：测试文件与类名使用 `*ComponentTest`；`./gradlew :crag-app:test --tests '*ComponentTest'` 能发现并通过该测试；`./gradlew test` 仍包含该测试。
**验证方式**：运行 `./gradlew :crag-app:test --tests '*ComponentTest'` 和 `./gradlew test`，检查测试报告中存在 `CragDemoApplicationComponentTest`。
**涉及文件**：`crag-app/src/test/java/ai/cerbur/crag/app/CragDemoApplicationTests.java`（迁移后删除）、`crag-app/src/test/java/ai/cerbur/crag/app/CragDemoApplicationComponentTest.java`（新增）、`crag-app/src/test/resources/application.yml`、`build.gradle.kts`（仅按需）

## 11.4 完成全量校验与治理验收

**目标**：证明测试约束、现有测试、活跃 Plan 和执行入口共同表达同一套规则，并为 `plan_9` 解除前置阻塞。
**前置任务**：11.1、11.2、11.3
**范围**：执行全量测试与静态检查；检查命名、术语、Plan 状态和文件边界；记录验收证据并回填提交；完成后将 `plan_9` 从阻塞恢复为待开始。
**非目标**：不开始执行 `plan_9`，不运行尚未建立的业务 HTTP 回归脚本，不修改 Docker 环境。
**验收标准**：`./gradlew test` 与 `./gradlew check` 通过；Plan 严格校验通过；活跃文档无测试分类冲突；所有任务提交已回填；`plan_9` 恢复为 `ready`，索引同步。
**验证方式**：运行 `./gradlew test`、`./gradlew check`、`python3 scripts/validate_plans.py --strict --verify-git`、`git diff --check`；使用 `git show --stat <hash>` 核对每项任务提交范围。
**涉及文件**：`plan/plan_11/plan_11.md`、`plan/plan_9/plan_9.md`、`plan/index/README.md`

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-19 | macOS 宿主机 | `rg -n 'TODO|TBD|待定|非单元测试' constraints/test-workflow.md`；`git diff --check`；`python3 scripts/validate_plans.py --strict plan/plan_11/plan_11.md` | 通过 | 四层测试、H2 边界、Docker HTTP 触发、runId、LLM、flaky 与行为覆盖规则已落地；无旧二分术语或占位符 |
| 2026-06-19 | macOS 宿主机 + OpenCode `deepseek/deepseek-v4-pro` | 聚焦 `rg`；`python3 scripts/validate_plans.py --strict plan/plan_7/plan_7.md plan/plan_9/plan_9.md plan/plan_10/plan_10.md plan/plan_11/plan_11.md`；`git diff --check` | 通过 | 代码风格、执行 Skill、提示模板与活跃 Plan 已对齐；OpenCode 会话 `ses_1220a91ebffeRxszvLRcWW83hg`，修复会话 `ses_122058a40ffe4KAA3S1a3kJPqS` |

## 阻塞记录

无。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-19 | 创建 plan_11 并设为待开始 | 测试约束 grilling 完成，现有二分法与 H2、Spring Context、ArchUnit 和 HTTP 回归语义冲突 | 建立 4 项治理任务；执行顺序调整为 plan_11 → plan_9 → plan_10 |
| 2026-06-19 | 增加 workflow Hotfix 前置与迁移例外 | 先修复状态机、依赖队列和旧测试规则迁移悖论 | plan_8.hotfix_1 完成后执行；11.2 与 11.3 可并行 |
| 2026-06-19 | 开始执行 11.1 | Plan gate 与 OpenCode 模型选择完成 | Plan 进入进行中；先重写测试工作流约束 |
| 2026-06-19 | 完成 11.1 | 四层测试约束与验收规则验证通过，实现提交 `0703beb` 已核对 | 整体进度更新为 25%；开始 11.2 |
| 2026-06-19 | 完成 11.2 | 约束由 ParentAgent 修改，Skill 由 OpenCode 实现并完成一轮独立修复；提交 `f365189` 已核对 | 整体进度更新为 50%；开始 11.3 |
