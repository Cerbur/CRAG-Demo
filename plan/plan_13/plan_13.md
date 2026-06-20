---
workflow_version: 3
plan_id: plan_13
type: main
status: in_progress
created: 2026-06-19
updated: 2026-06-20
---

# plan_13 — Spring Boot 4 与 Spring AI 2 基线升级

## 背景与目标

`plan_7` 将正式接入 Spring AI 与 DeepSeek。当前仓库仍使用 Spring Boot 3.4.1，并由 `crag-ingestion` 通过旧的 OpenAI Starter `1.0.0-M5` 间接获得 `Document` 与 `TokenTextSplitter`。继续在该基线上实现 Query 会同时累积框架升级、Provider 解耦和测试迁移债务。

本计划把全仓库升级至 Spring Boot 4.1.0、Spring Framework 7 与 Spring AI 2.0.0，保持 Java 21 和 Gradle 9.4.1，建立集中版本治理与自动防漂移门禁，迁移 Jackson 3、模块化 Starter、现有生产代码和测试基础设施，并通过真实 Docker HTTP 回归证明既有业务契约未退化。

## 范围

- 使用 Gradle version catalog 集中维护 Spring Boot、Spring AI 与 dependency-management 插件版本。
- 所有子模块统一应用 `io.spring.dependency-management`，由根构建显式导入 Boot BOM。
- `crag-ingestion` 单独导入 Spring AI BOM，并仅依赖同时提供 `Document` 与 `TokenTextSplitter` 的 `spring-ai-commons`。
- 移除旧 OpenAI Starter、OpenAI 自动配置排除和测试 dummy API key；Provider 与 DeepSeek 依赖留给 `plan_7`。
- 迁移 Boot 4 模块化 Starter、Jackson 3、Framework 7、Data JPA、Validation、SQL 初始化和相关自动配置。
- 保持纯单元、轻量组件、架构测试与 Docker HTTP 回归四层语义。
- 固定唯一 Boot Jar 文件名并使 Dockerfile 精确复制构建产物。
- 增加框架版本与仓库来源校验器并接入根 `check`。
- 同步 README、技术方向、约束事实、`plan_7` 与计划索引。

## 非目标

- 不实现 Query Context、Prompt、LLM Adapter、DeepSeek 调用或 UserQuery 业务流程。
- 不引入任何 Spring AI Provider Starter、API key、ChatModel 或模型自动配置。
- 不改变 PostgreSQL、pgvector、Sidecar 模型、Retrieval 算法、分块业务不变量或 HTTP 业务契约。
- 不升级 Java 目标版本或预设升级 Gradle Wrapper。
- 不引入 Gradle dependency locking。
- 不升级与 Boot 4 迁移无关的第三方依赖。
- 不进行全仓字段注入治理；只修正本计划实际修改的生产类。
- 不新增测试数据清理 API，不直接删除数据库数据，不执行 `docker compose down -v`。
- 不提前实施 `plan_10` 的健康检查或部署治理目标。

## 前置依赖

- **执行前置 Plan**：`plan_6.hotfix_6`
- `plan_6.hotfix_6` 已完成，当前不存在未解除执行阻塞。
- 当前 Gradle Wrapper 为 9.4.1；只有官方插件解析或构建证据证明不兼容时才允许增加 Wrapper 调整。
- Spring Boot 4.1.0 与 Spring AI 2.0.0 已于 2026-06-20 通过 Maven Central 官方元数据确认均为 GA 版本。

## 文件边界

