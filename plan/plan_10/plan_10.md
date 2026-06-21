---
workflow_version: 3
plan_id: plan_10
type: main
status: in_progress
created: 2026-06-19
updated: 2026-06-22
---

# plan_10 — Docker 正式健康检查与部署验收

## 背景与目标

CRAG-Demo 已具备单一 `docker-compose.yml`、默认 `app`、显式 `app-smoke`、PostgreSQL、模型初始化、Python Sidecar、非 root 运行和本地持久化。`plan_7` 已完成 Query/LLM 配置，`plan_9` 已完成 Smoke 隔离，`plan_12` 已将 `constraints/docker-structure.md` 重写为部署硬约束、当前实现索引和已知偏差。

当前剩余缺口集中在 Spring Boot 应用本身：`app` 与 `app-smoke` 没有正式健康检查，Compose 只能等待数据库和 Sidecar，不能判断应用是否真正可服务；Plan 10 草稿仍包含 Plan 12 已完成的治理范围，且部分 Smoke 启动描述与当前 `app-smoke` Compose Profile 实现不一致。

本计划不再重复全面 Docker 文档治理，而是完成三个结果：

1. 通过 Spring Boot Actuator 建立最小暴露面的 liveness/readiness 健康能力。
2. 让 `app` 与 `app-smoke` 使用 readiness 进入 Compose 健康状态，并支持二者同时运行。
3. 以自动化 Docker 回归证明默认/Smoke 模式、数据库故障恢复和持久化行为，并同步唯一事实文档。

## 范围

- 在 `crag-app` 接入 Spring Boot Actuator，只暴露 `health`。
- 建立 `/actuator/health/liveness` 与 `/actuator/health/readiness`。
- liveness 只反映 JVM/Spring 存活；readiness 反映 Spring 应用与数据库可服务状态。
- 为 `app` 与 `app-smoke` 增加相同的 Compose readiness healthcheck。
- 在应用运行镜像中显式安装健康检查所需的 `curl`，不依赖基础镜像隐含工具。
- 保持 `app:8080` 与 `app-smoke:8081` 可同时运行。
- 保持 `db:5432` 与 `sidecar:8001` 对宿主机开放，用于本地学习和诊断。
- 新增自动化 Docker readiness 回归脚本，覆盖健康端点、最小暴露面、双 App 并存、数据库故障恢复和 bind mount 持久化。
- 同步 README、Docker 约束、Smoke 包结构说明和计划索引。

## 非目标

- 不新增业务健康 Controller。
- 不在健康检查中执行 Retrieval、Embedding、Rerank、LLM 或其他业务请求。
- 不将 Sidecar 可用性纳入 App readiness；Sidecar 继续由自身 `/health` 和 Compose healthcheck 独立判断。
- 不改变 Query、Retrieval、Embedding、Rerank、Sidecar 或正式 HTTP API 协议。
- 不新增第二份 Compose 文件，不用环境变量覆盖默认 `app` 来模拟 Smoke 模式。
- 不引入 Kubernetes、Helm、高可用、生产密钥管理、生产监控或生产部署承诺。
- 不增加资源限制、只读根文件系统、镜像 digest 等生产级加固。
- 不删除、重建或清空 `data/pgdata/` 与 `.models/modelscope/`。

## 前置依赖

- **执行前置 Plan**：`plan_7`、`plan_9`、`plan_12`
- `plan_7` 已完成 Stub/DeepSeek Query 配置、正式 UserQuery API 和 Query HTTP 回归。
- `plan_9` 已完成 `crag-api`、`crag-smoke`、默认禁用诊断端点及 `app-smoke` Compose Profile。
- `plan_12` 已完成 Docker 当前事实、服务索引、受控偏差和机械防漂移校验。
- 三项执行前置 Plan 均已完成；本计划不存在外部执行阻塞。

## 文件边界

