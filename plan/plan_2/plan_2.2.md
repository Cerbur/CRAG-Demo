# Plan_2.2 — Sidecar 本地模型缓存与 Docker Compose 开箱即用

> 创建时间：2026-06-11
> 依赖：plan_2.1（Python Sidecar 模型服务）

---

## 范围说明

本计划只解决 Python Sidecar 的 Docker build / 模型缓存问题：

- 模型不进入 Git 管理
- Docker build 不下载模型
- `docker compose up` 在根目录开箱即用
- 启动前检查本地被 gitignore 的模型缓存目录；缺失时本地下载
- Sidecar 容器通过 bind mount 直接读取项目目录下的 ModelScope 模型目录
- 冒烟验证 sidecar 可启动，`/health`、`/embed`、`/rerank` 可响应

不包含 Java 业务链路、数据库 schema、RAG 检索逻辑调整。

---

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 2.2.1 | 新增本地模型检查/下载脚本 | ✅ 完成 | — | 2026-06-11 |
| 2.2.2 | 调整 Sidecar Dockerfile，移除 build-time 模型下载 | ✅ 完成 | — | 2026-06-11 |
| 2.2.3 | 调整 docker-compose.yml，挂载本地模型缓存目录 | ✅ 完成 | — | 2026-06-11 |
| 2.2.4 | 调整 .gitignore / .dockerignore，确保模型不进 Git 与 build context | ✅ 完成 | — | 2026-06-11 |
| 2.2.5 | 更新验证说明并完成 compose 冒烟 | ✅ 完成 | — | 2026-06-11 |
| 2.2.6 | 统一 embedding 维度：1024 → 768（对齐 ModelScope 实际模型） | ✅ 完成 | — | 2026-06-12 |

> 状态图例：⏳ 待开始 / 🔄 进行中 / ✅ 完成 / ❌ 阻塞

整体进度：**6 / 6（100%）**

---

## 方案

### 本地模型目录

使用项目根目录下的 `.models/modelscope` 作为 ModelScope 本地模型目录：

```text
.models/
└── modelscope/
    ├── iic__nlp_gte_sentence-embedding_chinese-base/
    └── BAAI__bge-reranker-v2-m3/
```

该目录被 `.gitignore` 忽略，同时被 `.dockerignore` 排除，避免进入 Git 或 Docker build context。

### 启动流程

```bash
./scripts/ensure-sidecar-models.sh
docker compose up --build
```

脚本职责：

1. 检查 `.models/modelscope` 中是否已有两个模型目录：`iic/nlp_gte_sentence-embedding_chinese-base` + `BAAI/bge-reranker-v2-m3`。
2. 缺失时使用本机 Python 环境安装/调用 `modelscope.snapshot_download` 下载模型。
3. 下载到项目目录 `.models/modelscope`，Sidecar 容器通过 compose bind mount 读取。
4. Sidecar 通过本地路径加载模型，不再让 sentence-transformers 在运行时访问 HuggingFace。

### Docker 行为

- Dockerfile 只安装 Python 依赖与复制服务代码，不下载模型。
- compose 将 `./.models/modelscope:/models/modelscope` 挂载进 sidecar。
- Sidecar 设置 `EMBEDDING_MODEL_PATH` / `RERANK_MODEL_PATH`，启动时从挂载目录加载模型；如果模型缺失，`model-init` 会先下载。

---

### 2.2.6 — 统一 embedding 维度：1024 → 768

**背景**：plan_2.1 原设计使用 `shibing624/text2vec-large-chinese`（1024 维），但该模型在 ModelScope 上不存在。实际部署使用了 `iic/nlp_gte_sentence-embedding_chinese-base`（768 维）。验证时发现 `/embed` 实际返回 `dimension: 768`，与 schema 中 `vector(1024)` 不一致，会导致 Dense 入库 INSERT 失败（pgvector 拒绝维度不匹配的向量）。

**代码变更**：

| 文件 | 内容 |
|------|------|
| `src/main/resources/schema.sql` | `embedding vector(1024)` → `embedding vector(768)` |
| `src/main/java/com/crag/demo/dao/entity/ChunkEmbedding.java` | `columnDefinition = "vector(768)"` + Javadoc 维度注释同步 |

**计划文档同步修正**：

| 文件 | 变更 |
|------|------|
| `plan/plan_2/plan_2.md` | 2.0 Schema 节 + 2.4 EmbeddingClient 节：`vector(1024)` / `float[1024]` → `vector(768)` / `float[768]` |
| `plan/plan_2/plan_2.1.md` | /embed 协议：模型名 `text2vec-large-chinese` → `gte-chinese-base`，维度 `1024` → `768` |
| `plan/plan_main.md` | 5.1.2 Chunk 表：`vector(1024)` → `vector(768)`；技术栈表 + 六中间件：模型名/维度修正 |

