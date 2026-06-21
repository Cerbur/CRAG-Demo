# Plan 10 Docker Readiness 设计

## 1. 背景

CRAG-Demo 当前已经具备单 Compose 默认启动、独立 `app-smoke` 服务、数据库与 Sidecar 健康检查、非 root 运行和本地持久化。`plan_12` 也已将 `constraints/docker-structure.md` 重写为部署约束与当前事实索引。

现存部署缺口集中在 Spring Boot 应用本身：

- `app` 与 `app-smoke` 没有正式健康检查，Compose 只能判断其依赖是否就绪，不能判断应用是否真正可服务。
- Plan 10 草稿仍包含 Plan 12 已完成的文档治理工作。
- Plan 10 中部分 Smoke 启动描述与当前 `app-smoke` Compose Profile 实现不一致。
- 默认模式、Smoke 模式、依赖故障和持久化尚未形成统一、可复现的部署验收闭环。

因此 Plan 10 收缩为正式健康检查、Compose 就绪链对齐和部署验收，不重复 Plan 12，也不扩展为生产部署治理。

## 2. 目标

- 为 `app` 与 `app-smoke` 提供不依赖 Smoke 诊断端点的正式健康能力。
- 明确区分应用存活状态与接收请求的就绪状态。
- 让 Compose 根据应用 readiness 标记 `healthy` 或 `unhealthy`。
- 保持数据库、Sidecar 和应用健康职责相互独立。
- 支持默认 App 与 Smoke App 同时运行。
- 以自动化验证证明默认模式、Smoke 模式、数据库故障恢复和持久化行为。
- 校准 Plan 10、README、Docker 约束和当前 Compose 实现。

## 3. 非目标

- 不新增业务健康 Controller。
- 不在健康检查中执行 Retrieval、Embedding、Rerank 或 LLM 请求。
- 不改变 Query、Retrieval、Sidecar 或正式 HTTP API 协议。
- 不新增第二份 Compose 文件。
- 不引入 Kubernetes、Helm、高可用、生产密钥管理或生产监控方案。
- 不增加资源限制、只读根文件系统、镜像 digest 等生产级加固。
- 不隐藏当前为学习和诊断用途暴露的数据库与 Sidecar 宿主机端口。

## 4. 健康模型

### 4.1 Liveness

`/actuator/health/liveness` 只表示 JVM 与 Spring 应用仍在运行。数据库或 Sidecar 故障不得使 liveness 失败，避免把外部依赖故障误判为进程死亡。

### 4.2 Readiness

`/actuator/health/readiness` 表示应用是否可以接收业务请求，并包含数据库连接状态：

- Spring 应用和数据库均正常时返回成功。
- 数据库不可用时返回非成功状态。
- 数据库恢复后无需重建容器即可重新恢复成功。

Readiness 不调用 Sidecar。Sidecar 继续通过自身 `/health` 和 Compose healthcheck 独立判断状态，避免健康检查触发模型推理或形成跨服务健康级联。

### 4.3 信息暴露

Actuator Web 端点只暴露 `health`。健康详情设为 `never`，不得通过 HTTP 暴露环境变量、Bean、配置值、数据库信息或其他管理端点。

## 5. Compose 架构

服务关系保持为：

```text
model-init 成功退出 → sidecar 健康 ─┐
                                    ├→ app / app-smoke readiness 健康
db 健康 ────────────────────────────┘
```

- `model-init` 仍以成功退出为完成条件，不增加伪健康检查。
- `sidecar` 仍通过 `/health` 判断真实模型服务状态。
- `app` 与 `app-smoke` 均使用 `/actuator/health/readiness` 作为 Compose healthcheck。
- `app` 与 `app-smoke` 可以同时运行，共享 `db` 与 `sidecar`。
- `app` 暴露 `8080:8080`，`app-smoke` 暴露 `8081:8080`。
- `app-smoke` 继续通过 Compose Profile `smoke` 显式启用，不通过覆盖默认 `app` 的 Profile 实现模式切换。
- `db:5432` 与 `sidecar:8001` 继续暴露给宿主机，服务间调用仍必须使用 Compose 服务名。

## 6. 组件与配置

### 6.1 Spring Boot

`crag-app` 增加 Spring Boot Actuator 依赖，并在应用配置中：

- 仅开放 `health` Web 端点。
- 设置 `show-details: never`。
- 启用 liveness 与 readiness probes。
- 将数据库健康贡献者纳入 readiness。

健康能力属于 `crag-app` 组合根，不放入 `crag-api`，也不依赖 `crag-smoke`。

### 6.2 Docker Compose

