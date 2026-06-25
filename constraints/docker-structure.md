# CRAG-Demo Docker 部署结构约束

> 本文档是 CRAG-Demo Docker 部署结构的唯一维护入口。`AGENTS.md`、`CLAUDE.md` 和计划文档只保留到本文档的路由。

## 一、文档定位

本文档包含三类信息，效力不同：

1. **架构硬约束**：规定服务职责、依赖方向、健康检查、运行身份、配置注入和持久化边界，新增或修改服务必须遵守。
2. **当前实现索引**：只描述仓库中已经存在的文件、服务和配置事实，用于导航，不构成未来设计承诺。
3. **已知偏差**：记录当前事实与硬约束之间尚未消除的差异，并关联负责修复的 Plan。

尚未实现的服务、配置或健康检查不得写入“当前实现索引”。未来设计统一写入对应 Plan；只有已经确定为项目级架构规则的内容才进入本文档正文。

## 二、适用环境

本部署约束覆盖本地开发、自动化测试、手工联调和 Demo。不承诺生产适用性。

默认入口：`docker compose up -d --build`，必须完整编排全部服务，不得要求用户预先手工运行模型下载脚本。

## 三、架构硬约束

### 3.1 服务职责

| 服务 | 职责 | 禁止事项 |
| --- | --- | --- |
| `redis` | Redis 7.4，提供 Snowflake ID Worker lease（不承载业务数据、缓存或事件） | 禁止承载业务持久化、缓存或事件总线 |
| `db` | PostgreSQL 17 + pgvector，提供持久化与向量检索能力 | 禁止承载业务逻辑 |
| `model-init` | 一次性下载 Sidecar 所需模型到共享卷，成功后退出 | 禁止伪装健康检查；禁止作为长期服务运行 |
| `sidecar` | Python 模型推理服务，提供 `/embed` 与 `/rerank` | 禁止承载业务编排或直接访问数据库 |
| `rag-service` | RAG 业务组合根，承载检索、入库、查询运行时、gRPC Server 与 Platform Probe | 禁止被其他 Application 模块直接依赖；legacy 写入/查询 HTTP 仅在 smoke Profile 下由 rag-service-smoke 暴露 |
| `access-service` | Access 服务组合根，gRPC Server 与 Schema readiness | 禁止 RAG 业务模块依赖 |
| `knowledge-service` | Knowledge 服务组合根，gRPC Server 与 Schema readiness | 禁止 RAG 业务模块依赖 |
| `console-api` | Console HTTP 入口，下游 Probe readiness | 禁止 DataSource、业务 Controller |
| `open-api` | Open HTTP 入口，下游 Probe readiness | 禁止 DataSource、业务 Controller |
| `rag-service-smoke` | 仅在显式激活 Smoke Profile 时存在的 RAG 诊断实例 | 禁止在默认启动中出现；禁止承载正式业务能力 |

### 3.2 依赖方向与启动顺序

启动关系固定为：

```
model-init（成功退出） → sidecar（健康）
db（健康） ───────────── → rag-service / access-service / knowledge-service（健康）
redis（健康） ─────────── → rag-service（健康）
sidecar（健康） ───────── → rag-service（健康）
db + redis + sidecar 健康 → rag-service（健康）
access / knowledge / rag 健康 → console-api / open-api（健康）
```

- `model-init` 以成功退出作为 `sidecar` 的就绪条件。
- `db`、`redis` 和 `sidecar` 以健康检查通过作为 `rag-service` 的就绪条件。
- `db` 以健康检查通过作为 `access-service` 和 `knowledge-service` 的就绪条件。
- `console-api` 和 `open-api` 以下游 Platform Probe 通过作为就绪条件。
- `rag-service-smoke` 的依赖链与 `rag-service` 相同。

### 3.3 健康检查

- 长期运行服务（`db`、`sidecar`、五个 Java 服务）必须具有验证真实可服务状态的健康检查。
- 一次性任务（`model-init`）以成功退出作为就绪条件，不伪造健康检查。
- Java 服务必须使用独立正式健康端点，不得依赖 `/api/v1/smoke/test/**` 或 Smoke 诊断端点。
- Sidecar 使用其 `/health` 端点进行健康检查。

### 3.4 运行身份

- Java 服务和 `sidecar` 必须以非 root 用户运行。
- `model-init` 只有在共享模型目录写权限确有需要时才可显式使用 root，并必须保留原因说明。

### 3.5 配置注入

- Compose 只通过环境变量覆盖部署配置，不复制或挂载修改后的 `application.yml`、源码或 Jar 来掩盖镜像内容。
- 新增服务依赖时，必须同时处理配置项、Compose 网络、启动依赖、健康检查和验收方式。
- Demo 默认数据库凭据可以保留，但必须标注仅供本地和 Demo 使用；真实密钥不得写入 Compose。

### 3.6 镜像构建

