---
workflow_version: 3
plan_id: plan_16
type: main
status: completed
created: 2026-06-25
updated: 2026-06-25
---

# plan_16 — RAG Service Module 收口与 Smoke HTTP 入口重整

> **For agentic workers:** 执行本计划必须先读取 `skill/execute-crag-plan/SKILL.md`；实现步骤使用测试先行、任务级提交和独立验收交接。

**Goal**：将 RAG 内部能力从多个 Gradle subproject 收口到 `crag-rag-service`，并把现有写入/Query HTTP 入口转为 `smoke` Profile 下的验证能力。

**Architecture**：`crag-rag-service` 成为唯一 RAG Gradle module，拥有 storage、retrieval、query、ingestion、smoke 和 rag app package。`crag-console-api` 与 `crag-open-api` 保持为未来正式 HTTP 入口；当前 legacy RAG 写入和 Query HTTP 行为只保留为 `/api/v1/smoke/**` 验证端点。Gradle module 边界被 package + ArchUnit 规则替代，业务行为不做重写。

**Tech Stack**：Java 21、Spring Boot 4.1.0、Spring Framework 7、Spring AI 2.0.0、Gradle 9.4.1、PostgreSQL 17、Docker Compose、ArchUnit、Spotless。

## 全局实现约束

- `crag-rag-service` 是唯一 RAG Gradle module；`crag-storage`、`crag-retrieval`、`crag-query`、`crag-ingestion`、`crag-api`、`crag-smoke` 必须从 `settings.gradle.kts` 移除。
- `ai.cerbur.crag.storage`、`ai.cerbur.crag.retrieval`、`ai.cerbur.crag.query`、`ai.cerbur.crag.ingestion` package 名保持稳定，避免无收益 import churn。
- 现有写入与 Query HTTP 行为不再是正式 API，只能作为 `smoke` Profile 下的验证入口。
- 所有 legacy RAG HTTP 验证 URI 使用 `/api/v1/smoke/**` 前缀。
- HTTP 回归脚本只允许替换 URL path；不得修改请求体、断言、等待、清理、执行顺序或业务判断。
- 不改造 ingestion、retrieval、query、storage、LLM、Embedding、Sparse、Dense、RRF 或 Rerank 的业务语义。
- 新增或迁移 Java 代码遵守 `constraints/code-style.md`；HTTP 边界遵守更新后的 `constraints/api-style.md`；持久化遵守 `constraints/persistence-style.md`；测试遵守 `constraints/test-workflow.md`。

## 背景与目标

当前 RAG 实现分散在 `crag-storage`、`crag-retrieval`、`crag-query`、`crag-ingestion`、`crag-api` 和 `crag-smoke` 六个 Gradle subproject 中。这些模块没有独立部署、独立版本或跨 RAG runtime 的复用需求，最终都由 `crag-rag-service` 组合启动。继续保留这些 Gradle module 会让内部包边界、依赖白名单、测试命令和未来 console/open API 演进变得更重。

用户已确认目标形态为完整收口：`crag-rag-service` 承载 RAG 内部能力与 smoke 验证入口；未来正式 HTTP API 由 `crag-console-api` 和 `crag-open-api` 承担。现有 `AdminRagController` 与 `UserQueryController` 不再代表正式 API，而是 smoke 验证写入与 Query 链路的工具。

本计划根据设计文档 `docs/superpowers/specs/2026-06-25-rag-service-module-consolidation-design.md` 执行。

## 范围

- 将 `crag-storage`、`crag-retrieval`、`crag-query`、`crag-ingestion` 的生产源码、测试源码和测试资源迁入 `crag-rag-service`。
- 将 `crag-api` 的 Controller、DTO、异常映射和组件测试迁入 `crag-rag-service` 的 smoke package。
- 将 `crag-smoke` 的诊断 Controller 迁入 `crag-rag-service` 的 smoke package。
- 从 `settings.gradle.kts` 移除被合并的六个 subproject，并删除对应 `build.gradle.kts`。
- 在 `crag-rag-service/build.gradle.kts` 合并被迁移模块需要的生产和测试依赖。
- 将 legacy RAG HTTP 验证 URI 统一为 `/api/v1/smoke/**`。
- 更新 ArchUnit、依赖校验器、框架依赖校验器及其测试，使其验证 package 边界和新的 module 事实。
- 更新 `constraints/package-structure.md`、`constraints/api-style.md`、`constraints/docker-structure.md`、`constraints/test-workflow.md` 中受影响的当前事实。
- 更新 `scripts/tests/http/**` 中旧 RAG HTTP URL，只替换 path。
- 更新 README 中受影响的模块索引、学习路径和 smoke URL 当前事实。

## 非目标

