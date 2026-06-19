---
workflow_version: 2
plan_id: plan_9
type: main
status: completed
owner: parent-agent
created: 2026-06-19
updated: 2026-06-19
---

# plan_9 — Java 模块边界收紧与 Smoke 诊断隔离

## 背景与目标

当前 multi-module 已完成物理拆分，但模块名、跨模块公开入口和诊断代码仍存在边界泄漏：`crag-admin` 同时承载 AdminRag 与 UserQuery，`crag-app` 中的 `TestController` 直接调用 DAO 和 Retrieval 内部阶段，Ingestion 直接依赖 Retrieval 的普通实现包，现有规则也缺少自动化验证。

本计划落实 `constraints/package-structure.md` 的目标约束：把正式 HTTP 层收敛为 `crag-api`，建立 `api` 公开包，隔离仅在 `smoke` Profile 启用的 `crag-smoke`，并通过 ArchUnit 将模块依赖和包访问规则变为可执行护栏。

## 范围

- 建立 ArchUnit 架构测试基线和迁移期例外。
- 将 `crag-admin` module 与 base package 重命名为 `crag-api` / `ai.cerbur.crag.api`。
- 将 Ingestion、Retrieval、Query 的跨模块入口迁入各自 `api` 包。
- 将 Embedding 公共契约与 Sidecar 实现分离。
- 新建 `crag-smoke`，迁移现有 `TestController` 并增加显式 smoke Profile 装配。
- 清除临时架构例外，同步约束、Docker、README、benchmark 引用和计划记录。

## 非目标

- 不实现 `plan_7` 的 Context、Prompt、DeepSeek 或 UserQuery 业务功能。
- 不改变 AdminRag、Retrieval、RRF、Rerank 的业务算法。
- 不拆分独立微服务，不实现 RPC，也不提前创建 Embedding SDK module。
- 不全面消除 Storage Entity 的跨模块使用，不为所有 Entity 增加映射层。
- 不改变正式 AdminRag 与 UserQuery API 路径。

## 前置依赖

- **执行前置 Plan**：`plan_11`
- `plan_5` 已完成 Gradle multi-module 拆分。
- `plan_6` 已完成 Retrieval 查询链路。
- `plan_8` 已启用 workflow v2 和计划静态校验。
- `plan_11` 必须先完成测试分层与回归工作流治理；本计划随后按新的 Component、Architecture 和 Docker HTTP 回归规则执行。
- `plan_7` 尚未开始，并在本计划完成前保持阻塞。

## 文件边界

- `settings.gradle.kts`
- `build.gradle.kts`
- `crag-admin/**`（迁移后删除）
- `crag-api/**`（新模块）
- `crag-app/**`
- `crag-common/**`
- `crag-storage/**`
- `crag-ingestion/**`
- `crag-retrieval/**`
- `crag-query/**`
- `crag-smoke/**`（新模块）
- `docker-compose.yml`
- `Dockerfile`
- `README.md`
- `benchmark/**`
- `skill/crag-benchmark/**`
- `scripts/validate_module_dependencies.py`
- `scripts/tests/test_validate_module_dependencies.py`
- `constraints/package-structure.md`
- `constraints/api-style.md`
- `plan/plan_main.md`
- `plan/plan_7/plan_7.md`
- `plan/plan_9/plan_9.md`
- `plan/plan_archive/2026-06-19-java-module-boundary-hardening.md`
- `plan/index/README.md`

历史已完成 Plan 只保留原始路径和当时事实，不因本次重命名批量改写。

## 关键决策

- `crag-api` 是正式 HTTP 协议适配层；只允许 Controller、请求 DTO、校验与异常转换。
- `crag-app` 是唯一组合根和启动 jar；Gradle 装配依赖不授予 Java 业务调用权限。
- 普通业务模块只能跨模块引用被依赖模块的 `api` 包。
- `api` 包中的单实现 Service 保持具体类，不机械增加 `Impl`。
- `EmbeddingClient` 属于 Retrieval 对外能力，迁入 `retrieval.api.embedding`；Sidecar HTTP 实现留在 Retrieval 内部。
- `crag-smoke` 是唯一受控跨层诊断例外，不生成启动 jar，不被业务模块依赖。
- smoke Bean 必须受 `@Profile("smoke")` 限制；默认环境不得暴露 `/api/v1/test/**`。
- Storage 暂时允许通过 DAO 和必要 Entity/Result 跨模块访问，但 Repository 永远只允许 Storage 内部访问。
- ArchUnit 在迁移初期记录精确临时例外，最终任务必须删除已解决例外。

