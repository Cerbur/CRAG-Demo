---
workflow_version: 2
plan_id: plan_9.hotfix_1
type: hotfix
parent_plan: plan_9
status: in_progress
owner: parent-agent
created: 2026-06-19
updated: 2026-06-19
---

# plan_9.hotfix_1 — GlobalExceptionHandler HTTP 状态码修正

## 背景与目标

`plan_9` Docker 冒烟验收中发现 `GlobalExceptionHandler` 的兜底 `@ExceptionHandler(Exception.class)` 返回类型为 `Response<?>`（非 `ResponseEntity`），导致所有未预期异常以 HTTP 200 返回。Spring 的 `NoResourceFoundException`（404）被吞为 HTTP 200 + `code:500`，使 `smoke_default_test.sh` 无法通过 HTTP 404 判断 smoke Profile 隔离生效。

本 hotfix 修正 HTTP 语义：兜底异常返回 500，`NoResourceFoundException` 显式返回 404，确保 Docker 冒烟流程可自动化验证。

## 范围

- `GlobalExceptionHandler.handleInternal` 返回类型改为 `ResponseEntity<Response<?>>`，设置 HTTP 500。
- 新增 `NoResourceFoundException` → HTTP 404 的显式 `@ExceptionHandler`。
- Docker 冒烟回归：默认 + smoke Profile 全流程，自动化脚本 PASS。

## 非目标

- 不修改其他 `@ExceptionHandler` 方法。
- 不新增新的错误码或 `ResponseCode` 枚举值。
- 不修改 `smoke_default_test.sh` 脚本逻辑。
- 不清理 `crag-admin/` 目录残留（无源码，不属本 hotfix）。

## 前置依赖

- **执行前置 Plan**：`plan_9`
- `plan_9` 已完成（6/6），`smoke` Profile 隔离已生效。
- `crag-api` 模块 + `GlobalExceptionHandler` 已在 `plan_9` 9.2 中建立。

## 文件边界

- `crag-api/src/main/java/ai/cerbur/crag/api/controller/advice/GlobalExceptionHandler.java`
- `plan/plan_9/plan_9.hotfix_1.md`
- `plan/index/README.md`

## 关键决策

- `handleInternal` 改为返回 `ResponseEntity.status(500).body(...)`，保持 `code=INTERNAL_ERROR` 不变。
- `NoResourceFoundException` 单独映射，返回 HTTP 404 + `code=NOT_FOUND`（使用现有 `ResponseCode.NOT_FOUND` 或等价语义码）。
- 不做更广泛的 HTTP 状态码重构：其他 handler 虽也返回 `Response<?>`，但所覆盖的异常类型（校验失败、参数错误）语义上可接受默认 200 + `code` 模式；本 hotfix 只修正明确扭曲 HTTP 语义的兜底和 404。
- Docker 验证覆盖默认和 smoke 两种模式，确认 `smoke_default_test.sh` 和 `smoke_endpoints_test.sh` 均 PASS。

## 未决问题

无。

## 风险与回滚

- 改动极小，只涉及一个文件的返回类型和新增一个 handler。回滚：`git revert` 即可。
- 若有外部调用方依赖 HTTP 200 + `code:500` 的兜底行为，升级后需适配。当前无外部调用方，风险可接受。

## 测试与验证计划

- 架构测试：`./gradlew :crag-app:test --tests '*ArchitectureTest'`
- 全量测试：`./gradlew test`
- 全量检查：`./gradlew check`
- Plan 校验：`python3 scripts/validate_plans.py --strict`
- Docker 默认冒烟：`bash scripts/tests/http/smoke_default_test.sh http://localhost:8080`
- Docker smoke 冒烟：`bash scripts/tests/http/smoke_endpoints_test.sh http://localhost:8081`

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 9.hotfix_1.1 | 修正 handleInternal 返回 ResponseEntity + HTTP 500 | ✅ 待验收 | pending | — |
| 9.hotfix_1.2 | 新增 NoResourceFoundException → HTTP 404 显式映射 | ✅ 待验收 | pending | — |
| 9.hotfix_1.3 | Docker 冒烟回归验证（默认 + smoke Profile） | ⏳ 待开始 | — | — |