- 不设计或实现新的 `crag-console-api` / `crag-open-api` 正式 HTTP 契约。
- 不改变现有写入、检索、问答、索引构建、LLM 或 Sidecar 行为。
- 不重命名 `storage`、`retrieval`、`query`、`ingestion` 业务 package。
- 不新增跨服务 RPC、事件、异步链路或数据库 schema 语义。
- 不重写 Docker HTTP 回归脚本逻辑；脚本只做 URL path 替换。
- 不修改历史已完成 Plan 文件中的旧事实；历史 Plan 保留当时上下文。

## 前置依赖

- **执行前置 Plan**：无
- 设计文档 `docs/superpowers/specs/2026-06-25-rag-service-module-consolidation-design.md` 已提交，提交为 `dfe330e`。
- 进入实现前必须先提交本计划和索引；未提交规划修订时不得开始 16.1。

## 文件边界

- `settings.gradle.kts`
- `build.gradle.kts`
- `crag-rag-service/**`
- `crag-storage/**`
- `crag-retrieval/**`
- `crag-query/**`
- `crag-ingestion/**`
- `crag-api/**`
- `crag-smoke/**`
- `constraints/package-structure.md`
- `constraints/api-style.md`
- `constraints/docker-structure.md`
- `constraints/test-workflow.md`
- `scripts/validate_module_dependencies.py`
- `scripts/tests/test_validate_module_dependencies.py`
- `scripts/validate_framework_dependencies.py`
- `scripts/tests/test_validate_framework_dependencies.py`
- `scripts/validate_constraints.py`
- `scripts/tests/test_validate_constraints.py`
- `scripts/tests/http/**`
- `README.md`
- `plan/plan_16/plan_16.md`
- `plan/index/README.md`

## 实现文件地图

### Gradle 与源码迁移

- `settings.gradle.kts`：移除 `crag-storage`、`crag-retrieval`、`crag-query`、`crag-ingestion`、`crag-api`、`crag-smoke`。
- `crag-rag-service/build.gradle.kts`：保留 Boot application module；合并 JPA、Web MVC、Validation、Redis、Actuator、Spring AI、PostgreSQL、ArchUnit、H2、Mockito extension 所需依赖；移除对被合并 subproject 的 `implementation` / `runtimeOnly`。
- `crag-rag-service/src/main/java/ai/cerbur/crag/storage/**`：从 `crag-storage` 迁入。
- `crag-rag-service/src/main/java/ai/cerbur/crag/retrieval/**`：从 `crag-retrieval` 迁入。
- `crag-rag-service/src/main/java/ai/cerbur/crag/query/**`：从 `crag-query` 迁入。
- `crag-rag-service/src/main/java/ai/cerbur/crag/ingestion/**`：从 `crag-ingestion` 迁入。
- `crag-rag-service/src/test/java/ai/cerbur/crag/storage/**`、`retrieval/**`、`query/**`、`ingestion/**`：迁入对应纯单元测试和组件测试。
- `crag-rag-service/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`：保留迁入模块使用的 Mockito inline mock maker 资源。

### Smoke HTTP 边界

- `crag-rag-service/src/main/java/ai/cerbur/crag/smoke/controller/AdminRagController.java`：从原 `crag-api` 迁入，新增 `@Profile("smoke")`，路径改为 `POST /api/v1/smoke/admin/rag`。
- `crag-rag-service/src/main/java/ai/cerbur/crag/smoke/controller/UserQueryController.java`：从原 `crag-api` 迁入，新增 `@Profile("smoke")`，路径改为 `POST /api/v1/smoke/query`。
- `crag-rag-service/src/main/java/ai/cerbur/crag/smoke/controller/TestController.java`：从原 `crag-smoke` 迁入，保留 `@Profile("smoke")`，路径从 `/api/v1/test` 改为 `/api/v1/smoke/test`。
- `crag-rag-service/src/main/java/ai/cerbur/crag/smoke/controller/advice/GlobalExceptionHandler.java`：从原 `crag-api` 迁入，继续作为 smoke HTTP 异常映射边界。
- `crag-rag-service/src/main/java/ai/cerbur/crag/smoke/dto/rag/**`：从原 `crag-api.dto.rag` 迁入。
- `crag-rag-service/src/main/java/ai/cerbur/crag/smoke/dto/query/**`：从原 `crag-api.dto.query` 迁入。
- `crag-rag-service/src/test/java/ai/cerbur/crag/smoke/**`：迁入并更新原 `crag-api` Controller、DTO、异常映射测试。

### 架构与校验器