- 基础镜像不得使用 `latest`；当前阶段固定明确发行标签。
- Java 服务使用多阶段构建，运行镜像不得包含 JDK、源码或构建缓存。
- 构建上下文必须排除 `data/`、`.models/`、`.env` 等本地持久化或凭据目录。

### 3.7 网络

- 容器间调用必须使用 Compose 服务名（`db`、`sidecar`），不得绕经 `localhost` 或宿主机映射端口。
- 宿主机端口暴露仅用于本地访问和诊断。

### 3.8 持久化

- 本地数据库数据持久化到 `data/pgdata-platform/`，模型缓存持久化到 `.models/modelscope/`。旧 `data/pgdata/` 仅保留回滚，不再读取或写入。
- 实际数据不得进入 Git 或 Docker build context。
- 普通 `docker compose down` 不得删除 bind mount 数据。

### 3.9 服务变更同步

- 新增、移动或重命名 Docker / Compose / Sidecar 部署文件时，必须同步更新本文档。
- Docker 部署结构变更如果会影响计划范围，必须同步更新对应 `plan_N.md` 或 `plan_N.hotfix_M.md`。

## 四、当前文件索引

```text
docker-compose.yml                    — 编排所有服务
docker/java-service.Dockerfile        — 通用 Java Service 多阶段构建
.dockerignore                         — 应用镜像构建排除
docker/postgres/init/001-platform.sh  — PostgreSQL 平台初始化脚本
sidecar/Dockerfile                    — Python Sidecar 模型服务镜像
sidecar/.dockerignore                 — Sidecar 构建排除
sidecar/main.py                       — FastAPI 服务入口（/health、/embed、/rerank）
sidecar/download_models.py            — ModelScope 模型下载脚本
sidecar/requirements.txt              — Python 依赖声明
scripts/ensure-sidecar-models.sh      — 独立模型下载辅助脚本
.env.example                          — 环境变量模板（凭据注释，不提交真实值）
```

## 五、当前服务索引

本节逐项记录 `docker-compose.yml` 中每个服务的当前实现事实。

### 5.1 `redis` — Redis 7.4 (Worker Lease)

| 属性 | 当前值 |
| --- | --- |
| 镜像 | `redis:7.4-alpine` |
| 容器名 | `crag-redis` |
| 端口 | 不暴露 |
| 持久化 | 无（仅 Worker lease，非业务数据） |
| 健康检查 | `redis-cli ping`，间隔 5s，超时 3s，重试 5 次 |
| 网络 | `crag-net` |

### 5.2 `db` — PostgreSQL 17 + pgvector

| 属性 | 当前值 |
| --- | --- |
| 镜像 | `pgvector/pgvector:pg17` |
| 容器名 | `crag-db` |
| 端口 | 不暴露 |
| 数据库 | `crag_platform` |
| 管理员 | `crag_admin`（仅初始化和健康检查，不注入 Java 容器） |
| 业务账号 | `crag_access`、`crag_knowledge`、`crag_rag`（独立 Schema） |
| 持久化 | `./data/pgdata-platform:/var/lib/postgresql/data` |
| 初始化 | `docker/postgres/init/001-platform.sh` |
| 健康检查 | `pg_isready -U crag_admin -d crag_platform`，间隔 5s，超时 3s，重试 5 次 |
| 网络 | `crag-net`（bridge） |

### 5.3 `model-init` — 模型下载

| 属性 | 当前值 |
| --- | --- |
| 构建上下文 | `./sidecar`，Dockerfile: `sidecar/Dockerfile` |
| 容器名 | `crag-model-init` |
| 运行身份 | `root`（共享模型目录写权限需要；模型缓存最终由 `sidecar` 以 `sidecar` 用户只读挂载使用） |
| 命令 | `python -u download_models.py` |
| 健康检查 | 禁用（一次性任务，以成功退出为就绪条件） |
| 挂载 | `./.models/modelscope:/models/modelscope`（读写，下载模型） |
| 网络 | `crag-net` |

### 5.4 `sidecar` — Python 模型推理服务

| 属性 | 当前值 |
| --- | --- |
| 构建上下文 | `./sidecar`，Dockerfile: `sidecar/Dockerfile` |
| 容器名 | `crag-sidecar` |
| 端口 | `8001:8001` |
| 运行身份 | `sidecar`（非 root） |
| 模型路径 | `/models/modelscope/iic__nlp_gte_sentence-embedding_chinese-base`（Embedding）<br>`/models/modelscope/BAAI__bge-reranker-v2-m3`（Rerank） |
| 挂载 | `./.models/modelscope:/models/modelscope:ro`（只读） |
| 就绪条件 | `model-init` 成功退出 |
| 健康检查 | 通过 Python urllib 调用 `http://localhost:8001/health`，间隔 10s，超时 5s，重试 30 次，启动宽限期 120s |
| 网络 | `crag-net` |

### 5.5 `access-service` — Access 服务

