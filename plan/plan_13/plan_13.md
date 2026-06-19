---
workflow_version: 2
plan_id: plan_13
type: main
status: ready
owner: parent-agent
created: 2026-06-19
updated: 2026-06-19
---

# plan_13 — Spring Boot 4 与 Spring AI 2 基线升级

## 背景与目标

`plan_7` 将首次正式接入 Spring AI 与 DeepSeek。继续在 Spring Boot 3.4 基线上选用旧 Spring AI 会立即形成待升级技术债；本项目是 Demo，当前业务规模和模块数量允许在 Query 实现前完成一次独立、可回滚的框架升级。

本计划把全仓库基线升级至 Spring Boot 4.1.0、Spring Framework 7 与 Spring AI 2.0.0，保持 Java 21 目标和现有 Gradle 9.4.1 Wrapper，修复编译、自动配置、测试与 Docker 构建兼容问题，并为 `plan_7` 提供稳定基线。

## 范围

- 统一升级所有模块的 Spring Boot dependency platform 与 Boot plugin。
- 引入 Spring AI 2.0.0 BOM 基线，但不实现 DeepSeek 业务调用。
- 处理 Boot 4 / Framework 7 引起的依赖、包、配置、测试和自动配置变化。
- 校准 H2 轻量组件测试、MVC Slice、JPA、Jackson、Validation、ArchUnit 与启动测试。
- 验证现有 AdminRag、Retrieval、Smoke 和 Docker HTTP 回归未退化。
- 同步构建、测试、包结构、README 和相关 Plan 中的版本事实。

## 非目标

- 不实现 Query Context、Prompt、LLM Adapter 或 UserQuery 业务流程。
- 不改变 PostgreSQL、pgvector、Sidecar 模型、Retrieval 算法或 HTTP 业务契约。
- 不升级 Java 目标版本；继续以 Java 21 编译和运行。
- 不升级已兼容且与 Boot 4 无关的依赖作为顺手治理。
- 不修改数据库 schema 或业务数据。

## 前置依赖

- **执行前置 Plan**：`plan_6.hotfix_6`
- 技术上本计划与 `plan_6.hotfix_6` 可独立，但二者都会修改 Gradle、约束、测试和索引；为避免共享文件冲突，执行队列串行化。
- 当前 Gradle Wrapper 为 9.4.1，满足升级基线，不需要预设 Wrapper 升级任务；若官方插件在实际解析中暴露兼容问题，必须先记录证据再调整。

## 文件边界

- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle/wrapper/**`（仅实际兼容问题需要时）
- `crag-*/build.gradle.kts`
- `crag-*/src/main/**`
- `crag-*/src/test/**`
- `Dockerfile`
- `docker-compose.yml`（仅升级兼容所需）
- `scripts/tests/http/**`（仅修复升级导致的兼容）
- `constraints/code-style.md`
- `constraints/package-structure.md`
- `constraints/test-workflow.md`
- `README.md`
- `plan/plan_13/plan_13.md`
- `plan/plan_7/plan_7.md`
- `plan/index/README.md`

## 关键决策

- 目标版本固定为 Spring Boot 4.1.0、Spring Framework 7、Spring AI 2.0.0。
- Java source/target 保持 21；本机 JDK 版本不是项目编译目标。
- 保持 Gradle Wrapper 9.4.1，除非实际插件解析或构建证据要求调整。
- 版本升级独立于 Query 功能提交，保证可单独回滚。
- 先建立依赖与编译基线，再修复测试，最后执行 Docker HTTP 回归。
- Spring AI 只建立 BOM/依赖解析基线，不在本计划配置 API key、Provider、ChatModel 或业务 Adapter。
- 不以禁用测试、放宽断言、永久排除自动配置或宽泛 ArchUnit 豁免完成迁移。
- Boot 4 模块化 starter 或自动配置变化按官方迁移路径处理，并同步所有模块，禁止局部混用 Boot 3/4 依赖。

## 未决问题

无。实际迁移中发现的 API 变更按编译和测试证据处理，不构成启动前设计未决项。

## 风险与回滚

- Boot 4 的模块化 starter、Framework 7 API 与测试切片变化可能导致全仓库编译或 Context 失败：按模块逐层修复并保留最小提交。
- Spring Data / Hibernate 版本升级可能暴露映射或初始化差异：通过纯单元、轻量组件和真实 PostgreSQL Docker HTTP 回归分层验证。
- Jackson 或 MVC 默认行为可能改变 HTTP JSON：现有契约脚本必须比较状态、字段集合和业务码。
- Docker 构建可能因插件或产物路径变化失败：保留现有多阶段结构，只修复升级必需项。
- 若迁移风险超过 Demo 可接受范围，可逆序撤销任务提交并恢复 Boot 3.4.1；本计划不迁移数据，无数据回滚。

## 测试与验证计划

- 依赖解析与编译：`./gradlew dependencies` 的定向检查、`./gradlew compileJava testClasses`。
- 纯单元测试：`./gradlew test`，覆盖现有业务行为。
- 轻量组件测试：运行全部 `*ComponentTest`，重点覆盖 MVC Slice、H2、配置绑定和主 Context。
- 架构测试：`./gradlew :crag-app:test --tests '*ArchitectureTest'`。
- 全量检查：`./gradlew check`，包含 Spotless、Plan、模块依赖和约束校验。
- Docker HTTP 回归：`docker compose up -d --build` 后执行 AdminRag、默认 Smoke 隔离、显式 Smoke 与 Retrieval 现有脚本。
- 最终执行 `python3 scripts/validate_plans.py --strict --verify-git`、`git diff --check` 并核对任务提交。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 13.1 | 升级 Boot 4 / Spring AI 2 构建基线 | ⏳ 待开始 | — | — |
| 13.2 | 修复生产代码与自动配置兼容 | ⏳ 待开始 | — | — |
| 13.3 | 校准测试基础设施与架构护栏 | ⏳ 待开始 | — | — |
| 13.4 | 完成 Docker HTTP 回归与文档收口 | ⏳ 待开始 | — | — |

整体进度：0 / 4（0%）

## 13.1 升级 Boot 4 / Spring AI 2 构建基线

**目标**：让全部 Gradle 模块在统一 Boot 4.1.0 与 Spring AI 2.0.0 版本基线上完成依赖解析和编译。
**前置任务**：无
**范围**：统一 Boot plugin、dependency platform 与 Spring AI BOM；检查 Boot 4 starter 模块变化；保持 Java 21 和 Gradle 9.4.1；修复构建脚本层面的依赖冲突。
**非目标**：不修改业务行为，不配置 DeepSeek，不提前修复运行时测试问题。
**验收标准**：所有模块只解析 Boot 4.1.0 体系依赖；无 Boot 3.4.1 残留；Spring AI 2.0.0 依赖可解析；主源码和测试源码完成编译。
**验证方式**：运行版本检索、定向 dependencyInsight、`./gradlew compileJava testClasses`、`python3 scripts/validate_module_dependencies.py`。
**涉及文件**：`build.gradle.kts`、`settings.gradle.kts`、`crag-*/build.gradle.kts`、`gradle/wrapper/**`

## 13.2 修复生产代码与自动配置兼容

**目标**：消除 Boot 4 / Framework 7 对生产代码、配置和应用启动造成的兼容问题。
**前置任务**：13.1
**范围**：处理 Spring Web、Validation、Data JPA、Jackson、SQL 初始化及自动配置变化；删除过期的 Spring AI 排除配置；确保默认应用可在受控测试配置下启动。
**非目标**：不重构业务模块，不改变 API 契约，不实现 Query LLM。
**验收标准**：生产代码无过期 API 编译错误；主 Context 可启动；现有模块边界不变；配置不包含为旧 Spring AI 骨架保留的失效排除项。
**验证方式**：运行受影响模块测试、`./gradlew :crag-app:test --tests '*ComponentTest'` 和配置检索；检查启动日志无版本混用。
**涉及文件**：`crag-*/src/main/**`、`crag-app/src/main/resources/**`、`crag-*/build.gradle.kts`

## 13.3 校准测试基础设施与架构护栏

**目标**：让四层测试在 Boot 4 基线上保持原有语义和发现规则。
**前置任务**：13.2
**范围**：修复 MVC Slice、MockMvc、H2、JPA、主 Context、ArchUnit 和测试配置；保持 `*Test`、`*ComponentTest`、`*ArchitectureTest` 分类；补充升级缺陷回归。
**非目标**：不通过禁用测试或宽泛豁免绕过迁移，不把 H2 当作真实数据库证据。
**验收标准**：全部 Gradle 测试被发现并通过；现有 HTTP 字段、错误码和 Profile 行为断言保持；架构规则无新增例外。
**验证方式**：运行模块测试、`./gradlew test`、`./gradlew :crag-app:test --tests '*ArchitectureTest'`、`./gradlew check`。
**涉及文件**：`crag-*/src/test/**`、`crag-app/src/test/resources/**`、`constraints/test-workflow.md`（仅事实变化时）

## 13.4 完成 Docker HTTP 回归与文档收口

**目标**：证明升级后的应用在真实 Compose 环境保持现有业务与诊断契约，并为 Plan 7 提供稳定基线。
**前置任务**：13.3
**范围**：构建 Boot 4 应用镜像；执行默认与 Smoke Docker HTTP 回归、AdminRag 与 Retrieval 链路；同步版本事实、README、包结构索引、Plan 7 前置结果和索引。
**非目标**：不实现 DeepSeek，不修改 plan_10 的完整部署治理目标。
**验收标准**：镜像构建成功；默认正式 API 可用且 Smoke 端点不可见；显式 Smoke 与 Retrieval 回归通过；文档不再引用 Boot 3.4.1；全量检查通过。
**验证方式**：运行 `docker compose up -d --build`、现有 `scripts/tests/http/` 脚本、显式 Smoke 回归、`./gradlew check`、Plan 严格校验和 `git diff --check`。
**涉及文件**：`Dockerfile`、`docker-compose.yml`、`scripts/tests/http/**`、`README.md`、`constraints/**`、`plan/plan_13/plan_13.md`、`plan/plan_7/plan_7.md`、`plan/index/README.md`

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
| 2026-06-19 | 创建计划并设为待开始 | Plan 7 grilling 决定在接入 Spring AI 前独立升级框架基线 | 执行队列为 plan_6.hotfix_6 → plan_13 → plan_7 → plan_10 |
