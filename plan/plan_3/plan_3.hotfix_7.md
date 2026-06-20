---
workflow_version: 3
plan_id: plan_3.hotfix_7
type: hotfix
parent_plan: plan_3
status: in_progress
created: 2026-06-21
updated: 2026-06-21
---

# plan_3.hotfix_7 — 恢复 @Autowired 默认依赖注入规范

## 背景与目标

`plan_3.hotfix_1` 已明确 Spring 依赖注入默认使用 `@Autowired` 字段注入。`plan_3.hotfix_6` 在重写工程规范时将该规则反转为构造器注入，并由后续 `plan_9.hotfix_4`、`plan_13` 和 `plan_7` 扩散到生产代码。本 Hotfix 恢复原始项目约定，并修复由错误规范直接引入的非必要构造器注入。

## 范围

- 修正 `constraints/code-style.md` 的依赖注入规则。
- 修正仍在执行的 `plan_7` 中构造器注入决策。
- 将受错误规则影响的 Controller 与 Service 恢复为 `@Autowired` 字段注入。
- 保留配置工厂、值对象和必须显式构造的非 Spring 注入场景。
- 增加静态回归检查，防止 Spring Controller/Service 再次使用依赖构造器。

## 非目标

- 不改写已完成 Plan 的历史事实或完成状态。
- 不修改通过 `@Bean` 工厂显式创建的 Adapter、配置属性和值对象构造器。
- 不调整业务逻辑、HTTP 契约、持久化行为或 Docker 部署。
- 不迁移测试类自身的 `@Autowired` 字段。

## 前置依赖

- **执行前置 Plan**：`plan_3`
- `plan_3.hotfix_1`、`plan_3.hotfix_6`、`plan_9.hotfix_4` 与 `plan_13` 已完成。

## 文件边界

- `constraints/code-style.md`
- `crag-api/src/main/java/ai/cerbur/crag/api/controller/**`
- `crag-api/src/test/java/ai/cerbur/crag/api/controller/AdminRagControllerComponentTest.java`
- `crag-api/src/test/java/ai/cerbur/crag/api/controller/UserQueryControllerComponentTest.java`
- `crag-ingestion/src/main/java/ai/cerbur/crag/ingestion/api/AdminRagService.java`
- `crag-query/src/main/java/ai/cerbur/crag/query/api/UserQueryService.java`
- `crag-query/src/test/java/ai/cerbur/crag/query/api/UserQueryServiceTest.java`
- `crag-app/src/test/java/ai/cerbur/crag/app/arch/**`
- `plan/plan_7/plan_7.md`
- `plan/plan_3/plan_3.hotfix_7.md`
- `plan/index/README.md`

## 关联范围与规模说明

- 主要责任归属 `plan_3.hotfix_6` 的全局代码风格反转；关联 `plan_9.hotfix_4`、`plan_13` 与执行中的 `plan_7`。
- 变更跨 API、Ingestion、Query 三个业务模块，但只机械恢复同一依赖注入规则，使用两个任务即可完成，不升级为主 Plan。
- 任务 3.hotfix_7.1 与 3.hotfix_7.2 紧密耦合，共享实现提交 `eddda3c`：前者恢复规范与 Plan 决策，后者同步修改生产装配、组件测试和架构护栏；拆分提交会产生规范与实现暂时冲突，二者共同构成同一可回滚修复。

## 关键决策

- Spring 管理的生产组件默认使用 `@Autowired` 字段注入。
- 非必要不新增依赖构造器；框架、配置工厂、不可变值对象或显式手工构造确有需要时可保留。
- 已完成 Plan 只作为根因证据保留，不追改历史描述；执行中的 `plan_7` 同步修正当前决策。
- 使用 ArchUnit 静态检查锁定 Controller 与 Service 的默认注入方式。

## 未决问题

无。

## 风险与回滚

- 字段注入会移除部分 `final` 依赖字段：这是恢复项目既定风格的预期变化，不改变 Spring Bean 运行时依赖。
- 纯单元测试不能再直接调用生产构造器：改用 Mockito 字段注入，保持测试隔离。
- 如 Spring Context 或测试装配失败，可回滚本 Hotfix 的实现提交；不涉及数据、配置或公共接口迁移。

## 测试与验证计划

- `python3 scripts/validate_constraints.py`
- `python3 scripts/validate_plans.py --strict`
- `./gradlew spotlessCheck`
- `./gradlew :crag-query:test :crag-ingestion:test :crag-api:test :crag-app:test --tests '*ArchitectureTest'`
- `bash scripts/tests/http/admin_rag_contract_test.sh`
- `bash scripts/tests/http/query_stub_success_test.sh`
- `git diff --check`

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 3.hotfix_7.1 | 恢复依赖注入规范与计划决策 | 🔄 进行中 | eddda3c | — |
| 3.hotfix_7.2 | 修复生产代码并增加静态回归检查 | 🔄 进行中 | eddda3c | — |

整体进度：0 / 2（0%）

## 3.hotfix_7.1 恢复依赖注入规范与计划决策

