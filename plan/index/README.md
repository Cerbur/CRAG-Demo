# Plan Index

> 最后更新：2026-06-14

本目录维护 CRAG-Demo 的执行计划索引。`plan_main` 只保留总体方向；具体计划、历史小数计划和 hotfix 状态统一从这里进入。

---

## 目录结构

```text
plan/
├── plan_main.md
├── index/
│   └── README.md
├── plan_1/
│   ├── plan_1.md
│   ├── plan_1.1.md
│   └── plan_1.hotfix_1.md
├── plan_2/
│   ├── plan_2.md
│   ├── plan_2.1.md
│   ├── plan_2.2.md
│   ├── plan_2.3.md
│   └── plan_2.hotfix_*.md
├── plan_3/
│   ├── plan_3.md
│   └── plan_3.hotfix_*.md
├── plan_4/
│   └── plan_4.md
├── plan_5/
│   └── plan_5.md
├── plan_6/
│   └── plan_6.md
└── plan_archive/
    └── README.md
```

---

## 主计划索引

| Plan | 主要功能 | 状态 | 入口 |
| --- | --- | --- | --- |
| plan_main | 项目定位、技术方向、阶段路线和协作约束入口 | ✅ 已收敛 | [plan_main.md](../plan_main.md) |
| plan_1 | 项目脚手架、基础设施、分包结构、DAO、Docker 基础环境 | ✅ 完成 | [plan_1.md](../plan_1/plan_1.md) |
| plan_2 | AdminRag 写入链路、Chunk 分块、Dense Embedding Cron、Sidecar 支撑 | ✅ 完成 | [plan_2.md](../plan_2/plan_2.md) |
| plan_3 | 项目介绍文档、架构 SVG、README 插图、协作约束抽取 | ✅ 完成 | [plan_3.md](../plan_3/plan_3.md) |
| plan_4 | Sparse 索引写入链路，完成 ingestion 侧 chunk_fts 构建 | ✅ 完成 | [plan_4.md](../plan_4/plan_4.md) |
| plan_5 | Java module 拆分，完成 `ai.cerbur.crag` 包名迁移、multi-module 迁移和启动模块收敛 | ✅ 完成 | [plan_5.md](../plan_5/plan_5.md) |
| plan_6 | Retrieval + Query 全链路，承接原 plan_4 未执行查询任务并补充 Rerank/LLM | ⏳ 待开始 | [plan_6.md](../plan_6/plan_6.md) |

---

## Plan_1 明细

| 文件 | 主要功能 | 状态 |
| --- | --- | --- |
| [plan_1.md](../plan_1/plan_1.md) | Gradle + Spring Boot 脚手架、基础包结构、DAO、schema、Dockerfile、docker-compose 基础环境 | ✅ 完成 |
| [plan_1.1.md](../plan_1/plan_1.1.md) | 历史小数计划：冒烟测试 Controller，用于验证 HTTP、数据库连接和三张表可查询 | ✅ 完成 |
| [plan_1.hotfix_1.md](../plan_1/plan_1.hotfix_1.md) | 计划命名约束修正，禁止继续新增小数 plan，统一 hotfix 归属规则 | ✅ 完成 |

## Plan_2 明细

| 文件 | 主要功能 | 状态 |
| --- | --- | --- |
| [plan_2.md](../plan_2/plan_2.md) | AdminRag 入库链路、ChunkSplit、Controller 接线、EmbeddingClient、DenseEmbeddingCron | ✅ 完成 |
| [plan_2.1.md](../plan_2/plan_2.1.md) | 历史小数计划：Python Sidecar 模型服务，提供 `/health`、`/embed`、`/rerank` | ✅ 完成 |
| [plan_2.2.md](../plan_2/plan_2.2.md) | 历史小数计划：Sidecar 本地模型缓存、Docker Compose 开箱即用、embedding 维度对齐 | ✅ 完成 |
| [plan_2.3.md](../plan_2/plan_2.3.md) | 历史小数计划：Git ignore 本地噪音清理 | ✅ 完成 |
| [plan_2.hotfix_1.md](../plan_2/plan_2.hotfix_1.md) | ChunkSplit 长文覆盖、多 parent group 和单测补强 | ✅ 完成 |
| [plan_2.hotfix_2.md](../plan_2/plan_2.hotfix_2.md) | Core 能力包拆分与 ChunkSplit 命名收敛 | ✅ 完成 |
| [plan_2.hotfix_3.md](../plan_2/plan_2.hotfix_3.md) | ChunkSplit 类迁移到 `chunk.split` 子包，AdminRagService 写入简化 | ✅ 完成 |
| [plan_2.hotfix_4.md](../plan_2/plan_2.hotfix_4.md) | AdminRagService 单元测试补充 | ✅ 完成 |
| [plan_2.hotfix_5.md](../plan_2/plan_2.hotfix_5.md) | 抽离 ChunkDao，Cron/Service 不再直接依赖 Repository | ✅ 完成 |

## Plan_3 明细

| 文件 | 主要功能 | 状态 |
| --- | --- | --- |
| [plan_3.md](../plan_3/plan_3.md) | 项目介绍文档、全链路架构 SVG、README 插图入口 | ✅ 完成 |
| [plan_3.hotfix_1.md](../plan_3/plan_3.hotfix_1.md) | 代码风格约束文档抽取 | ✅ 完成 |
| [plan_3.hotfix_2.md](../plan_3/plan_3.hotfix_2.md) | 约束文档目录收敛与包结构抽取 | ✅ 完成 |
| [plan_3.hotfix_3.md](../plan_3/plan_3.hotfix_3.md) | Docker 部署结构抽取 | ✅ 完成 |
| [plan_3.hotfix_4.md](../plan_3/plan_3.hotfix_4.md) | Plan 工作流约束抽取 | ✅ 完成 |
| [plan_3.hotfix_5.md](../plan_3/plan_3.hotfix_5.md) | Plan 目录整理、索引抽取、plan_main 收敛和工作流约束更新 | ✅ 完成 |

## Plan_4 明细

| 文件 | 主要功能 | 状态 |
| --- | --- | --- |
| [plan_4.md](../plan_4/plan_4.md) | Sparse CAS 状态推进、chunk_fts 幂等写入、SparseEmbeddingCron 定时构建 FTS 索引 | ✅ 完成 |

## Plan_5 明细

| 文件 | 主要功能 | 状态 |
| --- | --- | --- |
| [plan_5.md](../plan_5/plan_5.md) | Java module 拆分，包含 `ai.cerbur.crag` 包名迁移、`crag-admin` API service 和 multi-module 迁移 | ✅ 完成 |

## Plan_6 明细

| 文件 | 主要功能 | 状态 |
| --- | --- | --- |
| [plan_6.md](../plan_6/plan_6.md) | Sparse/Dense 查询、RRF、Rerank、UserQuery、LLM 全链路 | ⏳ 待开始 |

---

## 维护规则

- 新主计划必须创建为 `plan/plan_N/plan_N.md`，其中 `N` 使用连续数字。
- 新 hotfix 必须放在对应主计划目录，例如 `plan/plan_3/plan_3.hotfix_5.md`。
- 不再新增 `plan_1.1`、`plan_2.1` 这类小数计划；现有小数计划仅作为历史文件保留。
- 每次新增、完成或迁移计划后，同步更新本索引。
- 具体命名、进度表和上下文读取规则以 [`constraints/plan-workflow.md`](../../constraints/plan-workflow.md) 为准。