- `crag-rag-service/src/test/java/ai/cerbur/crag/rag/app/arch/ModuleBoundaryArchitectureTest.java`：改为验证新 package 边界、smoke profile、Repository 内聚和 Access/Knowledge 禁止 RAG 依赖。
- `scripts/validate_module_dependencies.py`：删除被移除 RAG subproject 的白名单项；保留剩余 module 依赖规则。
- `scripts/tests/test_validate_module_dependencies.py`：更新示例 settings/build 文件，覆盖被移除 module 不再作为必需白名单项。
- `scripts/validate_framework_dependencies.py`：更新 Spring AI BOM / dependency allowlist，允许 Spring AI 依赖集中在 `crag-rag-service`，删除对已移除 subproject 的期望。
- `scripts/tests/test_validate_framework_dependencies.py`：同步测试数据和断言。
- `scripts/validate_constraints.py`：更新对 `crag-api`、`crag-smoke`、RAG 子模块当前事实的约束检查。
- `scripts/tests/test_validate_constraints.py`：同步约束校验测试。

### HTTP 回归脚本与文档

- `scripts/tests/http/admin_rag_contract_test.sh`：`/api/v1/admin/rag` 改为 `/api/v1/smoke/admin/rag`。
- `scripts/tests/http/query_stub_success_test.sh`：写入和 Query URL 改为 smoke namespace。
- `scripts/tests/http/query_stub_failure_test.sh`：Query URL 改为 smoke namespace。
- `scripts/tests/http/query_deepseek_acceptance_test.sh`：写入和 Query URL 改为 smoke namespace。
- `scripts/tests/http/retrieval_evidence_test.sh`：`/api/v1/test/**` 改为 `/api/v1/smoke/test/**`。
- `scripts/tests/http/smoke_default_test.sh`：默认启动下不可访问路径改为 `/api/v1/smoke/test/**`。
- `scripts/tests/http/smoke_endpoints_test.sh`：诊断路径改为 `/api/v1/smoke/test/**`。
- `scripts/tests/http/docker_readiness_test.sh`：RAG smoke/default URL 改为 `/api/v1/smoke/**`，保持原健康检查逻辑。
- `README.md`：更新 curl 示例、模块索引、学习路径和 legacy smoke HTTP 说明。
- `constraints/*.md`：同步当前事实，不复制 Plan 任务细节。

## 关键决策

- Gradle module 边界收口，Java package 边界保留。
- `crag-api` 不再代表正式 API；现有 Controller 迁入 smoke package。
- 所有 legacy RAG HTTP 验证端点必须使用 `/api/v1/smoke/**`，避免与未来正式 console/open API 混淆。
- Smoke Controller 使用类级 `@Profile("smoke")`，不只依赖包扫描或配置类。
- HTTP 脚本只做 URL path 替换，避免把 module 迁移和业务回归逻辑改写混在一起。
- 已完成历史 Plan 中的旧 module/URL 描述不回改；本 Plan 和约束文档记录新的目标事实。
- 若文件移动导致 Git 无法识别 rename，不以保留 rename 统计为目标；以编译、测试和边界规则为准。

## 未决问题

无。

## 风险与回滚

- 风险：多 source set 合并后可能出现重复测试资源、重复测试配置或依赖缺失。预防措施是先完成 Gradle/source 迁移并运行定向 `:crag-rag-service:test`，再迁 smoke HTTP。
- 风险：旧 URL 残留会导致 Docker HTTP 回归脚本误打正式路径。预防措施是对 `scripts/tests/http/**`、`README.md`、Controller 测试和生产 Controller 注释执行旧路径检索。
- 风险：移除 module 后校验器与约束文档继续假设旧白名单。预防措施是把校验器、ArchUnit 和约束文档作为独立任务，并运行对应 Python 单测与 Plan 校验。
- 风险：`@Profile("smoke")` 遗漏会让验证端点在默认 RAG 服务暴露。预防措施是 ArchUnit 规则和 `smoke_default_test.sh` 双重验证。
- 回滚：本计划不包含不可逆数据库或运行时迁移。若迁移失败，可通过 `git revert` 回退实现提交；若只完成部分任务，按任务提交边界逐个 revert，并恢复 `settings.gradle.kts` 中被移除的 subproject include。

## 测试与验证计划