- `crag-app/build.gradle.kts`
- `crag-app/src/main/resources/application.yml`
- `crag-app/src/test/java/ai/cerbur/crag/app/ApplicationHealthComponentTest.java`（新增）
- `Dockerfile`
- `docker-compose.yml`
- `scripts/tests/http/docker_readiness_test.sh`（新增）
- `constraints/docker-structure.md`
- `constraints/package-structure.md`
- `README.md`
- `plan/plan_10/plan_10.md`
- `plan/index/README.md`

本计划不修改 Sidecar 源码、Sidecar Dockerfile、业务模块源码、数据库 schema、`.env.example` 或既有业务 HTTP 回归脚本。

## 关键决策

- 使用 Spring Boot Actuator 正式健康能力，不新增业务 Controller。
- Actuator Web 只暴露 `health`，`show-details` 固定为 `never`；`env`、`beans`、`configprops`、`metrics` 等端点不得对外暴露。
- 启用 Actuator probes：
  - liveness group 只包含 `livenessState`。
  - readiness group 明确包含 `readinessState` 与 `db`。
- 数据库不可用时 readiness 必须失败；liveness 必须保持成功。数据库恢复后 readiness 必须无需重建 App 镜像即可恢复。
- App readiness 不访问 Sidecar。Sidecar 首次模型下载、模型加载和运行状态继续由 `model-init` 成功退出与 Sidecar `/health` 负责。
- Compose 健康检查固定调用容器内 `http://localhost:8080/actuator/health/readiness`。
- 为避免依赖 Alpine/Temurin 镜像中不稳定的隐含工具，应用运行阶段显式安装 `curl`，并继续在安装后切换为 `appuser` 非 root 运行。
- `app` 与 `app-smoke` 使用相同镜像和相同正式健康端点，可以同时运行；Smoke 仅通过 `docker compose --profile smoke up -d --build app-smoke` 显式启用。
- 默认 `docker compose up -d --build` 不启动 `app-smoke`。
- `db:5432`、`sidecar:8001`、`app:8080` 保持宿主机端口映射；`app-smoke` 使用 `8081:8080`。
- Docker readiness 回归使用确定性 LLM Stub，不调用真实 DeepSeek。
- 持久化验收通过正式 AdminRag HTTP 写入带唯一 `runId` 的文档，普通 `docker compose down` 后重新启动，再以只读数据库查询确认该文档仍存在；数据库查询只作为 HTTP 写入后的持久化辅助证据。
- 验收禁止 `docker compose down -v`、清表、删除 bind mount 或删除模型缓存。

## 未决问题

无。健康语义、Actuator 暴露面、Sidecar 边界、双 App 并存、宿主机端口、Smoke 启动方式、探针工具和验收范围均已确认。

## 风险与回滚

- Spring Boot readiness 默认分组可能不包含数据库：配置中显式声明 `readinessState,db`，并同时使用 H2 组件测试和真实 PostgreSQL 故障回归验证。
- 数据库连接池在停止或恢复数据库后可能存在短暂状态延迟：自动化脚本使用有上限的轮询并输出最后状态，不使用无上限等待或一次性立即断言。
- 安装 `curl` 会小幅增加运行镜像体积：该成本换取确定、可审查的健康检查工具；不额外安装诊断工具包。
- App 启动和 schema 初始化可能超过普通探针宽限期：Compose 为 App 设置 `start_period: 30s`、`interval: 10s`、`timeout: 5s`、`retries: 12`；Sidecar 模型首次加载仍由其现有 120 秒宽限期和 30 次重试负责。
- `app` 与 `app-smoke` 共享数据库，回归数据可能影响后续观察：脚本使用唯一 `runId`，只做精确只读确认，不清理其他数据。
- 停止数据库的故障测试会暂时影响两个 App：脚本必须使用 `trap` 恢复 `db`、重新等待健康并执行普通 `docker compose down`；恢复失败时保留日志并返回非零。
- 所有实现按任务独立提交。失败时可逆序撤销文档、回归脚本、Compose healthcheck、Actuator 配置与依赖；本计划无 schema 迁移，回滚不得删除数据库或模型缓存。

## 测试与验证计划

