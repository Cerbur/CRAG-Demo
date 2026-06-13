# CRAG-Demo 总体规划（plan_main）

> 创建时间：2026-06-10
> 最后更新：2026-06-14（收敛为总体方向文档，执行计划索引迁移到 `plan/index/`）

---

## 一、项目定位

实现一个**开箱即用**的基于 RAG（Retrieval-Augmented Generation）的问答机器人后端服务。

核心原则：

- 一键部署：Docker Compose 包含应用、PostgreSQL + pgvector、模型 sidecar。
- 零鉴权：Demo 阶段对外 API 直接可用。
- 接口简洁：围绕 `UserQuery` 与 `AdminRag` 两个核心接口推进。
- 检索可解释：保留 sources，让用户能看到回答依据。

---

## 二、技术方向

| 层级 | 方向 | 说明 |
| --- | --- | --- |
| 后端 | Java 21 + Spring Boot 3.x | Demo 主服务 |
| 构建 | Gradle Kotlin DSL | 统一构建入口 |
| 数据 | PostgreSQL + pgvector | 元数据、全文检索、向量检索 |
| Embedding | Python Sidecar `/embed` | gte 中文 embedding，768 维 |
| Rerank | Python Sidecar `/rerank` | bge-reranker-v2-m3 |
| LLM | DeepSeek API + Spring AI | 一期接入 DeepSeek，integration 层保留扩展空间 |
| 部署 | Docker + Docker Compose | 本地一键启动 |

---

## 三、核心链路方向

### 3.1 AdminRag 入库链路

`POST /api/v1/admin/rag` 接收纯文本，完成 chunk 分块和数据库写入；Embedding 与索引构建通过异步任务处理。

方向约束：

- 一期只支持纯文本，不做文件解析。
- chunk 使用 parent + child 结构。
- parent 保存上下文窗口；child 作为检索粒度。
- Dense 与 Sparse 两条索引链路独立推进，失败可重试。

### 3.2 UserQuery 查询链路

`POST /api/v1/query` 接收用户问题，走混合检索、融合、重排和 LLM 生成。

方向约束：

- Dense 检索与 Sparse 检索都以 child chunk 为命中粒度。
- RRF 融合双路结果。
- 回表获取 parent chunk 上下文。
- Rerank 后交给 LLM 生成答案。
- Demo 阶段不做流式返回和用户鉴权。

---

## 四、架构边界

| 层级 | 职责 |
| --- | --- |
| controller | HTTP API 入口、请求校验、响应封装 |
| service | 业务编排，不直接依赖 Repository |
| core | RAG 核心能力，如 chunk、dense、sparse、rrf、rerank |
| dao | 数据访问入口，封装 Repository |
| integration | 外部服务接入，如 LLM、Embedding、Rerank |
| cron | 定时任务编排，调用 service/core/dao 完成异步处理 |

详细包结构维护在 [`constraints/package-structure.md`](../constraints/package-structure.md)。

---

## 五、阶段路线

| 阶段 | 目标 | 当前状态 |
| --- | --- | --- |
| plan_1 | 项目脚手架、基础设施、DAO、Docker 基础环境 | ✅ 完成 |
| plan_2 | AdminRag 写入链路、Dense Embedding 异步处理、sidecar 支撑 | ✅ 完成 |
| plan_3 | 项目介绍文档、架构图、协作约束整理 | ✅ 完成 |
| plan_4 | Sparse + Dense 查询、RRF、Rerank、UserQuery、LLM 全链路 | ⏳ 未创建 |

执行计划详情、历史小数计划、hotfix 状态和完成记录统一查看 [`plan/index/README.md`](./index/README.md)。

---

## 六、决策入口

- Plan 工作流、目录、命名、索引和进度规则：[`constraints/plan-workflow.md`](../constraints/plan-workflow.md)
- Java 代码风格：[`constraints/code-style.md`](../constraints/code-style.md)
- Java 包结构：[`constraints/package-structure.md`](../constraints/package-structure.md)
- Docker 部署结构：[`constraints/docker-structure.md`](../constraints/docker-structure.md)

`plan_main` 只维护长期方向和阶段边界，不承载具体执行计划索引、任务细节或 hotfix 明细。