- 纯单元测试：`./gradlew test`，覆盖迁入后的 storage、retrieval、query、ingestion 纯单元测试。
- 轻量组件测试：`./gradlew test --tests '*ComponentTest'`，覆盖 smoke Controller、异常映射、RAG application context 和 H2 替身装配。
- 架构测试：`./gradlew :crag-rag-service:test --tests '*ArchitectureTest'`，覆盖 package 边界、Repository 内聚、smoke profile 和禁止 Access/Knowledge 依赖 RAG。
- 静态与格式：`./gradlew spotlessCheck`、`./gradlew check`。
- Plan 校验：`python3 scripts/validate_plans.py`；完成前由验收 session 运行 `python3 scripts/validate_plans.py --strict --verify-git`。
- 约束/依赖校验器：`python3 -m unittest scripts.tests.test_validate_module_dependencies scripts.tests.test_validate_framework_dependencies scripts.tests.test_validate_constraints -v`。
- Docker HTTP 回归：通过 Docker Compose 执行受影响脚本，至少包括 `scripts/tests/http/smoke_default_test.sh`、`scripts/tests/http/smoke_endpoints_test.sh`、`scripts/tests/http/admin_rag_contract_test.sh`、`scripts/tests/http/query_stub_success_test.sh`、`scripts/tests/http/query_stub_failure_test.sh`、`scripts/tests/http/retrieval_evidence_test.sh` 和 `scripts/tests/http/docker_readiness_test.sh`。真实 DeepSeek 脚本 `query_deepseek_acceptance_test.sh` 仅在凭据、额度和网络可用时执行；不可用时必须在验收记录中说明风险。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 16.1 | 合并 RAG 内部业务 packages 到 `crag-rag-service` | ✅ 完成 | f40d0eba | 2026-06-25 |
| 16.2 | 迁移 legacy HTTP 为 smoke-only `/api/v1/smoke/**` | ✅ 完成 | 5dc0d93a | 2026-06-25 |
| 16.3 | 更新架构规则、约束文档和静态校验器 | ✅ 完成 | a4e04cbb | 2026-06-25 |
| 16.4 | 更新 HTTP 回归脚本 URL 与 README 当前事实 | ✅ 完成 | 89410552 | 2026-06-25 |
| 16.5 | 完成全量验证、Plan 交接和索引同步 | ✅ 完成 | 63edde11 | 2026-06-25 |

整体进度：5 / 5（100%）

## 16.1 合并 RAG 内部业务 packages 到 `crag-rag-service`

**目标**：`storage`、`retrieval`、`query`、`ingestion` 四个内部业务 package 迁入 `crag-rag-service`，被合并 subproject 不再参与 Gradle 构建。  
**前置任务**：无  
**范围**：迁移四个业务 module 的 main/test/resources；合并 `crag-rag-service/build.gradle.kts` 依赖；从 `settings.gradle.kts` 移除 `crag-storage`、`crag-retrieval`、`crag-query`、`crag-ingestion`；删除四个 module 的 `build.gradle.kts` 和迁空后的源码目录；修正因 source set 合并产生的 import、测试配置和依赖问题。  
**非目标**：不迁移 `crag-api` / `crag-smoke`；不改变 HTTP URI；不改业务行为。  
**验收标准**：`crag-rag-service` 能编译并发现迁入的四类业务测试；`settings.gradle.kts` 不再 include 四个内部业务 subproject；生产代码仍使用 `ai.cerbur.crag.storage`、`retrieval`、`query`、`ingestion` package；无 `project(":crag-storage")`、`project(":crag-retrieval")`、`project(":crag-query")`、`project(":crag-ingestion")` 依赖残留。  
**验证方式**：运行 `./gradlew :crag-rag-service:test --tests '*Chunk*Test' --tests '*Retrieval*Test' --tests '*UserQueryServiceTest' --tests '*AdminRagServiceTest'`；运行 `rg 'project\\(\":crag-(storage|retrieval|query|ingestion)\"\\)|include\\([^)]*\"crag-(storage|retrieval|query|ingestion)\"' settings.gradle.kts crag-rag-service/build.gradle.kts` 应无业务 subproject 依赖残留。  
**涉及文件**：`settings.gradle.kts`、`crag-rag-service/**`、`crag-storage/**`、`crag-retrieval/**`、`crag-query/**`、`crag-ingestion/**`

## 16.2 迁移 legacy HTTP 为 smoke-only `/api/v1/smoke/**`

**目标**：原 `crag-api` 和 `crag-smoke` 的 HTTP 能力进入 `crag-rag-service` smoke package，默认启动不暴露 legacy RAG HTTP 验证端点。  
**前置任务**：16.1  
**范围**：迁移 `AdminRagController`、`UserQueryController`、`GlobalExceptionHandler`、HTTP DTO、Controller 组件测试和 `TestController`；包名改为 `ai.cerbur.crag.smoke.*`；所有 smoke Controller 使用类级 `@Profile("smoke")`；URI 改为 `POST /api/v1/smoke/admin/rag`、`POST /api/v1/smoke/query`、`/api/v1/smoke/test/**`；从 `settings.gradle.kts` 移除 `crag-api`、`crag-smoke`；删除两个 module 的 `build.gradle.kts` 和迁空后的源码目录。  
**非目标**：不修改 HTTP 请求体、响应字段、错误码语义或业务编排；不新增正式 console/open API。  
**验收标准**：默认 RAG application context 不注册 smoke Controller；`smoke` Profile 下 Controller 测试通过；原 `ai.cerbur.crag.api` 生产包不存在；`crag-api` 和 `crag-smoke` 不再是 Gradle subproject；Controller 测试只断言 `/api/v1/smoke/**`。  
**验证方式**：运行 `./gradlew :crag-rag-service:test --tests '*ControllerComponentTest' --tests '*GlobalExceptionHandlerComponentTest' --tests '*RagServiceComponentTest'`；运行 `rg 'ai\\.cerbur\\.crag\\.api|/api/v1/admin/rag|/api/v1/query|/api/v1/test' crag-rag-service/src/main crag-rag-service/src/test`，除迁移说明中允许的历史注释外不得出现旧生产路径。  
**涉及文件**：`settings.gradle.kts`、`crag-rag-service/src/main/java/ai/cerbur/crag/smoke/**`、`crag-rag-service/src/test/java/ai/cerbur/crag/smoke/**`、`crag-api/**`、`crag-smoke/**`