## 未决问题

无。模块命名、公开边界、Smoke Profile、迁移顺序和 `plan_7` 前置关系均已确认。

## 风险与回滚

- 大范围包迁移可能造成 Spring 扫描、测试或 Gradle 依赖遗漏：每个阶段先更新架构测试，再执行对应模块测试和全量编译。
- `runtimeOnly` 装配可能影响 smoke Bean 的测试可见性：通过 Docker Compose 显式 smoke 验证确认最终 jar 包含诊断模块。
- 默认关闭 smoke 后既有 README 或 benchmark 流程可能失效：同步所有活跃文档和脚本引用，并分别验证默认与 smoke 启动模式。
- ArchUnit 规则过宽会失去约束价值，过窄会阻塞合理实现：迁移期例外必须精确到包或类，并在 9.6 清零已完成项。
- 本计划不涉及数据库迁移。每项任务使用独立提交；失败时按逆序撤销模块重命名、API 包迁移、smoke 装配和架构规则。正式 HTTP 路径保持不变，因此无需数据或客户端协议回滚。

## 测试与验证计划

- 计划校验：`python3 scripts/validate_plans.py --strict`。
- Gradle 依赖白名单：`python3 scripts/validate_module_dependencies.py`。
- 架构测试：`./gradlew :crag-app:test --tests '*ArchitectureTest'`。
- 轻量组件测试统一使用 `*ComponentTest`，覆盖 Spring Context、Profile 与装配行为，不把 H2 结果当作真实 PostgreSQL 保证。
- 模块测试：按迁移阶段运行受影响模块的纯单元、轻量组件和 Architecture 测试，最终运行 `./gradlew test`。
- 全量工程检查：`./gradlew check`，确认 Spotless、三类 Gradle 测试、架构规则和 Plan 校验全部通过。
- 默认 Docker 验证：`docker compose up -d --build` 后确认正式 API 可用且 `/api/v1/test/**` 不存在。
- Smoke Docker 验证：显式设置 `SPRING_PROFILES_ACTIVE=smoke` 启动 Compose，回归现有 smoke、chunk、indexes、rrf、rerank 和 retrieval 诊断端点。
- Smoke HTTP 回归必须沉淀为 `scripts/tests/http/` 自动化脚本；本计划只建立最小可用的显式启用机制，不治理健康检查、镜像、安全或持久化契约。
- 最终完成前执行 `python3 scripts/validate_plans.py --strict --verify-git`、`git diff --check` 并核对任务提交。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 9.1 | 建立 ArchUnit 模块边界基线 | ✅ 完成 | 95ca45e | 2026-06-19 |
| 9.2 | 将 crag-admin 重命名为 crag-api | ✅ 完成 | 2a257a2 | 2026-06-19 |
| 9.3 | 迁移跨模块公开 API 包 | ✅ 完成 | 0bac69b | 2026-06-19 |
| 9.4 | 分离 Embedding 公共契约与内部实现 | ✅ 完成 | b8bfab5 | 2026-06-19 |
| 9.5 | 新建 crag-smoke 并迁移诊断端点 | ✅ 完成 | 4796117 | 2026-06-19 |
| 9.6 | 收紧架构规则并完成全量验收 | ✅ 完成 | b11ae44 | 2026-06-19 |

整体进度：6 / 6（100%）

## 9.1 建立 ArchUnit 模块边界基线

**目标**：在任何包或模块迁移前建立可重复执行的架构测试，准确暴露当前偏差并锁定不得新增的越界访问。  
**前置任务**：无  
**范围**：为 `crag-app` 测试集引入 ArchUnit，建立代码依赖无环、Repository 内聚、Controller 位置、App 禁止业务调用、普通模块仅访问公开包等规则；新增标准库实现的 Gradle project dependency 白名单校验器及单元测试；对现有 `TestController`、旧公开入口和旧模块名使用精确临时例外。  
**非目标**：本任务不移动生产代码，不一次性修复现有偏差，不使用宽泛的全包忽略。  
**验收标准**：架构测试在记录当前精确例外后通过；新增同类越界类会使测试失败；Gradle 校验器能拒绝未列入白名单的 project dependency 和依赖环；每个临时例外都注明由 9.2 至 9.5 的哪项任务删除。  
**验证方式**：先编写无例外规则并运行 `./gradlew :crag-app:test --tests '*ArchitectureTest'` 确认命中当前偏差，再加入精确例外并确认测试通过；运行 `python3 -m unittest scripts.tests.test_validate_module_dependencies -v`、`python3 scripts/validate_module_dependencies.py` 和 `./gradlew :crag-app:test`。
**涉及文件**：`crag-app/build.gradle.kts`、`crag-app/src/test/**`、`scripts/validate_module_dependencies.py`、`scripts/tests/test_validate_module_dependencies.py`、`build.gradle.kts`

