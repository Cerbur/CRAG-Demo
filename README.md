# CRAG-Demo

基于 RAG（检索增强生成）的开箱即用问答机器人后端服务。

## 项目简介

CRAG-Demo 是一个基于 Java 21 + Spring Boot 构建的 RAG 问答系统后端，使用 PostgreSQL + pgvector 作为向量数据库，通过 Docker Compose 一键部署所有依赖服务。

## 特性

- 🔌 **开箱即用**：Docker Compose 一键启动，包含所有中间件
- 🧠 **RAG 架构**：文档分块 → 向量化 → 语义检索 → 重排序 → LLM 生成
- 📦 **全容器化**：PostgreSQL + pgvector + Spring Boot 全部 Docker 化
- 🔗 **统一 LLM 接口**：磨平不同 LLM 提供商差异，可灵活切换
- 📐 **清晰分层**：Controller → Service → Core → DAO → Integration，职责分明

## 技术栈

- **语言**：Java 21
- **框架**：Spring Boot 3.x
- **构建**：Gradle（Kotlin DSL）
- **向量数据库**：PostgreSQL + pgvector
- **容器化**：Docker + Docker Compose

## 快速开始

> 待 plan_1 执行完成后补充具体步骤。

```bash
# 克隆项目
git clone <repo-url>
cd CRAG-Demo

# 一键启动
docker-compose up -d

# 验证服务
curl http://localhost:8080/actuator/health
```

## API 接口

### 用户查询

```http
POST /api/v1/query
Content-Type: application/json

{
  "question": "什么是 RAG？"
}
```

### 管理端上传

```http
POST /api/v1/admin/rag
Content-Type: multipart/form-data
```

## 项目结构

```
├── controller/       # API 入口层
├── service/          # 业务服务层
│   └── impl/         # 服务实现
├── core/             # RAG 核心逻辑
│   ├── chunk/        # 文档分块
│   ├── embedding/    # 向量化
│   ├── vectorQuery/  # 向量查询
│   ├── retrieve/     # 检索召回
│   └── rerank/       # 重排序
├── dao/              # 数据访问层
├── integration/      # LLM 接入层
│   ├── llm/          # LLM 调用接口
│   └── prompt/       # 提示词管理
└── plan/             # 项目规划文档
```

## 开源协议

MIT License — 详见 [LICENSE](./LICENSE)