测试按 `constraints/test-workflow.md` 分层执行：

- 纯单元测试：本计划不新增纯业务逻辑，无新增 `*Test`。
- 轻量组件测试：
  - 新增 `ApplicationHealthComponentTest`，使用 Spring Context、MockMvc 与 H2。
  - 验证 `/actuator/health`、`/actuator/health/liveness`、`/actuator/health/readiness` 返回 HTTP 200 与 `status=UP`。
  - 验证响应不含 `components` 详情。
  - 验证 `/actuator/env` 不暴露并返回 HTTP 404。
  - 精确命令：`./gradlew :crag-app:test --tests '*ApplicationHealthComponentTest'`。
- 架构测试：不新增架构规则；最终运行 `./gradlew :crag-app:test --tests '*ArchitectureTest'` 防止模块边界回归。
- Compose 静态校验：
  - `docker compose config --services` 预期包含默认 `db`、`model-init`、`sidecar`、`app`，不包含 `app-smoke`。
  - `docker compose --profile smoke config --services` 预期额外包含 `app-smoke`。
  - 两份配置中的 App 服务均必须包含 readiness healthcheck。
- Docker HTTP 回归：
  - 运行 `bash scripts/tests/http/docker_readiness_test.sh`。
  - 脚本必须构建并启动默认栈，等待 `db`、`sidecar`、`app` 健康；验证正式健康端点、最小 Actuator 暴露面和默认 Smoke 端点 404。
  - 脚本随后启用 `app-smoke`，验证两个 App 同时健康、端口互不冲突、Smoke App 正式健康端点与诊断端点均可用。
  - 脚本停止 `db`，在限定时间内验证两个 App readiness 为 HTTP 503、liveness 为 HTTP 200、Compose 状态为 unhealthy；恢复 `db` 后验证状态重新为 healthy。
  - 脚本通过正式 AdminRag HTTP 写入唯一文档，普通 down/up 后以只读 SQL 确认文档仍存在。
- 既有业务回归：
  - readiness 专用脚本结束并清理服务后，执行 `docker compose --profile smoke up -d --build --wait`，重新启动默认与 Smoke App 并等待健康。
  - `bash scripts/tests/http/admin_rag_contract_test.sh http://localhost:8080`
  - `bash scripts/tests/http/smoke_default_test.sh http://localhost:8080`
  - `bash scripts/tests/http/smoke_endpoints_test.sh http://localhost:8081`
  - 三条既有业务回归结束后执行普通 `docker compose down`，不使用 `-v`。
- 最终工程校验：
  - `./gradlew check`
  - `python3 scripts/validate_constraints.py`
  - `python3 scripts/validate_plans.py --strict`
  - 独立交接前执行 `python3 scripts/validate_plans.py --strict --verify-git`
  - `git diff --check`

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 10.1 | 接入 Actuator 正式健康能力 | ✅ 完成 | c36658e | 2026-06-22 |
| 10.2 | 建立 Compose readiness 与自动化故障回归 | 🚧 进行中 | 2e6bf86 | — |
| 10.3 | 同步部署文档并完成全量验收 | 🚧 进行中 | 8b264ad | — |

整体进度：1 / 3（33%）

## 10.1 接入 Actuator 正式健康能力

**目标**：通过测试先行接入最小暴露面的 liveness/readiness，并明确数据库只影响 readiness。

**前置任务**：无

**范围**：

1. 新增 `ApplicationHealthComponentTest`，先断言三个健康入口、隐藏详情和 `/actuator/env` 404；运行目标测试确认在未接入 Actuator 时失败。
2. 在 `crag-app/build.gradle.kts` 增加 `org.springframework.boot:spring-boot-starter-actuator`。
3. 在 `application.yml` 增加以下等价配置：

   ```yaml
   management:
     endpoints:
       web:
         exposure:
           include: health
     endpoint:
       health:
         show-details: never
         probes:
           enabled: true
         group:
           liveness:
             include: livenessState
           readiness:
             include: readinessState,db
   ```

