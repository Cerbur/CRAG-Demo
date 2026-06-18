---
workflow_version: 2
plan_id: plan_10
type: main
status: draft
owner: parent-agent
created: 2026-06-19
updated: 2026-06-19
---

# plan_10 — Docker 部署契约升级与实现对齐

## 背景与目标

当前 `constraints/docker-structure.md` 只列出 `docker-compose.yml`、`Dockerfile` 和两个主要服务，未覆盖实际存在的 `model-init`、Python Sidecar、模型缓存、服务依赖、健康检查、配置注入、Smoke 启动方式和验收门槛。它更接近不完整的文件索引，无法作为部署变更的项目级约束，也已经与当前 Compose 实现和测试工作流产生漂移。

本计划在 `plan_9` 完成模块边界与 `crag-smoke` 落地后，将 Docker 文档升级为面向本地开发、测试和 Demo 的部署契约，区分长期硬约束、当前实现索引和已知偏差；同时补齐正式应用健康检查、单 Compose Smoke 切换、构建与运行约束，并让 Compose、Dockerfile、README 和测试工作流保持一致。

## 范围

- 将 Docker 文档从文件清单升级为本地开发、测试和 Demo 部署契约。
- 定义服务职责、依赖顺序、网络访问、端口、健康检查、配置注入、持久化、镜像构建、安全和验收规则。
- 保留单一 `docker-compose.yml`，通过显式 `SPRING_PROFILES_ACTIVE=smoke` 切换 Smoke 模式。
- 为 Spring Boot 应用提供不依赖诊断端点的正式健康检查，并纳入 Compose 就绪判断。
- 对齐 `docker-compose.yml`、应用与 Sidecar Dockerfile、忽略规则和必要的环境变量示例。
- 同步中文 README、测试工作流及受影响的部署说明。
- 分别完成默认模式和 Smoke 模式的 Docker Compose 验收。

## 非目标

- 不提供生产可直接使用的部署方案，不引入 Kubernetes、Helm、云服务编排或高可用配置。
- 不拆分第二份长期维护的 Compose 文件。
- 不改变 Retrieval、Embedding、Rerank 或 Query 的业务算法与 HTTP 协议。
- 不更换 PostgreSQL、pgvector、Java、Python 或模型供应方案。
- 不将 Demo 默认凭据包装为生产密钥管理方案。
- 不删除本地数据库或模型缓存，不以清理持久化数据作为常规验收步骤。

## 前置依赖

- `plan_9` 必须完成，由其先落地 `crag-api`、`crag-smoke`、默认禁用诊断端点及显式 Smoke Profile 装配。
- `plan_10` 在 `plan_9` 完成前保持 `draft`，不得与 `plan_9` 并行修改 `docker-compose.yml`、`Dockerfile`、`README.md`、`constraints/docker-structure.md` 或 `constraints/test-workflow.md`。
- 转为 `ready` 前重新读取 `plan_9` 的最终提交和验收记录，并按实际模块名、健康端点与启动方式校准本计划文件边界和任务内容。

## 文件边界

- `constraints/docker-structure.md`
- `constraints/test-workflow.md`
- `constraints/package-structure.md`
- `docker-compose.yml`
- `Dockerfile`
- `.dockerignore`
- `.gitignore`
- `.env.example`（仅在需要说明可配置项时新增）
- `sidecar/Dockerfile`
- `sidecar/.dockerignore`
- `crag-app/build.gradle.kts`
- `crag-app/src/main/resources/application.yml`
- `crag-app/src/test/**`
- `README.md`
- `plan/plan_10/plan_10.md`
- `plan/index/README.md`

若 `plan_9` 完成后模块或健康检查配置路径变化，转为 `ready` 前按最终实现更新本节，不保留失效路径。

## 关键决策