整体进度：0 / 3（0%）

## 9.hotfix_1.1 修正 handleInternal 返回 ResponseEntity + HTTP 500

**目标**：让兜底异常处理返回正确的 HTTP 500 状态码。  
**前置任务**：无  
**范围**：将 `handleInternal` 返回类型从 `Response<?>` 改为 `ResponseEntity<Response<?>>`，使用 `ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(...)` 构造响应。  
**非目标**：不修改日志逻辑，不改变 `ResponseCode.INTERNAL_ERROR` 的 code 值。  
**验收标准**：访问不存在路径返回 HTTP 500（非 200）；`Response` body 仍为 `success=false, code=500`。  
**验证方式**：`curl -s -w "\nHTTP:%{http_code}" http://localhost:8080/some-random-path` 确认 HTTP 500。  
**涉及文件**：`crag-api/src/main/java/ai/cerbur/crag/api/controller/advice/GlobalExceptionHandler.java`

## 9.hotfix_1.2 新增 NoResourceFoundException → HTTP 404 显式映射

**目标**：让 Spring 的 `NoResourceFoundException` 显式返回 HTTP 404，而非通过兜底 handler 转为 500。  
**前置任务**：9.hotfix_1.1  
**范围**：在 `ResponseCode` 枚举中新增 `NOT_FOUND(404)`；新增 `@ExceptionHandler(NoResourceFoundException.class)`，返回 `ResponseEntity.status(404).body(Response.error(ResponseCode.NOT_FOUND))`。  
**非目标**：不改变 Spring Boot 默认的静态资源 404 行为；不新增其他错误码。  
**验收标准**：默认模式下 `/api/v1/test/smoke` 返回 HTTP 404（非 500）；`smoke_default_test.sh` PASS。  
**验证方式**：`curl -s -w "\nHTTP:%{http_code}" http://localhost:8080/api/v1/test/smoke` 确认 HTTP 404。  
**涉及文件**：`crag-api/src/main/java/ai/cerbur/crag/api/controller/advice/GlobalExceptionHandler.java`、`crag-common/src/main/java/ai/cerbur/crag/common/dto/result/ResponseCode.java`

## 9.hotfix_1.3 Docker 冒烟回归验证（默认 + smoke Profile）

**目标**：在 Docker 真实环境中验证修正后的 HTTP 状态码，确认两个自动化脚本均 PASS。  
**前置任务**：9.hotfix_1.1、9.hotfix_1.2  
**范围**：重建 `app` 和 `app-smoke` 镜像，依次执行默认和 smoke 冒烟脚本。  
**非目标**：不修改测试脚本内容。  
**验收标准**：`smoke_default_test.sh` PASS（所有 smoke 端点返回 404）；`smoke_endpoints_test.sh` PASS（所有诊断端点返回 code=0）。  
**验证方式**：依次执行两项脚本，确认退出码均为 0。  
**涉及文件**：无代码变更，纯验证。

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-19 | 本机 macOS | `curl -s -w "\nHTTP:%{http_code}" http://localhost:8080/api/v1/test/smoke` | 通过 | HTTP 404 + code:404 |
| 2026-06-19 | 本机 macOS | `bash scripts/tests/http/smoke_default_test.sh http://localhost:8080` | 通过 | 4/4 PASS |
| 2026-06-19 | 本机 macOS | `bash scripts/tests/http/smoke_endpoints_test.sh http://localhost:8081` | 通过 | 3/3 PASS |
| 2026-06-19 | 本机 macOS, JDK 25, Gradle 9.4.1 | `./gradlew test` | 通过 | BUILD SUCCESSFUL |
| 2026-06-19 | 本机 macOS, JDK 25, Gradle 9.4.1 | `./gradlew check` | 通过 | 0 error |
| 2026-06-19 | 本机 | `python3 scripts/validate_plans.py --strict` | 通过 | 0 error, 0 warning |

## 阻塞记录

无。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-19 | 创建 plan_9.hotfix_1 | plan_9 Docker 冒烟发现 GlobalExceptionHandler 兜底返回错误 HTTP 状态码 | 新增 3 项任务 |
