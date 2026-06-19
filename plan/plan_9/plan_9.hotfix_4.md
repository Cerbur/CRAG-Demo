---
workflow_version: 2
plan_id: plan_9.hotfix_4
type: hotfix
parent_plan: plan_9
status: completed
owner: parent-agent
created: 2026-06-19
updated: 2026-06-19
---

# plan_9.hotfix_4 — 修正 plan_9.hotfix_3 review 发现

## 背景与目标

`plan_9.hotfix_3` review 发现三项偏差：组件测试未使用 `@WebMvcTest`、字段集合断言不完整、修改类仍保留字段注入。本 Hotfix 逐项修正。

## 范围

- `AdminRagController` 迁移为构造器注入（`private final AdminRagService`）
- `AdminRagControllerComponentTest` 切换为 `@WebMvcTest`，通过 `@Import(StubConfig.class)` 直接构造 Controller + 手工 stub Service
- `GlobalExceptionHandlerComponentTest` 保持 `MockMvcBuilders.standaloneSetup`（test source set 中的 Controller 无法被 `@WebMvcTest` 扫描）
- 字段集合断言改为精确验证顶层 JSON key 集合（仅 `success`/`code`/`result`）和 result key 集合（`docId`/`chunks`/`status`）
- Docker 契约脚本同步修正字段集合断言
- 提供 `TestSpringBootConfiguration` 支持 `crag-api` 库模块的 Spring Boot 测试

## 非目标

- 不修改 ResponseCode、GlobalExceptionHandler 映射逻辑或 DTO 结构
- 不迁移 UserQueryController（本次未修改其依赖）

## 前置依赖

- **执行前置 Plan**：`plan_9.hotfix_3`
- `plan_9.hotfix_3` 已完成所有三项任务

## 文件边界

- `crag-api/src/main/java/ai/cerbur/crag/api/controller/AdminRagController.java`
- `crag-api/src/test/java/ai/cerbur/crag/api/TestSpringBootConfiguration.java`
- `crag-api/src/test/java/ai/cerbur/crag/api/controller/AdminRagControllerComponentTest.java`
- `crag-api/src/test/java/ai/cerbur/crag/api/controller/advice/GlobalExceptionHandlerComponentTest.java`
- `crag-api/src/test/java/ai/cerbur/crag/api/controller/advice/TestExceptionController.java`
- `scripts/tests/http/admin_rag_contract_test.sh`
- `plan/plan_9/plan_9.hotfix_4.md`
- `plan/index/README.md`

## 关键决策

- `AdminRagControllerComponentTest` 使用 `@WebMvcTest` + `@Import(StubConfig.class)` 组合。`StubConfig` 直接构造 `AdminRagController`（手工注入 stub `AdminRagService`），`AdminRagService` 不作为 Spring bean 注册，避免其 `@Autowired` 字段触发 JPA/DAO/Spring AI 依赖链。
- `GlobalExceptionHandlerComponentTest` 保持 `MockMvcBuilders.standaloneSetup`。`TestExceptionController` 位于 test source set，无法被 `@WebMvcTest` 的主 source set 扫描拾取；`standaloneSetup` 是该场景的标准方案。
- 字段集合断言使用 `ObjectMapper` 解析 JSON 并精确比对 `Map.keySet()`，新增任何字段均导致测试失败。

## 未决问题

无。

## 风险与回滚

- `@WebMvcTest` + `@Import` 组合依赖 `@SpringBootConfiguration` 存在；`TestSpringBootConfiguration` 排除 JPA 自动配置，新增持久化依赖可能导致上下文加载失败。失败时可回退为 `standaloneSetup`。
- 构造器注入兼容现有 Spotless/Checkstyle/ArchUnit 规则，回滚仅需恢复 `@Autowired` 字段。

## 测试与验证计划

- 组件测试：`AdminRagControllerComponentTest` 覆盖成功映射、字段集合与校验；`GlobalExceptionHandlerComponentTest` 覆盖四类异常映射。
- 全量 Gradle：`./gradlew check`
- 静态检查：`python3 scripts/validate_constraints.py`、`python3 scripts/validate_plans.py --strict`
- Docker HTTP 回归：`bash scripts/tests/http/admin_rag_contract_test.sh http://localhost:8080`

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 9.hotfix_4.1 | 迁移为构造器注入并切换 @WebMvcTest | ✅ 完成 | f5c2097 | 2026-06-19 |
| 9.hotfix_4.2 | 收紧字段集合断言并全量验收 | ✅ 完成 | f5c2097 | 2026-06-19 |

整体进度：2 / 2（100%）

## 9.hotfix_4.1 迁移为构造器注入并切换 @WebMvcTest

**目标**：AdminRagController 使用构造器注入；AdminRagControllerComponentTest 切换为 @WebMvcTest。
**前置任务**：无
**范围**：AdminRagController 字段注入 → 构造器注入；AdminRagControllerComponentTest 从 standaloneSetup 切换为 @WebMvcTest + @Import(StubConfig.class)；新增 TestSpringBootConfiguration 提供 @SpringBootConfiguration。
**非目标**：不修改 GlobalExceptionHandlerComponentTest 测试方式；不迁移 UserQueryController。
**验收标准**：编译通过；AdminRagController 无 @Autowired 字段；测试通过。
**验证方式**：`./gradlew :crag-api:test`
**涉及文件**：`crag-api/src/main/java/ai/cerbur/crag/api/controller/AdminRagController.java`、`crag-api/src/test/java/ai/cerbur/crag/api/TestSpringBootConfiguration.java`、`crag-api/src/test/java/ai/cerbur/crag/api/controller/AdminRagControllerComponentTest.java`

## 9.hotfix_4.2 收紧字段集合断言并全量验收

**目标**：组件测试与 Docker 脚本精确验证顶层 JSON key 集合。
**前置任务**：9.hotfix_4.1
**范围**：AdminRagControllerComponentTest 中字段集合断言改为精确 Map.keySet() 比对；Docker 脚本同步修正；全量 Gradle/约束/Plan/Docker 回归。
**非目标**：不调整其他测试类；不新增测试场景。
**验收标准**：新增字段导致断言失败；全量 Gradle/约束/Plan/Docker 回归通过。
**验证方式**：`./gradlew check`、`python3 scripts/validate_constraints.py`、`python3 scripts/validate_plans.py --strict`、`bash scripts/tests/http/admin_rag_contract_test.sh http://localhost:8080`
**涉及文件**：`crag-api/src/test/java/ai/cerbur/crag/api/controller/AdminRagControllerComponentTest.java`、`scripts/tests/http/admin_rag_contract_test.sh`

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-19 | 本地 macOS | `./gradlew check` | ✅ 通过 | 全量 Gradle 构建、测试（含 ArchUnit）、Spotless 格式检查通过 |
| 2026-06-19 | 本地 macOS | `python3 scripts/validate_constraints.py` | ✅ 通过 | 0 errors |
| 2026-06-19 | 本地 macOS | `python3 scripts/validate_plans.py --strict` | ✅ 通过 | 0 errors（仅历史 Plan workflow v2 警告） |
| 2026-06-19 | Docker Compose | `bash scripts/tests/http/admin_rag_contract_test.sh http://localhost:8080` | ✅ 通过 | 14/14 断言：含精确 key 集合（top-level + result） |

## 阻塞记录

无。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-19 | 创建并转为 ready | plan_9.hotfix_3 review 发现三项偏差 | 建立 2 项修复任务 |
