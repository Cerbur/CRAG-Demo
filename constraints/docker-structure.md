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
| `db` | PostgreSQL 17 + pgvector，提供持久化与向量检索能力 | 禁止承载业务逻辑 |
| `model-init` | 一次性下载 Sidecar 所需模型到共享卷，成功后退出 | 禁止伪装健康检查；禁止作为长期服务运行 |
| `sidecar` | Python 模型推理服务，提供 `/embed` 与 `/rerank` | 禁止承载业务编排或直接访问数据库 |
| `app` | Spring Boot 应用，承载全部业务能力 | 禁止在默认 Profile 下暴露诊断端点 |
| `app-smoke` | 仅在显式激活 Smoke Profile 时存在的应用实例，提供分阶段诊断端点 | 禁止在默认启动中出现；禁止承载正式业务能力 |

### 3.2 依赖方向与启动顺序

启动关系固定为：

```
model-init（成功退出） → sidecar（健康）
db（健康） ───────────── → app（健康）
sidecar（健康） ───────── → app（健康）
```

- `model-init` 以成功退出作为 `sidecar` 的就绪条件。
- `db` 和 `sidecar` 以健康检查通过作为 `app` 的就绪条件。
- `app-smoke` 的依赖链与 `app` 相同。

### 3.3 健康检查

- 长期运行服务（`db`、`sidecar`、`app`）必须具有验证真实可服务状态的健康检查。
- 一次性任务（`model-init`）以成功退出作为就绪条件，不伪造健康检查。
- `app` 必须使用独立正式健康端点，不得依赖 `/api/v1/test/**` 或 Smoke 诊断端点。
- Sidecar 使用其 `/health` 端点进行健康检查。

### 3.4 运行身份

- `app` 和 `sidecar` 必须以非 root 用户运行。
- `model-init` 只有在共享模型目录写权限确有需要时才可显式使用 root，并必须保留原因说明。

### 3.5 配置注入

- Compose 只通过环境变量覆盖部署配置，不复制或挂载修改后的 `application.yml`、源码或 Jar 来掩盖镜像内容。
- 新增服务依赖时，必须同时处理配置项、Compose 网络、启动依赖、健康检查和验收方式。
- Demo 默认数据库凭据可以保留，但必须标注仅供本地和 Demo 使用；真实密钥不得写入 Compose。

### 3.6 镜像构建

- 基础镜像不得使用 `latest`；当前阶段固定明确发行标签。
- `app` 使用多阶段构建，运行镜像不得包含 JDK、源码或构建缓存。
- 构建上下文必须排除 `data/`、`.models/`、`.env` 等本地持久化或凭据目录。

### 3.7 网络

- 容器间调用必须使用 Compose 服务名（`db`、`sidecar`），不得绕经 `localhost` 或宿主机映射端口。
- 宿主机端口暴露仅用于本地访问和诊断。

### 3.8 持久化

- 本地数据库数据持久化到 `data/pgdata/`，模型缓存持久化到 `.models/modelscope/`。
- 实际数据不得进入 Git 或 Docker build context。
- 普通 `docker compose down` 不得删除 bind mount 数据。

### 3.9 服务变更同步

- 新增、移动或重命名 Docker / Compose / Sidecar 部署文件时，必须同步更新本文档。
- Docker 部署结构变更如果会影响计划范围，必须同步更新对应 `plan_N.md` 或 `plan_N.hotfix_M.md`。

## 四、当前文件索引