## 9.2 将 crag-admin 重命名为 crag-api

**目标**：让正式 HTTP module 的名称和 package 准确表达其同时承载 AdminRag 与 UserQuery 协议适配的职责。  
**前置任务**：9.1  
**范围**：将 Gradle module `crag-admin` 重命名为 `crag-api`；迁移 Java package 到 `ai.cerbur.crag.api`；按 AdminRag、UserQuery 和 advice 职责整理 Controller 与 DTO；更新活跃构建、装配、API 约束和文档引用。  
**非目标**：不改变 URL、请求响应结构和业务行为；不改写历史已完成 Plan 中的旧路径记录。  
**验收标准**：`settings.gradle.kts` 不再包含 `crag-admin`；源码和活跃配置不再引用 `ai.cerbur.crag.admin`；正式 Controller 仅位于 `crag-api`；现有 API 测试全部通过。  
**验证方式**：运行 `rg -n 'crag-admin|ai\.cerbur\.crag\.admin' settings.gradle.kts crag-* constraints README.md benchmark skill` 检查只剩明确历史说明；运行 `./gradlew :crag-api:test :crag-app:test`。  
**涉及文件**：`settings.gradle.kts`、`crag-admin/**`、`crag-api/**`、`crag-app/build.gradle.kts`、`constraints/api-style.md`、`README.md`

## 9.3 迁移跨模块公开 API 包

**目标**：让 Ingestion、Retrieval 和 Query 的跨模块调用入口能够从目录结构直接识别，并阻止普通模块引用内部实现。  
**前置任务**：9.2  
**范围**：迁移 `AdminRagService` / `AdminRagResult`、`RetrievalService` / 最终检索结果、`UserQueryService` / Query 对外结果到各模块 `api` 包；更新调用方 import、测试和 ArchUnit 临时例外；保持内部阶段结果在 Retrieval 内部。  
**非目标**：不把所有类迁入 `api`；不为单实现 Service 创建接口；不全面收口 Storage API。  
**验收标准**：`crag-api` 只通过 Ingestion 与 Query 的 `api` 包调用业务；`crag-query` 只通过 Retrieval `api` 调用检索门面；普通模块对非 `api` 包的新越界访问被架构测试阻断。  
**验证方式**：运行 `./gradlew :crag-ingestion:test :crag-retrieval:test :crag-query:test :crag-api:test :crag-app:test`；使用 `rg` 检查跨模块 import；运行 `ArchitectureRulesTest`。  
**涉及文件**：`crag-ingestion/src/**`、`crag-retrieval/src/**`、`crag-query/src/**`、`crag-api/src/**`、`crag-app/src/test/**`

## 9.4 分离 Embedding 公共契约与内部实现

**目标**：把 Embedding 明确建模为 Retrieval 对外能力，同时隐藏当前 HTTP Sidecar 传输实现。  
**前置任务**：9.3  
**范围**：将 `EmbeddingClient`、公共请求响应和公共异常迁入 `ai.cerbur.crag.retrieval.api.embedding`；将 `SidecarEmbeddingClient` 及其传输细节留在 Retrieval 内部；更新 Ingestion、Retrieval、配置与测试。  
**非目标**：不实现 RPC，不创建独立 SDK module，不改变 Sidecar `/embed` 协议或 embedding 维度。  
**验收标准**：Ingestion 只 import `retrieval.api.embedding`；公开契约不暴露 HTTP/Spring Web 类型；Sidecar 实现仍由 Spring 正确注入并通过现有成功、失败测试。  
**验证方式**：运行 `rg -n 'retrieval\.embedding' crag-ingestion/src` 确认无旧包引用；运行 `./gradlew :crag-ingestion:test :crag-retrieval:test :crag-app:test` 和架构测试。  
**涉及文件**：`crag-retrieval/src/**`、`crag-ingestion/src/**`、`crag-app/src/test/**`

## 9.5 新建 crag-smoke 并迁移诊断端点