## 16.3 更新架构规则、约束文档和静态校验器

**目标**：项目约束、ArchUnit 和 Python 校验器全部表达新的 package 边界与 Gradle module 事实。  
**前置任务**：16.2  
**范围**：更新 `ModuleBoundaryArchitectureTest`；更新 `constraints/package-structure.md`、`constraints/api-style.md`、`constraints/docker-structure.md`、`constraints/test-workflow.md`；更新 `scripts/validate_module_dependencies.py`、`scripts/validate_framework_dependencies.py`、`scripts/validate_constraints.py` 及其单测。  
**非目标**：不回写历史 Plan；不修改与本迁移无关的约束章节；不放宽 Repository 内聚、smoke profile 或 Access/Knowledge 禁止依赖 RAG 的规则。  
**验收标准**：约束文档不再把六个被合并 subproject 描述为当前 standalone module；`crag-rag-service` 被描述为 RAG internal packages 和 smoke verification HTTP 的所有者；正式 HTTP 入口归属 `crag-console-api` / `crag-open-api`；ArchUnit 验证 package 边界；Python 校验器单测反映新 module 集合。  
**验证方式**：运行 `./gradlew :crag-rag-service:test --tests '*ArchitectureTest'`；运行 `python3 -m unittest scripts.tests.test_validate_module_dependencies scripts.tests.test_validate_framework_dependencies scripts.tests.test_validate_constraints -v`；运行 `python3 scripts/validate_plans.py`。  
**涉及文件**：`crag-rag-service/src/test/java/ai/cerbur/crag/rag/app/arch/ModuleBoundaryArchitectureTest.java`、`constraints/package-structure.md`、`constraints/api-style.md`、`constraints/docker-structure.md`、`constraints/test-workflow.md`、`scripts/validate_module_dependencies.py`、`scripts/tests/test_validate_module_dependencies.py`、`scripts/validate_framework_dependencies.py`、`scripts/tests/test_validate_framework_dependencies.py`、`scripts/validate_constraints.py`、`scripts/tests/test_validate_constraints.py`

## 16.4 更新 HTTP 回归脚本 URL 与 README 当前事实

**目标**：所有当前 RAG HTTP 验证脚本和 README 示例使用 `/api/v1/smoke/**`，且脚本逻辑不被改写。  
**前置任务**：16.3  
**范围**：替换 `scripts/tests/http/**` 中旧 RAG 写入、Query 和 `/test` 诊断 URL path；更新 README 的 curl 示例、模块索引、学习路径和 smoke 说明；仅在注释直接提到旧路径时同步注释。  
**非目标**：不修改脚本请求体、断言、等待、清理、执行顺序、服务端口或业务判断；不重命名脚本。  
**验收标准**：`scripts/tests/http/**` 中不再出现 `/api/v1/admin/rag`、`/api/v1/query` 或 `/api/v1/test`；README 不再把 `crag-api` 描述为当前正式 RAG HTTP module；脚本 diff 只包含 URL path 和直接相关说明文本。  
**验证方式**：运行 `rg '/api/v1/(admin/rag|query|test)' scripts/tests/http README.md` 应无旧路径；人工检查 `git diff -- scripts/tests/http` 确认没有脚本逻辑改动；后续 16.5 通过 Docker Compose 执行受影响脚本。  
**涉及文件**：`scripts/tests/http/**`、`README.md`

## 16.5 完成全量验证、Plan 交接和索引同步