| 属性 | 当前值 |
| --- | --- |
| 构建 | `docker/java-service.Dockerfile`，`SERVICE_MODULE=crag-access-service` |
| 容器名 | `crag-access-service` |
| 端口 | 不暴露 |
| 运行身份 | `appuser`（非 root） |
| 数据库 | `jdbc:postgresql://db:5432/crag_platform?currentSchema=access`，账号 `crag_access` |
| gRPC | 9091（内部） |
| 就绪条件 | `db` 健康 |
| 健康检查 | `curl http://localhost:8091/actuator/health/readiness` |
| 网络 | `crag-net` |

### 5.6 `knowledge-service` — Knowledge 服务

| 属性 | 当前值 |
| --- | --- |
| 构建 | `docker/java-service.Dockerfile`，`SERVICE_MODULE=crag-knowledge-service` |
| 容器名 | `crag-knowledge-service` |
| 端口 | 不暴露 |
| 运行身份 | `appuser`（非 root） |
| 数据库 | `jdbc:postgresql://db:5432/crag_platform?currentSchema=knowledge`，账号 `crag_knowledge` |
| gRPC | 9092（内部） |
| 就绪条件 | `db` 健康 |
| 健康检查 | `curl http://localhost:8092/actuator/health/readiness` |
| 网络 | `crag-net` |

### 5.7 `rag-service` — RAG 服务

| 属性 | 当前值 |
| --- | --- |
| 构建 | `docker/java-service.Dockerfile`，`SERVICE_MODULE=crag-rag-service` |
| 容器名 | `crag-rag-service` |
| 端口 | `8082:8082`（健康检查 + gRPC；业务 HTTP 验证在 `rag-service-smoke:8083` 的 `/api/v1/smoke/**`） |
| 运行身份 | `appuser`（非 root） |
| 数据库 | `jdbc:postgresql://db:5432/crag_platform?currentSchema=rag,extensions`，账号 `crag_rag` |
| Sidecar | `http://sidecar:8001` |
| Redis | `redis:6379`（Worker lease） |
| gRPC | 9093（内部） |
| 就绪条件 | `db` 健康 且 `redis` 健康 且 `sidecar` 健康 |
| ID 配置 | `crag.id.service-domain=rag`、required-entities `LEGACY_DOCUMENT,CHUNK` |
| 健康检查 | `curl http://localhost:8082/actuator/health/readiness` |
| 网络 | `crag-net` |

### 5.8 `console-api` — Console API

| 属性 | 当前值 |
| --- | --- |
| 构建 | `docker/java-service.Dockerfile`，`SERVICE_MODULE=crag-console-api` |
| 容器名 | `crag-console-api` |
| 端口 | `8080:8080` |
| 运行身份 | `appuser`（非 root） |
| 下游 Probe | Access/Knowledge/RAG |
| 就绪条件 | 三个下游 Probe 全部通过 |
| 健康检查 | `curl http://localhost:8080/actuator/health/readiness` |
| 网络 | `crag-net` |

### 5.9 `open-api` — Open API

| 属性 | 当前值 |
| --- | --- |
| 构建 | `docker/java-service.Dockerfile`，`SERVICE_MODULE=crag-open-api` |
| 容器名 | `crag-open-api` |
| 端口 | `8081:8081` |
| 运行身份 | `appuser`（非 root） |
| 下游 Probe | Access/RAG |
| 就绪条件 | 两个下游 Probe 全部通过 |
| 健康检查 | `curl http://localhost:8081/actuator/health/readiness` |
| 网络 | `crag-net` |

### 5.10 `rag-service-smoke` — RAG Smoke 诊断

| 属性 | 当前值 |
| --- | --- |
| 构建 | `docker/java-service.Dockerfile`，`SERVICE_MODULE=crag-rag-service` |
| 容器名 | `crag-rag-service-smoke` |
| 端口 | `8083:8082` |
| Profile | `smoke` |
| Redis | `redis:6379`（Worker lease） |
| 就绪条件 | `db` 健康 且 `redis` 健康 且 `sidecar` 健康 |
| ID 配置 | `crag.id.service-domain=rag`、required-entities `LEGACY_DOCUMENT,CHUNK` |
| Compose Profile | `smoke` |
| 网络 | `crag-net` |

### 5.11 网络

| 属性 | 当前值 |
| --- | --- |
| 名称 | `crag-net` |
| 驱动 | `bridge` |

## 六、已知偏差

无。`plan_14` 已完成五进程拓扑、独立 Schema 和通用 Dockerfile。

## 七、维护与自动校验

- `AGENTS.md` 与 `CLAUDE.md` 不直接展开 Docker 部署结构，只链接到本文档。
- 新增、修改或删除 Compose 服务时，必须同步更新第 4-6 节。
- 约束校验器通过解析 `docker-compose.yml` 顶层 `services` 键并与本文档当前服务索引核对，阻止未经登记的漂移。
- 涉及测试运行方式时同时遵守 [`test-workflow.md`](./test-workflow.md)。