4. 运行目标组件测试，确认健康端点成功、无详情且其他管理端点不暴露。

**非目标**：不新增 Controller；不修改 Sidecar；不让 readiness 调用 Sidecar；不修改 Docker 镜像或 Compose。

**验收标准**：目标组件测试先失败后通过；Actuator 只暴露 health；三个健康入口均返回 `status=UP` 且不含 `components`；readiness 显式包含 `readinessState,db`；liveness 只包含 `livenessState`；`/actuator/env` 返回 404。

**验证方式**：运行 `./gradlew :crag-app:test --tests '*ApplicationHealthComponentTest'` 和 `./gradlew :crag-app:test --tests '*ArchitectureTest'`；预期目标组件测试和既有架构测试均通过。

**涉及文件**：`crag-app/build.gradle.kts`、`crag-app/src/main/resources/application.yml`、`crag-app/src/test/java/ai/cerbur/crag/app/ApplicationHealthComponentTest.java`

## 10.2 建立 Compose readiness 与自动化故障回归

**目标**：让默认与 Smoke App 使用同一正式 readiness healthcheck，并以自动化脚本证明双 App 并存、数据库故障恢复和 bind mount 持久化。

**前置任务**：10.1

**范围**：

1. 在 `Dockerfile` runtime 阶段以 root 执行 `apk add --no-cache curl`，随后继续使用既有 `appuser` 运行应用。
2. 为 `app` 与 `app-smoke` 增加同一 healthcheck：

   ```yaml
   healthcheck:
     test: ["CMD", "curl", "--fail", "--silent", "--show-error", "http://localhost:8080/actuator/health/readiness"]
     interval: 10s
     timeout: 5s
     retries: 12
     start_period: 30s
   ```

3. 新增 `scripts/tests/http/docker_readiness_test.sh`，使用 `set -euo pipefail`、唯一 `runId`、有上限轮询和 `trap` 恢复环境。
4. 脚本依次执行并断言：
   - 默认配置不包含运行中的 `app-smoke`，Smoke 配置包含该服务。
   - 默认栈启动后 `db`、`sidecar`、`app` 为 healthy。
   - `app` 的 health/liveness/readiness 为 HTTP 200 且 `status=UP`，响应不含 `components`；`/actuator/env` 与 `/api/v1/test/smoke` 为 HTTP 404。
   - 启用 `app-smoke` 后，两个 App 同时 healthy；`8080` 与 `8081` 的正式健康端点均成功；只有 `8081` 的 Smoke 诊断端点成功。
   - 通过 `POST /api/v1/admin/rag` 写入标题含 `runId` 的文档并保存 `docId`。
   - 停止 `db` 后，在限定时间内两个 readiness 返回 HTTP 503、两个 liveness 返回 HTTP 200、两个容器状态变为 unhealthy。
   - 恢复 `db` 后，数据库与两个 App 重新 healthy。
   - 执行普通 `docker compose down` 后重新启动，使用 `docker compose exec -T db psql` 的只读查询按 `docId` 确认 HTTP 写入文档仍存在。
   - 最终普通 `docker compose down`，不使用 `-v`。
5. 构建 App 镜像，并确认镜像中 `curl` 可执行、运行用户仍为 `appuser`。

**非目标**：不把数据库只读查询当作业务入口回归；不修改健康语义、Sidecar、业务 API 或 LLM；不运行真实 DeepSeek；不删除测试数据、volume、数据库目录或模型缓存。

**验收标准**：默认与 Smoke Compose 配置可解析；两个 App 都具备相同 healthcheck；镜像以 `appuser` 运行且探针命令可执行；专用脚本可重复执行并以非零退出码表达失败；默认 App 与 Smoke App 可并存；数据库故障只影响 readiness，不影响 liveness；恢复后自动重新健康；普通 down/up 后 HTTP 写入数据仍存在。

