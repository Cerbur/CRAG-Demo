# README Architecture Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Update the repository landing page and architecture SVG to describe the complete current platform delivered through plan_21 without showing a Plan timeline.

**Architecture:** Treat runtime artifacts as the source of truth: `settings.gradle.kts` defines modules, `docker-compose.yml` defines processes and ports, and Protobuf/contracts define synchronous service calls. Present one current-state system view, with communication styles distinguished visually and no future-state or stage badges.

**Tech Stack:** Markdown, SVG 1.1-compatible XML, Docker Compose, Java 21/Spring Boot 4.1 platform facts, `xmllint`, Python XML parsing, SVG raster rendering.

## Global Constraints

- Do not modify business code, runtime configuration, Plan status, or constraints.
- Show plan_21 capabilities as complete current-state capabilities per user direction.
- Do not show Plan numbers, a Plan timeline, router stages, future-state paths, or completion badges.
- Keep the README in Chinese.
- Preserve `docs/assets/crag-demo-architecture.svg` as the README image path.
- Smoke endpoints remain conditional controllers in Access 8091, Knowledge 8092, and RAG 8082; never draw a separate Smoke process.

---

### Task 1: Rewrite the README around current product capabilities

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: Current modules from `settings.gradle.kts`, ports and process topology from `docker-compose.yml`, API contracts from `docs/api/`.
- Produces: A Chinese landing page whose architecture, data flow, module list, and quick-start instructions agree with current runtime facts.

- [x] **Step 1: Record stale statements that must disappear**

Run:

```bash
rg -n "router[1-5]|plan_[0-9]+|8083|PENDING_CHUNK|后续.*阶段|已知缺口|Cron: DocChunkSplitListener" README.md
```

Expected: matches identify historical stage prose and the obsolete ingestion description.

- [x] **Step 2: Replace the architecture and capability narrative**

Keep the existing quick-start and API contract links, then organize the main body with these exact current-state sections:

```markdown
## 🗺️ 平台架构
## ✨ 当前能力
### Console 管理面
### Open 查询面
### 可靠摄取与多知识库 RAG
## 🔄 核心链路
### 文档摄取
### 问答查询
## 📦 项目结构
## 🛠️ 技术栈
```

The document ingestion flow must say: Console upload → Knowledge file/outbox → `DOC_UPLOADED` via Redis Streams → RAG reads the file over gRPC → chunk/Dense/Sparse indexes scoped by `knowledgeBaseId + operationVersion` → RAG publishes ingestion state → Knowledge projects READY/FAILED.

The query flow must say: Open API Key auth/cache → RAG Query gRPC → Dense + Sparse → RRF → Rerank → parent evidence → Stub/DeepSeek → answer + sources.

- [x] **Step 3: Align module inventory and terminology**

Ensure the tree lists all current modules, including:

```text
crag-platform-contracts
crag-knowledge-contracts
crag-access-contracts
crag-rag-contracts
crag-grpc-runtime
crag-event
crag-access-service
crag-knowledge-service
crag-rag-service
crag-console-api
crag-open-api
```

Use `chunk_fts`, `chunk_embedding`, `ingestion_job`, `operationVersion`, `KnowledgeBase`, and `API Key Scope` consistently; do not reintroduce `sparse_index` or `PENDING_CHUNK` as current distributed-ingestion facts.

- [x] **Step 4: Verify historical markers are absent**

Run:

```bash
rg -n "router[1-5]|plan_[0-9]+|8083|PENDING_CHUNK|后续.*阶段|已知缺口|Cron: DocChunkSplitListener" README.md
```

Expected: no matches.

- [x] **Step 5: Review the README diff**

Run:

```bash
git diff -- README.md
```

Expected: only current-state documentation changes; quick-start commands and API contract links remain usable.

### Task 2: Redraw and validate the current-state SVG

**Files:**
- Modify: `docs/assets/crag-demo-architecture.svg`

**Interfaces:**
- Consumes: The current-state service and flow narrative established in Task 1.
- Produces: A self-contained 1600px-wide SVG with accessible title/description and four readable architecture layers.

- [x] **Step 1: Replace the old stage-oriented composition**

Draw these exact layers:

```text
调用方 → HTTP 入口 → Access / Knowledge / RAG → 数据与模型基础设施
```

Required nodes: 管理控制台用户, 外部 API 调用方, `console-api :8080`, `open-api :8081`, `Access Service`, `Knowledge Service`, `RAG Service`, PostgreSQL + pgvector, Redis, 文件存储, Python Sidecar, Stub / DeepSeek LLM.

Required flow labels: HTTPS/JSON, gRPC, Redis Streams, Schema owner, file stream, embed/rerank, generate.

- [x] **Step 2: Encode current service responsibilities**

Use concise node copy:

```text
Access: Identity / Tenant / Membership; JWT / Refresh Session; API Key / Scope
Knowledge: KnowledgeBase / Document; file + ingestion projection; retry / reconciler
RAG: versioned ingestion; Dense + Sparse / RRF / Rerank; answer + sources
```

Add a compact note that `smoke` is a conditional Profile on ports 8091/8092/8082. Do not create a Smoke node.

- [x] **Step 3: Validate XML and accessibility metadata**

Run:

```bash
xmllint --noout docs/assets/crag-demo-architecture.svg
python3 -c 'import xml.etree.ElementTree as E; p="docs/assets/crag-demo-architecture.svg"; r=E.parse(p).getroot(); ns="{http://www.w3.org/2000/svg}"; assert r.find(ns+"title") is not None; assert r.find(ns+"desc") is not None; print("SVG metadata OK")'
```

Expected: `SVG metadata OK` and exit code 0.

- [x] **Step 4: Render and visually inspect the SVG**

Render the SVG to `/tmp/crag-demo-architecture.png` using an available SVG renderer, then inspect the PNG at original detail.

Expected: no clipped text, overlapping labels, obscured arrowheads, empty cards, or content beyond the viewBox.

- [x] **Step 5: Run repository checks**

Run:

```bash
python3 scripts/validate_openapi.py
./gradlew check
git diff --check
```

Expected: every command exits 0.

- [x] **Step 6: Commit the documentation update**

```bash
git add README.md docs/assets/crag-demo-architecture.svg docs/superpowers/specs/2026-06-29-readme-architecture-refresh-design.md docs/superpowers/plans/2026-06-29-readme-architecture-refresh.md
git commit -m "docs: refresh current platform architecture"
```