**目标**：保留现有冒烟和内部阶段诊断能力，同时让默认应用与业务模块不承载测试后门。  
**前置任务**：9.4  
**范围**：新增 library module `crag-smoke`；迁移 `TestController` 及相关响应类型；统一添加 `@Profile("smoke")`；由 `crag-app` 以可选运行时方式装配；为 Docker Compose 增加最小显式 smoke 启动方式；新增稳定诊断 HTTP 回归脚本；更新 README、benchmark 和测试端点参考。
**非目标**：不把 smoke 端点改造成正式 API，不允许业务模块依赖 `crag-smoke`，不生成独立 bootJar。  
**验收标准**：默认启动访问 `/api/v1/test/**` 返回不存在；显式 smoke 启动后现有诊断端点可用；自动化脚本对端点和退出码做明确断言；`crag-app` 不再包含 Controller 或直接业务 import；`crag-smoke` 是唯一允许跨层诊断访问的模块。
**验证方式**：运行 `./gradlew :crag-smoke:test :crag-app:test`；使用默认 Compose 验证端点不可见；显式 smoke Profile 启动后执行 `scripts/tests/http/` 的诊断回归脚本。
**涉及文件**：`settings.gradle.kts`、`crag-smoke/**`、`crag-app/**`、`docker-compose.yml`、`Dockerfile`、`README.md`、`benchmark/**`、`skill/crag-benchmark/**`、`scripts/tests/http/**`

## 9.6 收紧架构规则并完成全量验收

**目标**：删除迁移期例外，使文档、代码、构建和自动化规则共同表达同一套最终模块边界。  
**前置任务**：9.1、9.2、9.3、9.4、9.5  
**范围**：删除已解决的 ArchUnit 例外；由依赖校验器覆盖 Gradle 声明白名单与依赖环，由 ArchUnit 覆盖公开 API、代码依赖环、Repository、Controller、App 和 Smoke Profile 规则；更新当前实现索引、`plan_main`、`plan_7` 目标路径、决策归档、索引与验收证据。只对 Docker 文档做必要路由修正，不重写测试分类或部署契约。
**非目标**：不开始执行 `plan_7`，不扩展 Storage API 重构，不增加本计划外的新架构目标。  
**验收标准**：所有目标架构规则无迁移期豁免通过；默认与 smoke Docker 验证均符合预期；`package-structure.md` 不再保留已解决偏差；`plan_7` 解除架构阻塞并转为 `draft` 等待 Spring AI、Stub 与凭据校准；所有提交 hash 和验证证据回填。
**验证方式**：运行 `python3 scripts/validate_module_dependencies.py`、`./gradlew test`、`./gradlew check`、默认与 smoke Docker 冒烟、`python3 scripts/validate_plans.py --strict --verify-git`、`git diff --check`；使用 `git show --stat <hash>` 核对每项任务提交范围。  
**涉及文件**：`crag-app/src/test/**`、`constraints/package-structure.md`、`constraints/api-style.md`、`plan/plan_main.md`、`plan/plan_7/plan_7.md`、`plan/plan_9/plan_9.md`、`plan/plan_archive/2026-06-19-java-module-boundary-hardening.md`、`plan/index/README.md`

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-19 | 本机 macOS，JDK 25，Gradle 9.4.1 | `./gradlew :crag-app:test --tests '*ArchitectureTest' --rerun-tasks` | 通过 | 7 条架构规则全部通过；freeze 机制对新增方法调用形式越界生效（手工注入 ViolationProbe 探测类后 repository_cohesion 与 app_no_business_calls 失败，移除后恢复通过） |
| 2026-06-19 | 本机 Python 3 | `python3 -m unittest scripts.tests.test_validate_module_dependencies -v` | 通过 | 5/5 通过；含依赖环检测、白名单拒绝、crag-admin→crag-api 映射 |
| 2026-06-19 | 本机 Python 3 | `python3 scripts/validate_module_dependencies.py` | 通过 | 0 error |
| 2026-06-19 | 本机 | `python3 scripts/validate_plans.py --strict plan/plan_9/plan_9.md` | 通过 | 0 error, 0 warning |
| 2026-06-19 | 本机 | `./gradlew spotlessCheck` | 通过 | 格式合规 |
| 2026-06-19 | 本机 macOS，JDK 25，Gradle 9.4.1 | `./gradlew :crag-api:test :crag-app:test --rerun-tasks '*ArchitectureTest'` | 通过 | crag-api 无测试源(NO-SOURCE)；crag-app 架构测试按新 ai.cerbur.crag.api 包路径重新冻结后 7/7 通过 |
| 2026-06-19 | 本机 Python 3 | `python3 -m unittest scripts.tests.test_validate_module_dependencies -v` | 通过 | 5/5 通过；test_accepts_crag_admin_mapped 改为 test_accepts_crag_api_whitelist_directly 反映映射删除 |
| 2026-06-19 | 本机 | `rg -n 'crag-admin\|ai\.cerbur\.crag\.admin' settings.gradle.kts crag-app crag-api constraints README.md` | 通过 | 仅剩 constraints/api-style.md:44 明确历史说明(9.2 已完成迁移) |
| 2026-06-19 | 本机 | `python3 scripts/validate_plans.py --strict plan/plan_9/plan_9.md` | 通过 | 0 error, 0 warning |