为 `app` 与 `app-smoke` 增加相同的 readiness healthcheck。检查参数应容纳 Spring Boot 正常启动和 schema 初始化，但不为模型首次下载时间重复增加超长等待；模型加载等待仍由 Sidecar healthcheck 负责。

Compose 不新增应用间依赖，也不要求启动 Smoke 前停止默认 App。

### 6.3 测试

增加轻量组件测试，至少验证：

- 默认 Profile 下正式健康端点存在。
- liveness 与 readiness 可访问。
- Smoke 诊断端点不是健康能力的前置条件。
- 未授权的其他 Actuator Web 端点不暴露。

数据库真实故障及恢复行为由 Docker 验收证明，不使用 H2 结果代替 PostgreSQL 保证。

## 7. 故障处理

### 7.1 数据库故障

停止 `db` 后：

- `app` 与 `app-smoke` readiness 应失败。
- Compose 应将对应应用标记为 `unhealthy`。
- liveness 应保持成功。

恢复 `db` 后，应用 readiness 和 Compose 健康状态应自动恢复，无需删除持久化数据或重建应用镜像。

### 7.2 Sidecar 故障

Sidecar 不可用时，由 Sidecar 自身 healthcheck 表达故障。应用 readiness 不主动调用 Sidecar，也不把模型推理可用性伪装成应用基础就绪条件。

业务调用遇到 Sidecar 故障时，继续沿用现有业务异常处理；Plan 10 不修改该行为。

### 7.3 健康端点异常

若 Actuator 配置或镜像内容导致 readiness 不可访问，Compose 必须将应用保持在非健康状态。不得回退到 `/api/v1/test/**` 或只检查 TCP 端口来掩盖配置错误。

## 8. 验收设计

部署验收应可重复执行，并覆盖：

1. Compose 默认配置不包含运行中的 `app-smoke`。
2. 默认启动后 `db`、`sidecar`、`app` 均健康。
3. 默认 App 的 liveness、readiness 可用，Smoke 诊断端点不存在。
4. 启用 `smoke` Profile 后，`app` 与 `app-smoke` 可同时运行。
5. Smoke App 的正式健康端点和诊断端点均可用。
6. 两个 App 分别使用宿主机端口 `8080` 与 `8081`，互不冲突。
7. 停止数据库后 readiness 失败、liveness 保持成功；恢复数据库后 readiness 自动恢复。
8. 普通 `docker compose down` 与后续启动不会删除 `data/pgdata/` 或 `.models/modelscope/`。
9. 容器间数据库与 Sidecar 地址继续使用 Compose 服务名。
10. `./gradlew check`、Compose 静态校验、Docker HTTP 回归、Plan 校验、约束校验和 `git diff --check` 全部通过。

验收不得执行 `docker compose down -v`，也不得删除数据库目录或模型缓存。

## 9. Plan 10 任务结构

Plan 10 调整为三个顺序任务：

### 10.1 校准计划与剩余部署偏差

基于 Plan 7、Plan 9、Plan 12 和当前实现，删除重复治理范围，统一 Smoke 启动方式、健康语义、文件边界和验收命令，并将 Plan 转为 `ready`。

### 10.2 实现正式健康检查与 Compose 就绪链

接入 Actuator probes、配置最小暴露面、增加组件测试，并为 `app` 与 `app-smoke` 配置 readiness healthcheck。

### 10.3 同步文档并完成默认与 Smoke 全量验收

同步 README 与 Docker 当前事实，执行默认/Smoke 并存、数据库故障恢复、持久化及全量工程校验，记录可复现证据。

## 10. 风险与回滚

- Actuator readiness 默认分组行为可能不自动包含数据库贡献者：实现时必须通过组件测试与真实 PostgreSQL 故障测试确认，不依赖配置推测。
- 数据库连接池可能导致故障与恢复状态存在短暂延迟：Compose 重试参数应覆盖合理探测周期，验收使用有上限的轮询，不使用无依据固定长等待。
- Alpine 运行镜像不一定包含 `curl`：Compose healthcheck 应使用镜像中确定存在的能力，或在设计范围内选择不显著扩大运行镜像的检查方式。
- `app` 与 `app-smoke` 共享数据库，Smoke 数据可能影响默认实例观察：HTTP 回归继续使用唯一 `runId`，不执行破坏性清理。
- 回滚按任务提交逆序撤销 Actuator、Compose healthcheck 和文档变更；本设计不包含 schema 迁移，无需删除数据库或模型缓存。

## 11. 成功标准

Plan 10 完成时，项目应满足一句话标准：

> 使用单一 Compose 可同时运行默认与 Smoke 应用，Compose 能通过最小暴露的正式 readiness 端点准确判断 Spring Boot 与 PostgreSQL 是否可服务，并有自动化证据证明故障恢复和持久化行为。