**验证方式**：运行 `docker compose config`、`docker compose --profile smoke config`、`docker compose build app`、`APP_IMAGE_ID=$(docker compose images -q app) && docker run --rm --entrypoint curl "$APP_IMAGE_ID" --version`、`APP_IMAGE_ID=$(docker compose images -q app) && docker image inspect "$APP_IMAGE_ID" --format '{{.Config.User}}'` 和 `bash scripts/tests/http/docker_readiness_test.sh`；预期配置可解析、curl 成功、镜像用户为 `appuser`、专用脚本通过。

**涉及文件**：`Dockerfile`、`docker-compose.yml`、`scripts/tests/http/docker_readiness_test.sh`

## 10.3 同步部署文档并完成全量验收

**目标**：让 README、Docker 约束、包结构约束和最终实现一致，并完成全部工程与既有业务回归。

**前置任务**：10.2

**范围**：

1. 更新 `constraints/docker-structure.md`：记录 App liveness/readiness、Compose healthcheck 参数和 `curl`；删除“App 尚无正式健康检查”的已知偏差。
2. 更新 `constraints/package-structure.md`：将 Smoke 激活示例统一为 `docker compose --profile smoke up -d --build app-smoke`，不再描述为覆盖默认 App 的 `SPRING_PROFILES_ACTIVE`。
3. 更新中文 README：说明默认健康等待、正式健康端点、双 App 并存、Smoke 命令、端口和故障诊断入口。
4. 执行 readiness 专用脚本；该脚本最终清理服务后，使用 `docker compose --profile smoke up -d --build --wait` 重新启动默认与 Smoke App。
5. 在两个 App 健康后执行既有 AdminRag、默认 Smoke 和 Smoke 端点回归；回归结束后执行普通 `docker compose down`，不使用 `-v`。
6. 执行最终工程校验，记录环境、命令、结果与摘要。

**非目标**：不修改运行时代码、Compose 或 Dockerfile；不重写项目介绍、API 文档或测试分类；不运行真实 DeepSeek；不清理持久化数据。

**验收标准**：README 可指导默认与 Smoke App 同时启动并定位正式健康端点；两份约束准确描述最终实现且无受控偏差残留；既有正式与 Smoke HTTP 回归通过；Gradle、约束、Plan 和 diff 校验全部通过。

**验证方式**：依次运行 `bash scripts/tests/http/docker_readiness_test.sh`、`docker compose --profile smoke up -d --build --wait`、`bash scripts/tests/http/admin_rag_contract_test.sh http://localhost:8080`、`bash scripts/tests/http/smoke_default_test.sh http://localhost:8080`、`bash scripts/tests/http/smoke_endpoints_test.sh http://localhost:8081`、`docker compose down`、`./gradlew check`、`python3 scripts/validate_constraints.py`、`python3 scripts/validate_plans.py --strict`、`rg -n '/actuator/health|app-smoke|尚无正式健康检查' README.md constraints plan/plan_10/plan_10.md` 和 `git diff --check`；预期 Compose 重启并等待健康后，三条既有 HTTP 回归与工程校验全部通过，检索只保留符合当前实现的表述。