- 本部署契约只覆盖本地开发、自动化测试、手工联调和 Demo，不承诺生产适用性。
- 默认入口保持 `docker compose up -d --build`，必须完整编排 `db`、`model-init`、`sidecar` 和 `app`，不得要求用户预先手工运行模型下载脚本。
- 允许按服务启动用于诊断，但局部启动不构成完整部署验收。
- 只维护一个 `docker-compose.yml`。默认模式不得启用 Smoke；Smoke 通过 `SPRING_PROFILES_ACTIVE=smoke docker compose up -d --build` 显式激活。
- 文档分为“架构硬约束”“当前实现索引”“已知偏差”三类；服务名、端口和镜像标签属于当前实现索引，依赖方向、健康检查、非 root、配置注入和持久化边界属于硬约束。
- 本地数据库数据继续持久化到 `data/pgdata/`，模型缓存继续持久化到 `.models/modelscope/`；实际数据不得进入 Git 或 Docker build context。
- Demo 默认数据库凭据可以保留，但必须标注仅供本地和 Demo 使用；真实密钥不得写入 Compose，新增敏感项通过环境变量注入。
- `app:8080`、`sidecar:8001` 和 `db:5432` 默认暴露给宿主机用于本地访问和诊断；容器间调用必须使用 `db`、`sidecar` 等 Compose 服务名，不得绕经 `localhost` 或宿主机映射端口。
- `db`、`sidecar`、`app` 等长期运行服务必须具有验证真实可服务状态的健康检查；`model-init` 以成功退出作为就绪条件，不伪造健康检查。
- 启动关系固定为 `model-init` 成功后启动 `sidecar`，`db` 与 `sidecar` 健康后启动 `app`。
- `app` 使用独立正式健康端点，不得依赖 `/api/v1/test/**` 或其他 Smoke 诊断端点。
- 基础镜像不得使用 `latest`；当前阶段固定明确发行标签，不强制 digest。应用继续使用多阶段构建，运行镜像不得包含 JDK、源码或构建缓存。
- `app` 和 `sidecar` 默认以非 root 用户运行；一次性 `model-init` 只有在共享模型目录写权限确有需要时才可显式使用 root，并必须保留原因说明。
- Compose 只通过环境变量覆盖部署配置，不复制或挂载修改后的 `application.yml`、源码或 Jar 来掩盖镜像内容。
- 新增服务依赖时，必须同时处理配置项、Compose 网络、启动依赖、健康检查和验收方式。
- 未来如需生产部署，必须建立独立部署方案；不得把生产差异隐式塞入默认 Demo Compose。

## 未决问题

- `plan_9` 完成后的正式应用健康端点路径及其所在模块需要在本计划转为 `ready` 前按最终实现确认。当前推荐使用 Spring Boot Actuator 的正式健康能力，而不是新增业务 Controller；该项不影响本计划保持 `draft`。

## 风险与回滚

- `plan_9` 可能改变模块名、Docker 构建复制路径或 Smoke 装配方式：本计划禁止提前执行，并在转为 `ready` 前基于 `plan_9` 最终提交重新校准。
- 新增应用健康检查可能因数据库初始化或启动时序导致容器长时间处于 unhealthy：健康端点必须反映数据库真实状态，并设置与首次启动成本匹配的 `start_period`、超时和重试次数。
- Sidecar 首次下载和加载模型耗时较长：保留模型 bind mount，验收不得默认删除 `.models/modelscope/`；健康检查继续等待模型真正加载完成。
- 固定镜像标签仍不能提供 digest 级供应链复现：该风险在 Demo 范围内接受，未来生产方案另行处理。
- 宿主机固定端口可能与本地服务冲突：文档明确默认端口和覆盖方式；完整验收前检查端口占用，不通过改容器间地址规避。
- 所有修改按任务独立提交。失败时可逆序撤销文档契约、健康检查和 Compose 对齐提交；本计划不迁移数据库结构，回滚无需删除 `data/pgdata/` 或模型缓存。

## 测试与验证计划