**目标**：将依赖注入规范恢复为 `@Autowired` 字段注入默认，并消除执行中 Plan 的冲突决策。  
**前置任务**：无  
**范围**：修改代码风格约束、`plan_7` 当前决策和计划索引；记录根因提交与 Hotfix 中断关系。  
**非目标**：不改写已完成 Plan 的历史描述和状态。  
**验收标准**：规范明确默认 `@Autowired`；`plan_7` 不再要求构造器注入；索引将本 Hotfix 放在执行队首。  
**验证方式**：运行约束校验、严格 Plan 校验和文本检索。  
**涉及文件**：`constraints/code-style.md`、`plan/plan_7/plan_7.md`、`plan/plan_3/plan_3.hotfix_7.md`、`plan/index/README.md`

## 3.hotfix_7.2 修复生产代码并增加静态回归检查

**目标**：将错误规范引入的非必要构造器注入恢复为 `@Autowired`，并用静态检查防止复发。  
**前置任务**：3.hotfix_7.1  
**范围**：修复 AdminRag/UserQuery Controller 与 Service；调整 UserQueryService 单元测试装配；增加 Controller/Service 注入风格 ArchUnit 断言。  
**非目标**：不修改配置工厂显式构造的 LLM Adapter，不改变业务行为。  
**验收标准**：目标生产类使用 `@Autowired` 字段且无依赖构造器；相关单元、组件、架构测试和格式检查通过。  
**验证方式**：执行测试与验证计划中的 Gradle、Spotless 和 diff 检查命令。  
**涉及文件**：`crag-api/src/main/java/ai/cerbur/crag/api/controller/**`、`crag-api/src/test/java/ai/cerbur/crag/api/controller/AdminRagControllerComponentTest.java`、`crag-api/src/test/java/ai/cerbur/crag/api/controller/UserQueryControllerComponentTest.java`、`crag-ingestion/src/main/java/ai/cerbur/crag/ingestion/api/AdminRagService.java`、`crag-query/src/main/java/ai/cerbur/crag/query/api/UserQueryService.java`、`crag-query/src/test/java/ai/cerbur/crag/query/api/UserQueryServiceTest.java`、`crag-app/src/test/java/ai/cerbur/crag/app/arch/**`

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-21 | macOS / Java 25 / Gradle 9.4.1 | `python3 scripts/validate_constraints.py` | 通过 | 约束校验 0 error |
| 2026-06-21 | macOS / Python 3 | `python3 scripts/validate_plans.py --strict` | 通过 | Plan 校验 0 error；24 个历史兼容 warning |
| 2026-06-21 | macOS / Java 25 / Gradle 9.4.1 | `./gradlew :crag-query:test :crag-ingestion:test :crag-api:test :crag-app:test --tests '*ArchitectureTest' spotlessCheck` | 通过 | 50 个 task，12 executed、38 up-to-date；相关单元、组件、架构与格式检查通过 |
| 2026-06-21 | Git | `git diff --check` | 通过 | 无空白错误 |
| 2026-06-21 | 独立验收 / Git | `git show --stat --summary eddda3c`、`git show eddda3c -- constraints/code-style.md crag-app/src/test crag-api/src/test crag-api/src/main crag-ingestion/src/main crag-query/src/main` | 失败 | ArchUnit 无条件禁止所有 `@Service` / `@RestController` 带参构造器，与代码风格允许框架、工厂、不可变对象、手工构造或明确测试与设计收益例外冲突；实现提交还修改了两个未列入 Plan 文件边界的 Controller 组件测试。 |
| 2026-06-21 | 独立验收 / Plan 与 Git | `git show --stat eddda3c`、任务提交栏与关联范围核对 | 失败 | 任务 3.hotfix_7.1、3.hotfix_7.2 共享 `eddda3c`，交接时未按提交协议说明紧密耦合；本次退回时已补录事实说明，仍需执行 session 修正实现与验证缺口后重新交接。 |
| 2026-06-21 | 独立验收 / Docker Compose | `bash scripts/tests/http/admin_rag_contract_test.sh`、`bash scripts/tests/http/query_stub_success_test.sh` | 未执行 | `eddda3c` 修改 Controller 与 Spring Bean 装配，按测试工作流必须执行受影响业务链路的 Docker HTTP 回归；交接验收记录仅包含 Gradle/ArchUnit/Spotless，缺少两条真实运行时回归证据。本轮已由静态审查确认阻断，交由执行 session 补跑并记录。 |
| 2026-06-21 | 独立验收 / Plan 索引 | `plan_3.hotfix_7` YAML、当前执行队列与当前验收队列交叉核对 | 失败 | `verifying` Hotfix 同时出现在执行和验收队列，违反双队列互斥要求。 |

## 阻塞记录

无。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-21 | 创建并转为 ready | `plan_3.hotfix_6` 错误反转既有依赖注入规范，后续 Plan 按错误规则扩散 | 中断 `plan_7`，先恢复规范、代码和静态护栏 |
| 2026-06-21 | 开始执行两个修复任务 | 计划门槛通过，进入规范、代码、测试与架构护栏修复 | Hotfix 转为 `in_progress` |
| 2026-06-21 | 完成实现、自测并交接验收 | 实现提交 `eddda3c` 已创建，规定验证全部通过 | 两项任务转为待验收，Hotfix 转为 `verifying` |
| 2026-06-21 | 独立验收失败并退回进行中 | 双队列冲突；ArchUnit 抹除代码风格明确例外；Controller/Bean 装配变更缺少 Docker HTTP 回归；两个组件测试遗漏于文件边界；共享提交缺少紧密耦合说明 | 两项任务与 Hotfix 退回 `in_progress`；从验收队列移除并保留执行队首，修正实现、补齐回归证据后重新交接 |