## 阻塞记录

- **日期**：2026-06-19
- **原因**：`plan_11` 将先消除单元测试、Spring/H2 组件测试、ArchUnit 和 Docker HTTP 回归之间的分类冲突，避免本计划新增测试后再次改名或调整验收方式。
- **当前进度**：6 个任务均未开始，无需回滚实现。
- **解除条件**：`plan_11` 完成并通过测试、文档与 Plan 全量校验。
- **解除方**：`plan_11` owner。
- **解除状态**：已于 2026-06-19 解除。`plan_11` 四层测试分类、命名规则、H2 边界、Docker HTTP 回归触发条件、runId、LLM Stub 与 flaky 规则已落地，`./gradlew test`/`./gradlew check` 与 Plan 严格校验通过。
- **恢复后的下一步**：重新读取新版 `constraints/test-workflow.md`，从 9.1 开始执行。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-19 | 创建 plan_9 并设为待开始 | 完成包结构约束 grilling，迁移决策已全部收敛 | 建立 6 项顺序执行任务；plan_7 在本计划完成前阻塞 |
| 2026-06-19 | 状态调整为阻塞并增加 plan_11 前置依赖 | 测试分层必须先于 ArchUnit、Spring Context 和 Smoke 回归落地 | 架构目标不变；执行顺序调整为 plan_11 → plan_9 |
| 2026-06-19 | 收窄 Smoke、测试与 Docker 职责 | 避免与 plan_10 重复设计部署机制，并落实自动化 HTTP 回归 | plan_9 只交付最小 Smoke 隔离；完成后 plan_7 转 draft 校准 |
| 2026-06-19 | 状态从 blocked 恢复为 ready | plan_11 完成测试分层治理并全量校验通过，前置阻塞条件已满足 | 可从 9.1 开始执行；plan_7 仍保持阻塞至本计划完成 |
| 2026-06-19 | 状态从 ready 转为 in_progress，开始 9.1 | Plan Gate 全部通过，用户选定 deepseek/deepseek-v4-pro 作为实现模型 | 9.1 进行中；其余 5 项待开始 |
| 2026-06-19 | 完成 9.1，回填实现提交 95ca45e | ArchUnit 7 条规则建立，冻结例外精确关联 9.3/9.4/9.5；依赖白名单校验器修复依赖环检测后 5/5 通过 | 整体进度 1/6；下一步 9.2 |
| 2026-06-19 | 完成 9.2，回填实现提交 2a257a2 | crag-admin→crag-api 模块与 package 迁移，旧目录删除；同步 constraints、架构测试包路径与依赖校验器死映射清理 | 整体进度 2/6；下一步 9.3 |
| 2026-06-19 | 完成 9.3，回填实现提交 0bac69b | Ingestion/Retrieval/Query 公开入口迁入各模块 api 包；同步更新调用方 import、架构测试和 constraints | 整体进度 3/6；下一步 9.4 |
| 2026-06-19 | 完成 9.4，回填实现提交 b8bfab5 | EmbeddingClient/EmbeddingException 迁入 retrieval.api.embedding；SidecarEmbeddingClient 留在内部；所有 import 更新 | 整体进度 4/6；下一步 9.5 |
| 2026-06-19 | 完成 9.5，回填实现提交 4796117 | 新建 crag-smoke 模块；TestController 迁入并加 @Profile("smoke")；crag-app 加 runtimeOnly 依赖；Dockerfile 修正 crag-admin→crag-api+smoke；docker-compose.yml 加 smoke profile；HTTP 回归脚本创建 | 整体进度 5/6；下一步 9.6 |
| 2026-06-19 | 完成 9.6 并标记 Plan 完成，回填实现提交 b11ae44 | 删除所有迁移期 ArchUnit 冻结例外，8 条规则无豁免通过；添加 smoke Profile 校验规则；plan_7 解除阻塞转 draft；同步索引、执行队列和决策归档 | 整体进度 6/6；plan_9 完成 |