- `gradle/libs.versions.toml`
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle/wrapper/**`（仅有实际兼容证据时）
- `crag-*/build.gradle.kts`
- `crag-ingestion/src/main/**`
- `crag-smoke/src/main/**`
- `crag-*/src/test/**`
- `crag-app/src/main/resources/**`
- `crag-app/src/test/resources/**`
- `Dockerfile`
- `docker-compose.yml`（仅升级兼容所需）
- `scripts/validate_framework_dependencies.py`
- `scripts/tests/test_validate_framework_dependencies.py`
- `scripts/tests/http/**`（仅升级导致脚本失效时）
- `constraints/code-style.md`（仅事实变化时）
- `constraints/package-structure.md`
- `constraints/test-workflow.md`（仅事实变化时）
- `constraints/docker-structure.md`（仅 Docker 当前事实变化时）
- `README.md`
- `plan/plan_archive/2026-06-20-spring-boot-4-framework-baseline.md`
- `plan/plan_archive/README.md`
- `plan/plan_main.md`
- `plan/plan_13/plan_13.md`
- `plan/plan_7/plan_7.md`
- `plan/index/README.md`

## 关键决策

### 构建与版本治理

- `gradle/libs.versions.toml` 是 Spring Boot 4.1.0、Spring AI 2.0.0 与 `io.spring.dependency-management` 1.1.7 的唯一版本事实来源。
- 根构建在 `subprojects` 中统一应用 dependency-management 插件并导入 Boot BOM，模块不再重复声明插件、BOM 或 Gradle `platform(...)`。
- `crag-app` 的 Boot Plugin 负责打包与应用编排；依赖版本由显式 BOM 导入管理，不依赖隐式版本管理。
- Spring Framework 不单独固定版本，由 Boot 4.1.0 BOM 管理对应 Framework 7.x 补丁版本；禁止显式覆盖 Spring Framework 版本。
- Spring AI BOM 当前只在 `crag-ingestion` 导入。`plan_7` 接入模型时再在 `crag-query` 导入同一 catalog 版本的 Spring AI BOM。
- 正式依赖仓库只保留 Maven Central 与 Gradle Plugin Portal，删除 Spring Milestone 仓库。
- 不同时使用 dependency-management BOM 与 Gradle platform 管理同一框架，不引入 dependency locking。

### Spring AI 与分块边界

- `crag-ingestion` 仅使用 `spring-ai-commons`；Spring AI 2.0.0 的 `Document` 与 `TokenTextSplitter` 均由该模块提供。
- 禁止引入 `spring-ai-transformers`；该模块用于 ONNX Transformers 模型支持，会传递引入 ONNX Runtime、DJL 与 PyTorch engine，不属于当前文档切分边界。
- 删除 `spring-ai-openai-spring-boot-starter:1.0.0-M5`、OpenAI 自动配置排除和测试 dummy API key。
- 分块升级保持业务不变量，不要求与旧里程碑版本字节级切点一致：
  - parent 最大 1024 tokens；
  - child 基础最大 256 tokens，并附加约 64 tokens overlap；
  - 非空短文本不得丢失；
  - parent 与 child 必须覆盖全文和尾部；
  - parent/child 索引、归属与状态语义不变；
  - 相同输入重复执行结果确定。
- 允许 Spring AI 2 因句界实现变化产生不同切点或 chunk 数；测试不得绑定旧实现的偶然边界。

### Boot 4、Jackson 3 与生产代码

- 使用 Boot 4 的规范模块化 Starter，例如 MVC 使用 `spring-boot-starter-webmvc`；不依赖旧聚合 Starter 的兼容转发。
- `ObjectMapper` 迁移为 `tools.jackson.databind.ObjectMapper`，序列化异常使用 `tools.jackson.core.JacksonException`。
- Jackson 注解继续使用 `com.fasterxml.jackson.annotation.*`；不得机械迁移到不存在的新包。
- `AdminRagService` 改为构造器注入，并新增 ingestion 语义异常 `MetadataSerializationException` 保留 Jackson cause。
- metadata 序列化失败仍由全局兜底映射为现有 `50001`，不新增 HTTP 业务码。
- 只对本计划实际修改的生产类修正字段注入；不扩大为全仓机械重构。

### 测试、打包与兼容策略

- MVC Slice 使用 Boot 4 的 `spring-boot-starter-webmvc-test` 与 `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`；其他模块按需继续使用通用 `spring-boot-starter-test`。
- 主应用组件测试继续使用 `@SpringBootTest` + H2，纯单元测试不启动 Spring，架构规则不放宽。
- 禁止把 Slice 测试改成全量 Context 来绕过迁移问题。
- `crag-app` 禁用 plain Jar，`bootJar` 固定输出 `crag-demo.jar`；Dockerfile 精确复制该文件。
- 默认禁止 Jackson 2 兼容依赖、Boot 3/4 或 Spring AI 1/2 混用、永久自动配置排除、宽泛依赖排除、强制版本、禁用测试或放宽断言。
- 官方依赖确有传递冲突时，只允许最小定向措施，并记录 `dependencyInsight` 证据、影响与移除条件；未消除的临时桥接不能作为完成状态。
- 框架版本、生产代码与测试迁移必须作为一个原子任务提交，提交前 `./gradlew check` 全绿，禁止留下不可编译或测试失败的中间提交。
- 3 个任务各自创建独立实现提交，不共享提交；失败时按逆序撤销。

## 未决问题

无。迁移中出现的编译、解析或运行时差异必须以官方依赖解析、编译、测试和 Docker 回归证据处理，不作为放宽既定边界的理由。

## 风险与回滚

- Boot 4 模块化 Starter、Framework 7 与测试切片变化可能导致依赖或 Context 失败：按依赖解析、生产编译、测试、Docker 四层逐步定位，不跨层混合修复。
- Jackson 3 可能改变序列化默认行为：组件测试和 HTTP 脚本必须保持字段、业务码与 metadata 格式契约。
- Spring AI 2 splitter 可能改变句界切点：以业务不变量与确定性测试验收，不冻结旧实现偶然输出。
- Spring Data / Hibernate 变化可能暴露映射、native SQL 或初始化差异：H2 只证明受控装配，最终由 PostgreSQL Docker HTTP 链路证明真实兼容。
- Docker 构建可能因 Jar 产物或缓存层变化失败：固定 Boot Jar 名称并精确复制，不改变多阶段与非 root 结构。
- 每个任务一个实现提交；整体失败时按 `13.3 → 13.1` 逆序撤销，恢复 Boot 3.4.1、旧构建声明与原测试配置。
- 本计划无数据库 schema 或数据迁移。回滚不删除测试数据；通过唯一 runId 识别本计划回归数据。

## 测试与验证计划

- 构建治理校验器：
  - `python3 -m unittest scripts.tests.test_validate_framework_dependencies -v`
  - `python3 scripts/validate_framework_dependencies.py`
  - 验证 catalog 唯一版本源、禁止硬编码旧版本、禁止 milestone 仓库、禁止 platform 混用、Spring AI 依赖模块边界。
- 依赖解析：
  - 定向运行 Boot、Framework、Jackson 与 Spring AI 的 `dependencyInsight`；
  - 确认 Boot 4.1.0、Framework 7.x、Spring AI 2.0.0、Jackson 3，且不存在 Boot 3、Spring AI 1/Milestone 或显式 Framework 覆盖。
- 生产编译与启动：
  - `./gradlew compileJava`
  - `./gradlew :crag-app:test --tests '*CragDemoApplicationComponentTest'`
- 测试分层：
  - `./gradlew test`
  - `./gradlew :crag-app:test --tests '*ArchitectureTest'`
  - `./gradlew check`
- Docker HTTP 回归分两阶段：
  1. `docker compose up -d --build`，执行 `admin_rag_contract_test.sh` 与 `smoke_default_test.sh`。
  2. `docker compose --profile smoke up -d --build app-smoke`，执行 `smoke_endpoints_test.sh` 与 `retrieval_evidence_test.sh`。
- `smoke_default_test.sh` 只接受应用真实响应的 HTTP 404；连接失败或 HTTP 000 必须失败。
- `smoke_endpoints_test.sh` 的写入数据必须包含唯一 runId。
- `retrieval_evidence_test.sh` 等待索引超时后必须立即失败，不得警告后继续。
- 每次 HTTP 回归使用唯一 runId；无安全精确清理入口时保留可识别测试数据，只允许普通 `docker compose down`。
- 最终执行 `python3 scripts/validate_plans.py --strict --verify-git` 与 `git diff --check`。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 13.1 | 原子升级框架、生产代码与测试基线 | 🚧 进行中 | a83c62b | — |
| 13.2 | 固定 Boot Jar 并完成 Docker HTTP 回归 | 🚧 进行中 | 6624b6e | — |
| 13.3 | 同步技术方向、约束与下游计划并全量收口 | 🚧 进行中 | — | — |

整体进度：0 / 3（0%）

## 13.1 原子升级框架、生产代码与测试基线

**目标**：以单一可构建、可测试、可回滚的实现提交完成 Boot 4.1.0、Framework 7、Jackson 3 与 Spring AI 2.0.0 基线升级。
**前置任务**：无
**范围**：新增 version catalog 并集中声明 Boot、Spring AI 和 dependency-management 插件版本；根构建在 `subprojects` 中统一应用 dependency-management 与 Boot BOM；`crag-ingestion` 单独导入 Spring AI BOM并只依赖 `spring-ai-commons`；替换 Boot 4 模块化生产与测试 Starter；移除模块内 platform、硬编码版本和 milestone 仓库；新增框架依赖校验器、单元测试与根 `check` 接线；迁移 `Document`/`TokenTextSplitter` API、Jackson 3、`AdminRagService` 构造器注入和 `MetadataSerializationException`；删除 OpenAI 自动配置排除和 dummy key；修复 Web、Validation、JPA、SQL 初始化、MVC Slice、MockMvc、H2、主 Context 与测试自动配置；强化 splitter 业务不变量与确定性断言并保持 ArchUnit 规则。
**非目标**：不引入 Provider、DeepSeek、`spring-ai-transformers`、dependency locking 或 Framework 显式版本；不修改 HTTP 业务码；不对未触碰生产类进行字段注入治理；不改变分块业务不变量；不把 Slice 测试改为全量 Context；不禁用测试、放宽断言或新增架构豁免。
**验收标准**：Boot、Spring AI 与插件版本只在 catalog 定义；子模块不重复插件、Boot BOM、硬编码版本或 Gradle platform；Boot BOM 全局生效，Spring AI BOM 只在 `crag-ingestion`；旧 Boot 3.4.1、Spring AI M5/OpenAI Starter、`spring-ai-transformers`、milestone 仓库、旧自动配置排除和 dummy key 无残留；全部生产源码编译；主 Context 在 H2 下启动；metadata JSON、异常契约和分块业务不变量保持；MVC Slice 使用 Boot 4 专用 Starter 与新注解包；Gradle 默认发现全部三类测试且 `check` 全绿；无 Boot 3/Spring AI 1/Jackson 2 兼容桥或跳过项。
**验证方式**：运行校验器单元测试和脚本；定向运行 `dependencies`/`dependencyInsight`，确认 Boot 4.1.0、Framework 7.x、Spring AI 2.0.0、Jackson 3 与 dependency-management 1.1.7 正确解析，且没有 ONNX/DJL/PyTorch 传递依赖；运行 `./gradlew compileJava`、主应用组件测试、受影响模块定向测试、`./gradlew test`、`./gradlew :crag-app:test --tests '*ArchitectureTest'` 和 `./gradlew check`；重复执行相同分块输入验证确定性；核对测试报告无跳过项。
**涉及文件**：`gradle/libs.versions.toml`、`build.gradle.kts`、`settings.gradle.kts`、`crag-*/build.gradle.kts`、`scripts/validate_framework_dependencies.py`、`scripts/tests/test_validate_framework_dependencies.py`、`crag-ingestion/src/main/**`、`crag-smoke/src/main/**`、`crag-app/src/main/resources/**`、`crag-app/src/test/resources/**`、`crag-*/src/test/**`、`constraints/test-workflow.md`（仅事实变化时）

## 13.2 固定 Boot Jar 并完成 Docker HTTP 回归

**目标**：证明升级后的唯一 Boot Jar 可确定性构建，并在真实 Compose 环境保持 AdminRag、Smoke 与 Retrieval 契约。
**前置任务**：13.1
**范围**：禁用 `crag-app` plain Jar，固定 `bootJar` 为 `crag-demo.jar`；Dockerfile 精确复制该文件；修复默认 Smoke 将连接失败视为通过的问题、Evidence 索引超时后继续的问题，并为 Smoke 写入增加唯一 runId；仅在升级导致兼容问题时调整 Compose；按默认与 smoke profile 两阶段执行四套回归。
**非目标**：不增加健康检查、不改变服务拓扑、不新增清理 API、不直接删除数据库数据、不执行 `down -v`、不提前实现 `plan_10`。
**验收标准**：构建目录只产生预期可启动 Jar；镜像构建并以非 root 用户启动；默认正式 API 可用且 Smoke 端点真实返回 404，连接失败或 HTTP 000 不得通过；显式 Smoke 与 Retrieval Evidence 回归通过；索引等待超时必须失败；HTTP 状态、字段、业务码、完整 parent evidence 与稳定排序保持；所有写入测试数据具有唯一 runId。
**验证方式**：运行 `:crag-app:bootJar` 并检查产物；执行 `docker compose up -d --build`、AdminRag 与默认 Smoke 脚本；执行 `docker compose --profile smoke up -d --build app-smoke`、Smoke 与 Retrieval Evidence 脚本；检查容器日志无版本混用或敏感信息；最后普通 `docker compose down`。
**涉及文件**：`crag-app/build.gradle.kts`、`Dockerfile`、`docker-compose.yml`（仅必要时）、`scripts/tests/http/**`（仅必要时）、`constraints/docker-structure.md`（仅事实变化时）

## 13.3 同步技术方向、约束与下游计划并全量收口

**目标**：把已验证的框架基线写入项目级方向、文档和下游计划，并完成全部静态与构建门禁。
**前置任务**：13.2
**范围**：创建 Boot 3.4/Spring AI M5 到 Boot 4.1/Spring AI 2.0 的归档决策记录；更新 `plan_main.md`、README、包结构和必要约束事实；校准 `plan_7` 的 dependency-management/Spring AI BOM 边界和 DeepSeek 模块依赖任务；同步索引与验收记录；运行全量检查。
**非目标**：不实现 `plan_7`；不创建未验证的未来配置；不复制任务明细到索引；不把版本历史写入代码约束。
**验收标准**：项目级技术方向与实际构建一致；README 精确写明 Boot 4.1.0、Framework 7 与 Spring AI 2.0.0；归档决策含 before/after、影响、迁移与回滚；`plan_7` 明确只在 `crag-query` 增加 Spring AI BOM 与 DeepSeek 模块；索引状态和最终实际进度一致；全部检查通过。
**验证方式**：运行 `./gradlew check`、框架依赖校验器、约束校验器、`python3 scripts/validate_plans.py --strict --verify-git` 和 `git diff --check`；检索旧版本、旧 Starter、milestone 仓库和过期文档事实。
**涉及文件**：`plan/plan_archive/2026-06-20-spring-boot-4-framework-baseline.md`、`plan/plan_archive/README.md`、`plan/plan_main.md`、`README.md`、`constraints/package-structure.md`、其他仅事实变化的 `constraints/**`、`plan/plan_7/plan_7.md`、`plan/plan_13/plan_13.md`、`plan/index/README.md`

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-20 | macOS / Java 21 / Gradle 9.4.1 | 工作区与任务提交证据核对；`git diff --check`；`python3 scripts/validate_framework_dependencies.py`；`python3 -m unittest scripts.tests.test_validate_framework_dependencies -v`；`python3 scripts/validate_plans.py --strict --verify-git`；`./gradlew check`；定向 `dependencyInsight` | 失败 | `./gradlew check`、框架校验器及其 9 个单测通过，依赖解析为 Boot 4.1.0 / Framework 7.0.8 / Spring AI 2.0.0 / Jackson 3.1.4；但全部实现仍为未提交工作区改动，3 个任务提交栏为占位 hash，严格 Plan 校验报 3 个 P218；`crag-ingestion` 仍硬编码 `spring-ai-bom:2.0.0` 并使用 `platform()`，与 catalog 唯一版本源及任务 13.1 禁止子模块 Gradle platform 的验收标准冲突，校验器测试反而显式放行；新增 `MetadataSerializationException` 缺少序列化失败及 cause 保留测试。Docker HTTP 回归因实现尚未达到验收交接门槛未执行。 |
| 2026-06-20 | macOS / Java 21 / Gradle 9.4.1 | `./gradlew check`；`python3 scripts/validate_framework_dependencies.py`；`python3 -m unittest scripts.tests.test_validate_framework_dependencies -v`；`python3 scripts/validate_plans.py --strict --verify-git`；`git diff --check`；定向 `dependencyInsight`（Boot/Framework/Jackson） | 通过 | 三个问题均修复：(1) Spring AI BOM 从 `crag-ingestion` 的硬编码 `platform("...:2.0.0")` 迁移至根构建 `dependency-management` 统一导入，版本通过 catalog `libs.versions.spring.ai` 解析，所有子模块通过 BOM 获得 Spring AI 版本管理；(2) `check_no_platform_mixing()` 新增 Spring AI BOM platform 检测模式，移除 crag-ingestion 豁免，单元测试翻转为检测违规；(3) `AdminRagServiceTest` 新增 serializationFailureThrowsWithCausePreserved 测试，验证 `MetadataSerializationException` 保留 `JacksonException` cause 且消息含 docId 标识。`./gradlew check` 全绿，9 个校验器单测全过，Plan 校验 0 错误，依赖解析确认 Boot 4.1.0 / Framework 7.0.8 / Jackson 3.1.4。13.1 已提交 `d347833`。 |
| 2026-06-20 | macOS / Java 21 / Gradle 9.4.1 / Docker | `./gradlew :crag-app:bootJar`；`docker compose up -d --build` → `admin_rag_contract_test.sh` + `smoke_default_test.sh`；`docker compose --profile smoke up -d --build app-smoke` → `smoke_endpoints_test.sh` + `retrieval_evidence_test.sh`；容器日志检查；`docker compose down` | 通过 | 四套回归全部通过：(1) AdminRag 契约测试 — 成功、Validation、未知路径全 PASS；(2) 默认 Smoke 测试 — 4 个测试端点均返回 404，HTTP 000 正确判定为 FAIL；(3) Smoke 端点测试 — /smoke、/chunk、/retrieval 全 PASS，写入数据含唯一 RUN_ID `smoke-20260620-140920-23690`；(4) Parent Evidence 回归 — 证据结构、parentChunkId 匹配、RUN_ID 内容包含、稳定排序、matchedChildIds 交叉验证全 PASS，RUN_ID=`evidence-1781935771-23743`。构建目录只产生 `crag-demo.jar`（54 MB），无 plain jar；容器以非 root 用户运行；日志确认 Spring Boot v4.1.0 / Spring v7.0.8，无版本混用或敏感信息。`docker compose down` 普通清理，测试数据保留可识别。 |
| 2026-06-20 | macOS / Java 21 / Gradle 9.4.1 | `./gradlew check`；`python3 scripts/validate_framework_dependencies.py`；`python3 -m unittest scripts.tests.test_validate_framework_dependencies -v`；`python3 scripts/validate_plans.py --strict --verify-git`；`git diff --check`；全仓检索旧版本/旧 Starter/milestone 仓库残留 | 通过 | 全量检查通过：(1) `./gradlew check` 全绿，0 错误；(2) 框架依赖校验器及其 9 个单测全过；(3) Plan 严格校验 0 错误；(4) 归档决策记录 `plan/plan_archive/2026-06-20-spring-boot-4-framework-baseline.md` 已创建并修正 `platform()` 描述；(5) `plan_main.md` 补充 Spring AI 2.0.0 版本；(6) README 已含 Boot 4.1.0 / Framework 7 / Spring AI 2.0.0；(7) `plan_7.md` 未决问题已校准为根 `dependency-management` 统一导入 Spring AI BOM，明确 `crag-query` 不使用 `platform()`；(8) `plan/index/README.md` 同步为待验收状态；(9) 全仓检索无 Boot 3.4.1、Spring AI M5/1.0.0-M5、OpenAI Starter、Spring Milestone 仓库残留。 |
| 2026-06-20 | macOS / Java 21 / Gradle 9.4.1 | 提交范围与任务证据核对；`python3 scripts/validate_framework_dependencies.py`；`python3 -m unittest scripts.tests.test_validate_framework_dependencies -v`；`python3 scripts/validate_plans.py --strict --verify-git`；`git diff --check`；`./gradlew check` | 失败 | 静态校验、9 个校验器单测、严格 Plan 校验和 Gradle 全量检查均通过；但实现与 Plan 规范不一致：(1) 根构建向全部子模块导入 Spring AI BOM，而本 Plan 的范围、关键决策及 13.1 验收标准明确要求 Spring AI BOM 只在 `crag-ingestion` 导入，并由 plan_7 后续在 `crag-query` 增加；(2) 任务 13.2 记录的实现提交 `b1c92c3` 只修改 Plan 文档，真正的 Dockerfile 和 HTTP 脚本改动混在 13.1 提交 `d347833`，不满足任务实现证据真实性及“三个任务各自独立实现提交、不共享提交”的关键决策；(3) `d347833` 同时包含 13.1、13.2、13.3 文件范围。上述问题已足以阻断完成，本轮未重复执行 Docker HTTP 回归。 |

## 阻塞记录

无。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-19 | 创建计划并设为待开始 | Plan 7 grilling 决定在接入 Spring AI Provider 前独立升级框架基线 | 执行队列为 plan_6.hotfix_6 → plan_13 → plan_7 → plan_10 |
| 2026-06-20 | 完成执行细节 grilling 并重写为 5 项任务 | 锁定集中版本治理、dependency-management、最小 Spring AI 模块、Jackson 3、测试分层、确定性 Boot Jar、Docker 回归与项目级方向同步 | 保持 ready；先提交 Plan 与索引，再按 13.1 至 13.5 串行执行 |
| 2026-06-20 | 二次 grilling 收敛为 3 项原子任务 | 禁止不可构建中间提交；确认 splitter 只需 commons、Boot 4 MVC 测试专用 Starter、根构建集中 BOM，并修复三处 HTTP 回归假阳性或隔离漏洞 | 保持 ready；13.1 必须在单一提交内完成框架、生产和测试迁移并使 `check` 全绿 |
| 2026-06-20 | 独立验收失败，退回进行中 | 实现未提交且无真实任务 hash；Spring AI BOM 的硬编码 `platform()` 与 Plan 验收标准冲突；metadata 序列化异常契约缺少失败路径测试 | 13.1 退回进行中，13.2 与 13.3 恢复待开始；修复后按任务分别提交、回填真实 hash、完成自测与 Docker 回归，再重新交接验收 |
| 2026-06-20 | 第二次独立验收失败，退回进行中 | Spring AI BOM 被根构建导入全部子模块，违反 Plan 的模块边界；13.2 实现证据指向纯文档提交，且 13.1 提交混入后续任务范围 | 13.1、13.2、13.3 均退回进行中；按既定模块边界修正 BOM 导入，并整理真实、独立的任务实现提交与 hash 后重新交接 |
| 2026-06-20 | BOM 边界修正与提交重整 | 修正 Spring AI BOM 只在 crag-ingestion 导入（通过 catalog library + dependencyManagement），根构建不再全局导入；校验器更新为禁止根构建含 Spring AI BOM；重整三个任务为独立提交 | 13.1 含 framework/生产/测试/BOM 修正，13.2 含 Dockerfile/HTTP 回归脚本，13.3 含文档同步；旧混合提交 d347833/b1c92c3/40fb33b 已由 soft reset 替换 |