---

## 验证

```bash
./scripts/ensure-sidecar-models.sh
docker compose up --build -d sidecar
docker compose ps sidecar
curl http://localhost:8001/health
curl -X POST http://localhost:8001/embed \
  -H "Content-Type: application/json" \
  -d '{"text":"你好世界"}'
curl -X POST http://localhost:8001/rerank \
  -H "Content-Type: application/json" \
  -d '{"query":"什么是RAG","documents":["RAG是检索增强生成","今天天气不错"]}'
```

已验证：

- `docker compose up --build -d sidecar` 可完成 `model-init` 并拉起 sidecar
- `/health` 返回 `{"status":"ok","models":{"embedding":"loaded","rerank":"loaded"}}`
- `/embed` 返回 768 维向量
- `/rerank` 返回排序结果，RAG 相关文档排名第一
- `docker compose up --build -d` 可在根目录拉起 db / sidecar / app
- `http://localhost:8080/api/v1/test/smoke` 返回数据库 connected

备注：ModelScope SDK 中 `shibing624/text2vec-base-chinese` 不存在，已使用 ModelScope 可下载的同类中文 embedding 模型 `iic/nlp_gte_sentence-embedding_chinese-base`。

### 2026-06-11 运行验证记录

本次验证命令与结果：

```bash
docker compose ps
```

- `crag-db`：`Up 8 hours (healthy)`，端口 `5432:5432`
- `crag-sidecar`：`Up 8 hours (healthy)`，端口 `8001:8001`
- `crag-app`：`Up 8 hours`，端口 `8080:8080`

```bash
curl http://localhost:8001/health
```

返回：

```json
{"status":"ok","models":{"embedding":"loaded","rerank":"loaded"}}
```

```bash
curl -X POST http://localhost:8001/embed \
  -H "Content-Type: application/json" \
  -d '{"text":"什么是 RAG？"}'
```

返回 `dimension: 768`，说明 embedding 模型已加载并可推理。

```bash
curl -X POST http://localhost:8001/rerank \
  -H "Content-Type: application/json" \
  -d '{"query":"什么是 RAG？","documents":["RAG 是检索增强生成。","番茄炒蛋是一道家常菜。"]}'
```

返回排序结果，`index=0` 的 RAG 文档分数高于无关文档。

```bash
curl http://localhost:8080/api/v1/test/smoke
```

返回：

```json
{"status":"ok","database":"connected","tables":{"chunk_fts":0,"chunk":0,"chunk_embedding":0}}
```

```bash
docker compose exec -T app wget -qO- http://sidecar:8001/health
```

从 `app` 容器内部访问 `sidecar` 服务名成功，返回模型均 `loaded`，确认 Docker Compose 内部网络下 app -> sidecar 联通。

```bash
docker compose exec -T app wget -qO- \
  --header="Content-Type: application/json" \
  --post-data='{"text":"容器内调用 embedding"}' \
  http://sidecar:8001/embed
```

从 `app` 容器内部访问 sidecar `/embed` 成功，返回 `dimension: 768`，确认 app 容器到 sidecar 推理端点联通。

发现项：

- 当前实际 embedding 维度为 768；若后续数据库 `chunk_embedding.embedding` 仍按 `vector(1024)` 设计，需要在 plan_2/plan_main 与 schema 中统一为 768，或切换回 1024 维模型后再执行 Dense 入库。

### 2026-06-11 重建启动记录

按要求移除当前 Docker Compose 容器并重新 build + 启动：

```bash
docker compose down
docker compose up --build -d
```

执行结果：

- app / sidecar / model-init 镜像重新 build 成功
- `crag-db`、`crag-sidecar`、`crag-app` 容器重新创建并启动成功
- `crag-db` 状态为 `healthy`
- `crag-sidecar` 状态为 `healthy`
- `crag-app` 状态为 `Up`

重启后验证：

```bash
docker compose ps
curl http://localhost:8001/health
curl http://localhost:8080/api/v1/test/smoke
docker compose exec -T app wget -qO- http://sidecar:8001/health
```

验证结果：

- sidecar `/health` 返回 `{"status":"ok","models":{"embedding":"loaded","rerank":"loaded"}}`
- app 冒烟接口返回 `{"status":"ok","database":"connected","tables":{"chunk_embedding":0,"chunk_fts":0,"chunk":0}}`
- app 容器内部访问 `http://sidecar:8001/health` 成功，确认 Compose 内网联通

---

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-06-11 | 创建 plan_2.2，5 个子任务全部完成 |
| 2026-06-12 | 新增 2.2.6：统一 embedding 维度 1024→768（对齐 ModelScope 实际模型 `gte-chinese-base`），同步修正 schema.sql + ChunkEmbedding.java + plan_2 / plan_2.1 / plan_main 中的维度引用 |