**目标**：完成本计划全部自测、格式、静态校验、Docker HTTP 回归和执行 session 交接记录。  
**前置任务**：16.4  
**范围**：运行必需 Gradle/Python/Docker 验证；修复验证发现的本计划范围内问题；回填 16.1-16.4 实现提交短 hash；将任务转为待验收；将 Plan 转为 `verifying`；同步 `plan/index/README.md` 验收队列。  
**非目标**：不做最终验收完成；最终完成必须由未参与实现的独立验收 session 执行。  
**验收标准**：`./gradlew spotlessCheck`、`./gradlew test`、`./gradlew check` 通过；Plan 校验通过；受影响 Docker HTTP smoke 回归脚本执行并记录结果；每个实现任务提交栏有真实短 hash；Plan 和索引均处于待验收状态且互相一致。  
**验证方式**：运行 `./gradlew spotlessCheck`、`./gradlew test`、`./gradlew check`、`python3 scripts/validate_plans.py`；通过 Docker Compose 执行 `smoke_default_test.sh`、`smoke_endpoints_test.sh`、`admin_rag_contract_test.sh`、`query_stub_success_test.sh`、`query_stub_failure_test.sh`、`retrieval_evidence_test.sh`、`docker_readiness_test.sh`；真实 DeepSeek 脚本按凭据可用性执行或记录未执行原因。  
**涉及文件**：`plan/plan_16/plan_16.md`、`plan/index/README.md`、本计划范围内因验证失败需要修正的文件

## 验收记录

> 以下为执行 session 自测记录，最终完成判定由未参与实现的独立验收 session 给出。

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-25 | 本地 Java 21 / Gradle 9.4.1 | `./gradlew check` | 通过 | spotlessCheck + crag-rag-service 356 tests（0 failures/0 skipped）+ 四个校验器 0 error + validatePlans --strict 0 error（24 条历史 P101 警告，与本计划无关） |
| 2026-06-25 | 本地 | `python3 -m unittest scripts.tests.test_validate_{module_dependencies,framework_dependencies,constraints}` | 通过 | 55 tests OK |
| 2026-06-25 | 本地 | `./gradlew :crag-rag-service:test --tests '*ArchitectureTest'` | 通过 | 包边界、Repository 内聚、smoke `@Profile`、Access/Knowledge 禁止依赖 RAG/smoke 全部满足 |
| 2026-06-25 | Docker Engine 29.5.2 | 重建 `rag-service` / `rag-service-smoke` 镜像 | 通过 | 收口后唯一 RAG module 镜像构建成功，两服务 healthy |
| 2026-06-25 | Docker | `smoke_default_test.sh`（8082） | 通过 | 默认模式下 `/api/v1/smoke/test/**` 全部 404，验证 `@Profile("smoke")` 门控 |
| 2026-06-25 | Docker | `smoke_endpoints_test.sh`（8083） | 通过 | smoke Profile 下 `/smoke` `/chunk` `/retrieval` 均成功（code=0） |
| 2026-06-25 | Docker | `retrieval_evidence_test.sh`（8083） | 通过 | parent evidence 的 matchedChildIds 与真实 child 检索命中交叉验证一致 |
| 2026-06-25 | Docker | `admin_rag_contract_test.sh`（8083） | 通过 | AdminRag 写入契约、Bean Validation、未知路径 404 全通过 |
| 2026-06-25 | Docker | `query_stub_success_test.sh`（8083） | 通过 | Stub 成功路径 + parentChunkId/matchedChildIds decimal string |
| 2026-06-25 | Docker | `query_stub_failure_test.sh`（8083） | 失败 | 见下“未通过项与风险” |
| 2026-06-25 | Docker | `docker_readiness_test.sh` | 部分未执行 | 测试 4 已修正写入指向 8083；完整运行留待独立验收（见下“未通过项与风险”） |
| 2026-06-25 | Docker | `query_deepseek_acceptance_test.sh` | 未执行 | 无 DeepSeek 凭据/额度；脚本目标已同步到 smoke 实例 |

### 未通过项与风险

- `query_stub_failure_test.sh` Docker 回归未通过。失败路径（LLM 不可用 → 502）已由
  `UserQueryControllerComponentTest.ExceptionMapping.llmUnavailableReturns502` 组件测试覆盖；
  plan_16 未修改 Stub 失败模式或异常映射，判定为 Docker 回归的环境/时序问题（失败模式重建 +
  readiness 轮询），非 plan_16 引入。建议独立验收在干净环境复跑。
- `docker_readiness_test.sh` 测试 4（AdminRag 写入）已由 8082 改指向 8083 并在写入前确保
  rag-service-smoke 启动；其完整套件含测试 6 数据库故障恢复，会反复重启服务，触发**预存的**
  schema 并发初始化竞态（两实例共享 rag schema 且 `spring.sql.init.mode=always`、`schema.sql`
  非幂等；plan_16 未改 schema.sql），非 plan_16 引入。该脚本的 plan_16 相关断言
  （8082 默认 smoke 端点 404、8083 smoke 端点 200、写入成功）已被 `smoke_default` /
  `smoke_endpoints` / `admin_rag_contract` 聚焦脚本覆盖。完整运行留待独立验收。
- 重建 `rag-service` 与 `rag-service-smoke` 同时进行时会触发上述 schema 竞态导致其一启动失败；
  串行重启可恢复。属既有部署脆弱性，不在本计划范围。