```text
docker-compose.yml                    — 编排所有服务
Dockerfile                            — Spring Boot 应用多阶段构建镜像
.dockerignore                         — 应用镜像构建排除
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

### 5.1 `db` — PostgreSQL 17 + pgvector

| 属性 | 当前值 |
| --- | --- |
| 镜像 | `pgvector/pgvector:pg17` |
| 容器名 | `crag-db` |
| 端口 | `5432:5432` |
| 数据库 | `crag_demo` |
| 用户 | `crag_user`（Demo 默认凭据） |
| 持久化 | `./data/pgdata:/var/lib/postgresql/data` |
| 健康检查 | `pg_isready -U crag_user -d crag_demo`，间隔 5s，超时 3s，重试 5 次 |
| 网络 | `crag-net`（bridge） |

### 5.2 `model-init` — 模型下载

| 属性 | 当前值 |
| --- | --- |
| 构建上下文 | `./sidecar`，Dockerfile: `sidecar/Dockerfile` |
| 容器名 | `crag-model-init` |
| 运行身份 | `root`（共享模型目录写权限需要；模型缓存最终由 `sidecar` 以 `sidecar` 用户只读挂载使用） |
| 命令 | `python -u download_models.py` |
| 健康检查 | 禁用（一次性任务，以成功退出为就绪条件） |
| 挂载 | `./.models/modelscope:/models/modelscope`（读写，下载模型） |
| 网络 | `crag-net` |

### 5.3 `sidecar` — Python 模型推理服务

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

### 5.4 `app` — Spring Boot 应用（默认模式）

| 属性 | 当前值 |
| --- | --- |
| 构建上下文 | `.`，Dockerfile: `Dockerfile`（多阶段：JDK 21 编译 → JRE 21 运行） |
| 容器名 | `crag-app` |
| 端口 | `8080:8080` |
| 运行身份 | `appuser`（非 root） |
| 数据库连接 | `jdbc:postgresql://db:5432/crag_demo`（通过 Compose 服务名） |
| Sidecar 连接 | `http://sidecar:8001`（Embedding + Rerank，通过 Compose 服务名） |
| 就绪条件 | `db` 健康 且 `sidecar` 健康 |
| 健康检查 | `curl --fail --silent --show-error http://localhost:8080/actuator/health/readiness`，间隔 10s，超时 5s，重试 12 次，启动宽限期 30s |
| Actuator | 只暴露 `health`；liveness 仅含 `livenessState`；readiness 含 `readinessState,db`；`show-details=never` |
| 运行镜像工具 | 显式安装 `curl`（健康检查探针使用） |
| 网络 | `crag-net` |

### 5.5 `app-smoke` — Spring Boot 应用（Smoke 诊断模式）

| 属性 | 当前值 |
| --- | --- |
| 构建上下文 | `.`，Dockerfile: `Dockerfile`（与 `app` 相同镜像） |
| 容器名 | `crag-app-smoke` |
| 端口 | `8081:8080` |
| 运行身份 | `appuser`（非 root） |
| Profile | `SPRING_PROFILES_ACTIVE=smoke`（显式激活诊断端点） |
| 其他配置 | 数据库与 Sidecar 连接同 `app` |
| 就绪条件 | `db` 健康 且 `sidecar` 健康 |
| 健康检查 | 与 `app` 相同（同一镜像、同一正式健康端点） |
| Compose Profile | `smoke`（不随默认 `docker compose up` 启动） |
| Query 环境变量 | `CRAG_QUERY_LLM_PROVIDER`（stub）、`CRAG_QUERY_LLM_STUB_MODE`（success）<br>DeepSeek（可选，provider=deepseek 时须设置）：`DEEPSEEK_API_KEY`、`CRAG_QUERY_LLM_DEEPSEEK_BASE_URL`、`CRAG_QUERY_LLM_DEEPSEEK_MODEL`、`CRAG_QUERY_LLM_DEEPSEEK_TEMPERATURE`、`CRAG_QUERY_LLM_DEEPSEEK_MAX_OUTPUT_TOKENS`、`CRAG_QUERY_LLM_REQUEST_TIMEOUT` |
| 网络 | `crag-net` |

### 5.6 网络

| 属性 | 当前值 |
| --- | --- |
| 名称 | `crag-net` |
| 驱动 | `bridge` |

## 六、已知偏差

无。`plan_10` 已完成 Actuator 健康端点和 Compose healthcheck 接入。

## 七、维护与自动校验

- `AGENTS.md` 与 `CLAUDE.md` 不直接展开 Docker 部署结构，只链接到本文档。
- 新增、修改或删除 Compose 服务时，必须同步更新第 4-6 节。
- 约束校验器通过解析 `docker-compose.yml` 顶层 `services` 键并与本文档当前服务索引核对，阻止未经登记的漂移。
- 涉及测试运行方式时同时遵守 [`test-workflow.md`](./test-workflow.md)。