- Plan 校验：`python3 scripts/validate_plans.py --strict`。
- Compose 静态校验：默认和 Smoke 环境分别运行 `docker compose config`。
- 镜像构建验证：重新构建 `app`、`sidecar` 和 `model-init` 镜像，确认基础镜像标签、构建上下文和多阶段产物正确。
- 默认部署：`docker compose up -d --build`，确认长期服务均健康、正式健康端点可用、数据库与 Sidecar 状态正常，且 `/api/v1/test/**` 不存在。
- Smoke 部署：先停止默认容器，再运行 `SPRING_PROFILES_ACTIVE=smoke docker compose up -d --build`，确认正式健康端点仍可用且 Smoke 诊断端点仅在该模式出现。
- 容器内连通性：从 `app` 容器验证通过 Compose 服务名访问 `sidecar`，不得使用宿主机回环地址。
- 持久化检查：普通 `docker compose down` 后重新启动，确认数据库目录与模型缓存未被删除。
- 验收清理：执行 `docker compose down`，不附带 `-v`，不删除仓库内 bind mount 数据。
- 最终检查：`./gradlew check`、`python3 scripts/validate_plans.py --strict --verify-git` 和 `git diff --check`。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 10.1 | 升级 Docker 部署契约与当前实现索引 | ⏳ 待开始 | — | — |
| 10.2 | 建立正式应用健康检查与 Compose 就绪链 | ⏳ 待开始 | — | — |
| 10.3 | 对齐 Compose、镜像构建、配置和持久化规则 | ⏳ 待开始 | — | — |
| 10.4 | 同步使用文档并完成默认与 Smoke 全量验收 | ⏳ 待开始 | — | — |

整体进度：0 / 4（0%）

## 10.1 升级 Docker 部署契约与当前实现索引

**目标**：让 `constraints/docker-structure.md` 成为可指导实现和审查的唯一 Docker 部署契约，而不是不完整的文件清单。  
**前置任务**：`plan_9` 已完成；本计划已根据其最终实现从 `draft` 转为 `ready`  
**范围**：按“文档定位与适用环境、架构硬约束、当前文件与服务索引、服务依赖、网络与端口、健康检查、配置与密钥、数据与模型持久化、镜像构建与运行身份、默认与 Smoke 启动、验收门槛、已知偏差、维护同步矩阵”重写 Docker 约束；准确记录 `db`、`model-init`、`sidecar`、`app` 的当前职责和值；列出重写时发现但将在 10.2、10.3 修复的偏差。  
**非目标**：本任务不修改 Compose、Dockerfile 或应用代码，不把当前端口和镜像标签定义为永久不可变架构。  
**验收标准**：文档明确区分硬约束、当前实现和已知偏差；覆盖本计划全部关键决策；不存在尚未实现却被写成“当前事实”的结构；所有内部链接有效。  
**验证方式**：逐项对照 `docker-compose.yml`、两个 Dockerfile、忽略规则、`application.yml` 和 `plan_9` 最终提交；运行 `rg -n 'TODO|TBD|待定' constraints/docker-structure.md`、`python3 scripts/validate_plans.py --strict` 和 `git diff --check`。  
**涉及文件**：`constraints/docker-structure.md`、`plan/plan_10/plan_10.md`

## 10.2 建立正式应用健康检查与 Compose 就绪链

**目标**：让 Compose 能通过不依赖 Smoke 后门的正式端点判断 Spring Boot 应用及其数据库依赖是否真正可服务。  
**前置任务**：10.1  
**范围**：按 `plan_9` 最终模块结构接入 Spring Boot 正式健康能力，配置只暴露必要健康信息；增加应用层测试，验证默认 Profile 下健康能力可用且不依赖 `crag-smoke`；为 `app` 增加健康检查；确认 `model-init → sidecar → app` 与 `db → app` 的就绪条件完整。  
**非目标**：不新增业务健康 Controller，不把 Retrieval 全链路或模型推理请求塞入应用存活检查，不改变 Sidecar `/health` 协议。  
**验收标准**：默认 Profile 下正式健康端点返回成功并反映数据库连接状态；禁用 Smoke 时仍可检查应用健康；`app` 成为 Compose 中具有健康状态的长期服务；任一必要依赖未就绪时 App 不会被误判为完整部署可用。  
**验证方式**：运行受影响的 `crag-app` 测试和 `./gradlew check`；运行 `docker compose config`；启动 `db`、`sidecar`、`app` 后检查 `docker compose ps` 与正式健康端点；停止数据库后确认应用健康状态能够反映依赖异常。  
**涉及文件**：`crag-app/build.gradle.kts`、`crag-app/src/main/resources/application.yml`、`crag-app/src/test/**`、`docker-compose.yml`、`constraints/docker-structure.md`