### 独立验收结论（2026-06-25，未参与实现的验收 session）

**结论：通过验收，Plan 标记完成。**

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-25 | 本地 Java 21 / Gradle 9.4.1 | `./gradlew check` | 通过 | BUILD SUCCESSFUL |
| 2026-06-25 | 本地 | `./gradlew :crag-rag-service:cleanTest :crag-rag-service:test`（强制重跑取新鲜证据） | 通过 | 99 test classes，356 tests，0 failures / 0 errors / 0 skipped（含 ArchitectureTest） |
| 2026-06-25 | 本地 | `python3 scripts/validate_plans.py --strict --verify-git` | 通过 | 0 error；24 条 P101 警告均为历史 v2 Plan，与本计划无关 |
| 2026-06-25 | 本地 | `python3 -m unittest scripts.tests.test_validate_module_dependencies scripts.tests.test_validate_framework_dependencies scripts.tests.test_validate_constraints -v` | 通过 | 55 tests OK |
| 2026-06-25 | 本地 | rg 残留检查（16.1/16.2/16.4） | 通过 | settings/rag-service build 无被移除 subproject 引用；rag-service src 无 `ai.cerbur.crag.api`/旧 URI；http 脚本与 README 无 `/api/v1/(admin/rag\|query\|test)` |
| 2026-06-25 | Docker Engine 29.5.2 | `smoke_default_test.sh`（8082 默认 Profile） | 通过 | 四个 `/api/v1/smoke/test/**` 端点全部 404，验证 `@Profile("smoke")` 门控 |
| 2026-06-25 | Docker | `smoke_endpoints_test.sh`（8083 smoke Profile） | 通过 | `/smoke` `/chunk` `/retrieval` 均 code=0 |
| 2026-06-25 | Docker | `admin_rag_contract_test.sh`（8083） | 通过 | 写入成功（docId/parentChunkIds decimal string）、Bean Validation 400/40001、未知路径 404/40401 |
| 2026-06-25 | Docker | `retrieval_evidence_test.sh`（8083） | 通过 | parent evidence matchedChildIds 与真实 child 检索交叉验证一致；稳定排序 |
| 2026-06-25 | Docker | `query_stub_success_test.sh`（8083） | 通过 | AdminRag 写入 → 索引 → Query 全链路；固定 Stub 答案、parentChunkId/matchedChildIds decimal string |
| 2026-06-25 | Docker | `query_stub_failure_test.sh`（8083） | 失败（预存缺陷，非本计划引入） | 见下「query_stub_failure 根因复核」 |
| 2026-06-25 | Docker | `docker_readiness_test.sh` | 部分覆盖（见下说明） | plan_16 相关断言由上述聚焦脚本覆盖；DB 故障恢复/持久化（test 6/7）属 plan_10/plan_15 范围且触发预存 schema 竞态，未全量执行以避免拆栈 |
| 2026-06-25 | — | `query_deepseek_acceptance_test.sh` | 未执行 | 无 DeepSeek 凭据/额度；本计划未触及 DeepSeek 供应商配置/SDK/协议，真实调用非完成门槛 |

**query_stub_failure 根因复核（独立验证）：**

执行 session 记录该脚本 Docker 回归失败并判断为环境/时序问题、非本计划引入。独立验收复跑确认失败，并定位到**确切的预存测试设计缺陷**，而非时序：

- `UserQueryService.answer()` 在检索证据为空时短路返回 `"知识库证据不足"`（HTTP 200、code=0），**不调用 LLM**（见 `UserQueryService`「Empty context — no LLM call」分支）。
- 脚本 Phase 1 直接以 `{"question":"测试问题"}` 发起查询，未先写入任何证据文档；该泛化问题命不中已索引文档，evidence 为空 → 短路返回 200，永远到不了 `llmClient.generate()`，故 Stub failure 模式从不触发，无法产生 502。
- 独立复跑验证：先经 `/api/v1/smoke/admin/rag` 写入含唯一 `verify-fail-*` 标识的文档并等待索引，再在 failure 模式下查询同一问题，得到 `{"code":50201,"result":null,"success":false}` **HTTP 502** —— 证明失败路径在 evidence 存在时完全正确。
- 失败路径的异常→HTTP 映射另由 `UserQueryControllerComponentTest.llmUnavailableReturns502`（断言 502/50201）覆盖，该测试在本次 356 tests 中通过。
- `git diff --stat dfe330e 2ff799c2` 显示 plan_16 对 `UserQueryService.java`、`StubLlmAdapter.java` **0 行改动**（纯文件迁移），对 `GlobalExceptionHandler.java` 仅包名 `api→smoke` + `@Profile("smoke")`（4 行机械变更，异常映射 `@ExceptionHandler` 未改动）。

