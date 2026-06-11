# Plan_2.1 — Python Sidecar 模型服务

> 创建时间：2026-06-10
> 依赖：plan_2（先于 plan_2 任务 2.4-2.6 执行）

---

## 范围说明

plan_2.1 补齐 plan_2 缺失的 Python Sidecar 模型服务，包括：

- FastAPI 应用骨架 + `/health` `/embed` `/rerank` 三个端点
- 模型 eager 加载：`iic/nlp_gte_sentence-embedding_chinese-base`（embedding，768 维）+ `BAAI/bge-reranker-v2-m3`（rerank）
- Sidecar Dockerfile（build-time 下载模型，非 root 用户）
- docker-compose.yml 集成（sidecar 服务 + app 依赖 + 环境变量）
- Java 侧 RerankClient 签名修正 + application.yml 配置

**原因**：plan_2 任务 2.4（EmbeddingClient）和 2.5（Cron Dense）依赖 Sidecar `/embed` 端点，但 Sidecar 本身没有任何设计和实现。

---

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 2.1.1 | Sidecar 项目脚手架（目录 + requirements.txt + main.py 骨架） | ✅ 完成 | — | 2026-06-10 |
| 2.1.2 | /health 端点 + 模型 eager 加载（lifespan） | ✅ 完成 | — | 2026-06-10 |
| 2.1.3 | /embed 端点（text2vec-base-chinese，768 维） | ✅ 完成 | — | 2026-06-10 |
| 2.1.4 | /rerank 端点（bge-reranker-v2-m3，CrossEncoder） | ✅ 完成 | — | 2026-06-10 |
| 2.1.5 | Sidecar Dockerfile + .dockerignore | ✅ 完成 | — | 2026-06-10 |
| 2.1.6 | docker-compose.yml 集成 + .gitignore + application.yml 配置 | ✅ 完成 | — | 2026-06-10 |
| 2.1.7 | Java RerankClient 签名修正（`List<?>` → `List<RerankResult>`） | ✅ 完成 | — | 2026-06-10 |

> 状态图例：⏳ 待开始 / 🔄 进行中 / ✅ 完成 / ❌ 阻塞

整体进度：**7 / 7（100%）**

---

## Sidecar API 协议

### GET /health

```
200 OK:
{
  "status": "ok" | "starting",
  "models": {
    "embedding": "loaded" | "loading",
    "rerank": "loaded" | "loading"
  }
}

503 Service Unavailable（模型加载失败）:
{
  "status": "error",
  "models": {"embedding": "failed", "rerank": "loaded"}
}
```

- `ok` → 两个模型都 loaded
- `starting` → 仍在加载中
- `error` → 任一 failed
- Docker Compose healthcheck 使用此端点判断服务就绪

### POST /embed

```
Request:  {"text": "需要向量化的文本"}
Response: {"embedding": [0.123, -0.456, ...], "dimension": 768}
Error:    503 {"error": "EmbeddingModelNotLoaded", "message": "..."}
Error:    500 {"error": "EmbeddingInferenceError", "message": "..."}
```

- 模型：`iic/nlp_gte_sentence-embedding_chinese-base`（768 维，ModelScope 实际可用模型）
- `dimension`: 768
- `normalize_embeddings=True`（单位向量，用于 pgvector 余弦相似度）
- text 限制：1-8192 字符

### POST /rerank

```
Request:  {"query": "用户问题", "documents": ["候选1", "候选2", ...]}
Response: {"results": [{"index": 0, "score": 0.98}, {"index": 2, "score": 0.76}]}
Error:    400 {"error": "ValidationError", "message": "'documents' must be a non-empty list"}
```

- 模型：`BAAI/bge-reranker-v2-m3`（CrossEncoder）
- `results` 按 `score` 降序
- `index` 是输入 `documents` 数组的 0-based 位置
- query 限制 1-1024 字符，documents 限制 1-50 条

---

## 文件变更

### 新增

| 文件 | 说明 |
|------|------|
| `sidecar/main.py` | FastAPI 应用（~180 行），lifespan 模型预加载 + 3 端点 + Pydantic 模型 |
| `sidecar/requirements.txt` | `fastapi==0.115.6`, `uvicorn[standard]==0.34.0`, `sentence-transformers==3.3.1`, `torch==2.5.1` |
| `sidecar/Dockerfile` | Python 3.12-slim，build-time 下载模型（`HF_ENDPOINT` ARG），非 root，HEALTHCHECK |
| `sidecar/.dockerignore` | Python 排除项 |

### 修改

| 文件 | 变更 |
|------|------|
| `docker-compose.yml` | 新增 `sidecar` 服务（context: ./sidecar, port 8001, healthcheck）；`app` 新增 `depends_on: sidecar` + `CRAG_EMBEDDING_SIDECAR_URL` / `CRAG_RERANK_SIDECAR_URL` 环境变量 |
| `.gitignore` | 新增 Python 排除（`__pycache__/`, `*.pyc`, `.venv/`, `venv/`） |
| `src/main/resources/application.yml` | 新增 `crag.embedding.*` 和 `crag.rerank.*` 配置（默认 localhost:8001） |
| `src/main/java/com/crag/demo/integration/rerank/RerankClient.java` | 返回类型 `List<?>` → `List<RerankResult>`；新增 `RerankResult(int index, float score)` record |

---

## 依赖关系

```
plan_2.1（Sidecar 服务）→ plan_2 任务 2.4-2.6（Java 集成）
```

plan_2 任务 2.4（SidecarEmbeddingClient）和 2.5（EmbeddingService Cron）需要 Sidecar `/embed` 可用。

---

## 验证

```bash
# 1. 构建 Sidecar 镜像（首次 10-20 分钟）
docker compose build sidecar

# 2. 启动全栈
docker compose up -d

# 3. 确认 sidecar healthy
docker compose ps
# crag-sidecar  → "healthy"

# 4. /health
curl http://localhost:8001/health
# {"status":"ok","models":{"embedding":"loaded","rerank":"loaded"}}

# 5. /embed
curl -X POST http://localhost:8001/embed \
  -H "Content-Type: application/json" \
  -d '{"text": "你好世界"}'
# {"embedding":[...],"dimension":768}

# 6. /rerank
curl -X POST http://localhost:8001/rerank \
  -H "Content-Type: application/json" \
  -d '{"query": "什么是RAG", "documents": ["RAG是检索增强生成", "今天天气不错"]}'
# {"results":[{"index":0,"score":0.95},{"index":1,"score":0.12}]}

# 7. Java 应用可达
curl http://localhost:8080/api/v1/test/smoke
```

---

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-06-10 | 创建 plan_2.1，7 个子任务全部完成 |