**涉及文件**：`constraints/docker-structure.md`、`constraints/package-structure.md`、`README.md`、`plan/plan_10/plan_10.md`、`plan/index/README.md`

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-21 | macOS, Docker Compose | `./gradlew :crag-app:test --tests '*ApplicationHealthComponentTest'` | ✅ 通过 | 4 个健康端点组件测试全部通过 |
| 2026-06-21 | macOS, Docker Compose | `./gradlew :crag-app:test --tests '*ArchitectureTest'` | ✅ 通过 | 架构边界无回归 |
| 2026-06-21 | macOS, Docker Compose | `bash scripts/tests/http/docker_readiness_test.sh` | ✅ 通过 | 配置校验、健康端点、双 App 并存、故障恢复、持久化全部通过 |
| 2026-06-21 | macOS, Docker Compose | `bash scripts/tests/http/admin_rag_contract_test.sh http://localhost:8080` | ✅ 通过 | AdminRag 写入契约正常 |
| 2026-06-21 | macOS, Docker Compose | `bash scripts/tests/http/smoke_default_test.sh http://localhost:8080` | ✅ 通过 | 默认模式不暴露 Smoke 端点 |
| 2026-06-21 | macOS, Docker Compose | `bash scripts/tests/http/smoke_endpoints_test.sh http://localhost:8081` | ✅ 通过 | Smoke 诊断端点正常 |
| 2026-06-21 | macOS, Docker Compose | `./gradlew check` | ✅ 通过 | 全量 Gradle 校验通过 |
| 2026-06-21 | macOS, Docker Compose | `python3 scripts/validate_constraints.py` | ✅ 通过 | 0 error |
| 2026-06-21 | macOS, Docker Compose | `python3 scripts/validate_plans.py --strict --verify-git` | ✅ 通过 | 0 error, 24 warning（历史 Plan） |
| 2026-06-21 | macOS, Docker Compose | `git diff --check` | ✅ 通过 | 无空白问题 |
| 2026-06-22 | macOS, Docker Compose | 独立验收：组件/架构测试、约束与 Plan 校验、Compose 配置、`docker_readiness_test.sh` | ❌ 未通过 | 功能断言最终通过，但停库后的 readiness 使用固定等待后无超时单次 `curl`；两个请求各阻塞约 60 秒，未实现计划要求的有上限轮询，存在脚本无界挂起风险。失败清理还会先 `down` 再读取日志，无法可靠保留故障证据。10.1 验收通过；10.2、10.3 退回执行。 |

## 阻塞记录

- **日期**：2026-06-21
- **原因**：`plan_7` 尚未完成独立验收，最终 Query/LLM 配置和部署环境变量未冻结。
- **当前进度**：0/4，Plan 保持草稿，未修改运行时实现。
- **解除条件**：`plan_7` 通过独立验收并标记 `completed`。
- **解除方**：`plan_7` 独立验收 session。
- **解除结果**：`plan_7` 已于 2026-06-21 通过第七次独立验收；随后基于 Plan 7、Plan 9、Plan 12 和当前仓库事实完成范围校准。
- **恢复状态与下一步**：Plan 已从 `draft` 转为 `ready`，下一步执行 10.1。

## 废弃任务记录

无。本 Plan 在进入 `in_progress` 前重排任务；旧草稿任务编号不构成稳定执行历史。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-19 | 创建 draft 计划 | Docker 部署契约存在缺口，且需等待模块边界与 Query/LLM 最终实现 | 初始建立 4 项全面部署治理任务 |
| 2026-06-19 | 增加 Plan 7 前置并收窄 Smoke 职责 | Query/LLM 配置影响部署契约，Smoke 机制由 Plan 9 交付 | 执行顺序调整为 Plan 9 → Plan 7 → Plan 10 |
| 2026-06-21 | 解除 Plan 7 前置阻塞 | Plan 7 第七次独立验收通过 | Plan 恢复为 draft，等待最终校准 |
| 2026-06-21 | 按批准设计收缩范围并转为 ready | Plan 12 已完成 Docker 约束治理；剩余真实缺口为 App 正式健康检查、Compose 就绪链和部署验收 | 任务重排为 3 项；新增 Actuator probes、双 App 并存、故障恢复和持久化自动验收；可开始执行 10.1 |
| 2026-06-21 | 补齐最终业务回归的 Compose 生命周期 | readiness 专用脚本按设计在结束时清理服务，原 10.3 命令序列未在既有 HTTP 回归前重新启动服务 | 明确以 Smoke Profile 重启并等待默认与 Smoke App 健康，完成三条回归后普通 down |
| 2026-06-22 | 第一次独立验收未通过 | 停库阶段未按计划实现有上限 readiness 轮询，宿主机 `curl` 无请求超时，且失败清理在读取日志前执行 `down`；实测两个 readiness 请求各阻塞约 60 秒 | 10.1 标记完成；10.2、10.3 与 Plan 退回 `in_progress`，修复有界轮询、请求超时和失败证据保留后重新交接独立验收 |
