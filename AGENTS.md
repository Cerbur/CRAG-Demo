# CRAG-Demo — 项目索引

## 项目概述

基于 RAG（Retrieval-Augmented Generation）的问答机器人后端服务。技术栈：Java 21 + Spring Boot + Gradle + Docker + pgvector。

## 核心规范

1. **规划优先**：所有代码修改必须先落为 `plan/` 目录下的计划文档，不直接在对话中修改代码。
2. **计划分层**：
   - `plan/plan_main.md` — 总业务方向，持续迭代。
   - `plan/plan_X`（plan_1, plan_2, ...）— 具体执行计划，将大任务拆分为小任务，每完成一个小任务更新状态并记录 commit。
   - `plan/plan_archive/` — 方向性变更记录，记录 before / after 及时间。
3. **README.md** — 使用中文维护，随项目目标持续更新。
4. **开源协议**：MIT。

## 包结构索引

```
com.crag.demo
├── CragDemoApplication              — Spring Boot 启动类
├── controller/                       — API 入口层
│   ├── UserQueryController          — 用户查询接口
│   └── AdminRagController           — 管理端 RAG 知识库上传接口
├── service/                          — 业务服务层（接口定义）
│   └── impl/                         — 业务服务层（实现）
│       ├── UserQueryServiceImpl     — 用户查询服务实现
│       └── AdminRagServiceImpl      — 管理端 RAG 服务实现
├── core/                             — RAG 核心逻辑层
│   ├── chunk/                        — 文档分块
│   ├── embedding/                    — 向量化
│   ├── vectorQuery/                  — 向量查询
│   ├── retrieve/                     — 检索召回
│   └── rerank/                       — 重排序
├── dao/                              — 数据访问层（pgvector 向量数据库操作）
└── integration/                      — 外部服务接入层
    ├── llm/                          — LLM 调用（Spring AI，一期 DeepSeek）
    │   └── prompt/                   — 提示词模板管理
    ├── embedding/                    — Embedding 调用（一期 Sidecar /embed）
    └── rerank/                       — Rerank 调用（一期 Sidecar /rerank）
```

## Docker 部署结构

```
docker-compose.yml                    — 编排所有服务
Dockerfile                            — Spring Boot 应用镜像
├── PostgreSQL + pgvector 扩展        — 向量数据库
└── Spring Boot 应用                  — 主服务
```

## 当前状态

- [x] 项目规划初始化
- [x] plan_main 细化 — 混合检索流水线（BM25 + pgvector + RRF）
- [ ] plan_A 执行计划创建（最小 Demo：core 全链路）
- [ ] 项目脚手架搭建（plan_A 子任务）
- [ ] 核心功能实现（plan_A 子任务）
- [ ] LLM 接入（plan_B）
- [ ] Docker 化部署（plan_C）

## 对话约定

- 每次对话产出为 plan 文档更新，不直接修改代码。
- 如有实现过程中的失误，在原 plan 基础上分支新的 plan，然后执行。
- 所有 plan_X 执行完成后，更新 `plan/plan_main.md` 标记进度。