结论：`query_stub_failure_test.sh` 失败是**预存测试脚本缺陷**（Phase 1 未 seed evidence），与本计划无关；plan_16 对该脚本仅做 URL path 迁移（16.4 非目标明确不修改脚本逻辑），迁移后的路径 `/api/v1/smoke/query` 工作正常。建议后续以独立 hotfix 修正脚本的 evidence 准备（参照 `plan_10.hotfix_1` 先例），不阻塞本计划验收。

**docker_readiness 复核：**

脚本中 plan_16 相关断言（test 3：8082 默认 `/api/v1/smoke/test/**` → 404；test 5：8083 smoke → 200；test 4：经 8083 `/api/v1/smoke/admin/rag` 写入）已被 `smoke_default_test.sh`、`smoke_endpoints_test.sh`、`admin_rag_contract_test.sh` 聚焦脚本覆盖且通过，并实测 `rag-service`(8082) 与 `rag-service-smoke`(8083) 并存 healthy。test 6（DB 故障恢复）与 test 7（持久化）验证 plan_10/plan_15 的基础设施行为，不受 plan_16 影响，且触发预存 schema 并发竞态（`spring.sql.init.mode=always` + 非幂等 `schema.sql` + 两实例共享 rag schema）；全量执行会 `docker compose down` 拆除运行栈且不新增 plan_16 证据，故未全量执行。

**关键证据：**

- 运行中的 `rag-service`(8082) 与 `rag-service-smoke`(8083) 镜像即为 plan_16 代码：旧代码服务 `/api/v1/admin/rag` 等正式路径，而当前镜像默认 8082 对 `/api/v1/smoke/test/**` 返回 404、smoke 8083 返回 200，正是 plan_16 的 `@Profile("smoke")` 门控结果。
- 六个被合并 subproject（`crag-storage`/`crag-retrieval`/`crag-query`/`crag-ingestion`/`crag-api`/`crag-smoke`）已从 `settings.gradle.kts` 移除且无 `build.gradle.kts` 残留；`ai.cerbur.crag.api` 生产包不存在；`storage`/`retrieval`/`query`/`ingestion` package 名保持稳定。
- Mockito MockMaker 资源位于 `crag-rag-service/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`，内容为 `mock-maker-inline`（与变更记录一致）。
- 工作区无 plan_16 未提交变更（验收提交前）。

## 阻塞记录

无。发生阻塞时记录原因、当前进度、解除条件、解除方、下一步与日期。

## 废弃任务记录

无。任务废弃时记录原因、日期及替代任务或决策。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-25 | 创建计划 | 用户确认 RAG module 完整收口方案 A，并要求按 Plan workflow 新建计划 | 初始范围为 RAG 内部 module 合并、legacy HTTP smoke 化、脚本 URL 迁移、约束和验证同步 |
| 2026-06-25 | 16.1 同时并入 crag-api / crag-smoke 源码（保留原包名）并从 settings 移除全部六个 subproject | crag-rag-service 的 jar 被禁用（Boot 应用），其他 subproject 无法通过 project() 消费其 class；crag-api / crag-smoke 仍 project() 引用已删除业务模块会使 Gradle 配置失败。为保证每任务可独立验证，六个模块源码统一在 16.1 并入 | 16.1 提交包含六个模块源码迁移；smoke 包名重命名、`@Profile("smoke")` 与 URI 变换在 16.2 完成 |
| 2026-06-25 | 16.1 将 rag-service Mockito MockMaker 由 `mock-maker-subclass` 改为 `mock-maker-inline` | 合并 source set 后 subclass 配置覆盖 Mockito 5+ 默认 inline，使依赖 inline 才能 mock Spring AI 类型的 DeepSeek 适配器测试失败；与实现文件地图“保留 inline mock maker 资源”一致 | storage/retrieval/ingestion 测试仍通过（inline 是 subclass 超集） |
| 2026-06-25 | 16.2 同时更新 ModuleBoundaryArchitectureTest（原划入 16.3） | 包重命名后 `ai.cerbur.crag.api..` 为空触发 ArchUnit empty-should 失败，且 Gradle 9.x `--tests` glob 会连带运行架构测试，导致 16.2 验证命令无法单独通过 | 16.3 改为专注约束文档与 Python 校验器 |
| 2026-06-25 | 16.5 修复 `docker/java-service.Dockerfile`（删除六个已合并模块的 COPY）并将写/查询 HTTP 回归脚本目标指向 smoke 实例（8083 / rag-service-smoke） | 验证发现：Dockerfile 仍 COPY 已删模块致镜像构建失败；写/查询 HTTP 已 smoke-only，默认 rag-service(8082) 返回 404，脚本须指向 rag-service-smoke | plan 原定“仅替换 URL path / 不改服务端口”不足以覆盖端点迁到 smoke 实例的后果，此处为必要修正 |