## 10.3 对齐 Compose、镜像构建、配置和持久化规则

**目标**：消除当前 Docker 实现与部署契约之间除正式健康检查外的偏差，并保持默认一键启动。  
**前置任务**：10.2  
**范围**：在单一 Compose 中加入默认关闭、环境变量显式开启的 Smoke Profile 注入；核对并修正服务名、端口、内部 URL、依赖条件、健康检查参数、bind mount、基础镜像明确标签、多阶段构建、运行镜像内容和非 root 用户；仅在需要解释可覆盖配置时新增无秘密的 `.env.example`；保证 `data/`、`.models/`、`.env` 同时被 Git 和对应 build context 排除。  
**非目标**：不新增第二份 Compose，不引入生产 secret manager，不强制镜像 digest，不删除或重建本地持久化数据。  
**验收标准**：默认 `docker compose config` 不激活 Smoke；设置 `SPRING_PROFILES_ACTIVE=smoke` 后只改变应用 Profile；容器间地址全部使用服务名；长期服务健康检查、一次性任务退出条件、非 root 运行、固定基础镜像标签和持久化排除规则与契约一致；`docker compose up -d --build` 不需要手工预下载模型。  
**验证方式**：比较默认与 Smoke 两次 `docker compose config` 输出；运行 `docker compose build --no-cache app sidecar model-init`；检查构建上下文不包含 `data/`、`.models/`、`.env`；启动服务后使用容器身份与网络连通性检查验证运行用户和 `app → sidecar` 服务名访问。  
**涉及文件**：`docker-compose.yml`、`Dockerfile`、`.dockerignore`、`.gitignore`、`.env.example`（按需）、`sidecar/Dockerfile`、`sidecar/.dockerignore`、`constraints/docker-structure.md`

## 10.4 同步使用文档并完成默认与 Smoke 全量验收

**目标**：让使用者、测试约束和实际部署方式一致，并以可复现证据完成部署契约升级。  
**前置任务**：10.3  
**范围**：更新中文 README 的默认启动、正式健康检查、Smoke 显式启动、端口与本地数据说明；同步 `test-workflow.md` 的 Docker 验收命令和健康判定；检查 `package-structure.md` 中 Smoke Docker 描述与最终方式一致；执行默认与 Smoke 两套完整验收并记录结果。  
**非目标**：不重写项目介绍或 API 文档，不把局部服务启动当作完整验收，不清除数据库和模型缓存。  
**验收标准**：README 可让新使用者用单一 Compose 完成默认和 Smoke 两种启动；默认模式诊断端点不可见、Smoke 模式可见；`db`、`sidecar`、`app` 均健康；App 正式健康端点、数据库状态、Sidecar 健康和容器内调用均通过；普通 down/up 后持久化数据仍存在；约束、实现和命令示例无冲突。  
**验证方式**：依次运行默认与 Smoke 环境的 `docker compose config`、`docker compose up -d --build`、`docker compose ps`、正式健康端点和诊断端点检查、容器内 Sidecar 调用、`docker compose down`；运行 `./gradlew check`、`python3 scripts/validate_plans.py --strict --verify-git`、`rg -n 'docker compose|SPRING_PROFILES_ACTIVE|/api/v1/test|health' README.md constraints` 和 `git diff --check`。  
**涉及文件**：`README.md`、`constraints/test-workflow.md`、`constraints/package-structure.md`、`constraints/docker-structure.md`、`plan/plan_10/plan_10.md`、`plan/index/README.md`

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |

## 阻塞记录

无。当前处于 `draft` 是因为已声明的前置依赖 `plan_9` 尚未完成，不记为运行中阻塞；转为 `ready` 前按其最终实现重新校准。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-19 | 创建 draft 计划 | Docker 约束 grilling 已完成，决定将全面部署契约升级与 `plan_9` 串行拆分 | 建立 4 项任务；`plan_9` 完成前不得执行 |
