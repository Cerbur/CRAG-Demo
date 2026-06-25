# Plan Index

> 最后更新：2026-06-25（plan_6.hotfix_7.2 独立验收通过：Sparse searchFts OR partial-match 修复经 Docker 回归 query_stub/retrieval_evidence 双 PASS、psql 边界对比 OR=1 vs 旧 AND=0、独立 sparse=1 证据确认；整份 plan_6.hotfix_7（7.1 Dense ivfflat→hnsw + 7.2 Sparse AND→OR）完成，移出验收队列）

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
│   ├── plan_5.md
│   └── plan_5.hotfix_1.md
├── plan_6/
│   ├── plan_6.md
│   ├── plan_6.hotfix_1.md
│   ├── plan_6.hotfix_2.md
│   ├── plan_6.hotfix_3.md
│   ├── plan_6.hotfix_4.md
│   ├── plan_6.hotfix_5.md
│   ├── plan_6.hotfix_6.md
│   └── plan_6.hotfix_7.md
├── plan_7/
│   └── plan_7.md
├── plan_8/
│   └── plan_8.md
├── plan_9/
│   ├── plan_9.md
│   ├── plan_9.hotfix_1.md
│   ├── plan_9.hotfix_2.md
│   ├── plan_9.hotfix_3.md
│   ├── plan_9.hotfix_4.md
│   └── plan_9.hotfix_5.md
├── plan_10/
│   ├── plan_10.md
│   └── plan_10.hotfix_1.md
├── plan_11/
│   └── plan_11.md
├── plan_12/
│   └── plan_12.md
├── plan_13/
│   └── plan_13.md
├── plan_14/
│   └── plan_14.md
├── plan_15/
│   └── plan_15.md
└── plan_archive/
    └── README.md
```

---

## 主计划索引

| Plan | 主要功能 | 状态 | 活跃修正 | 入口 |
| --- | --- | --- | --- | --- |
| plan_main | 项目定位、技术方向、阶段路线和协作约束入口 | ✅ 已收敛 | — | [plan_main.md](../plan_main.md) |
| plan_1 | 项目脚手架、基础设施、分包结构、DAO、Docker 基础环境 | ✅ 完成 | — | [plan_1.md](../plan_1/plan_1.md) |
| plan_2 | AdminRag 写入链路、Chunk 分块、Dense Embedding Cron、Sidecar 支撑 | ✅ 完成 | — | [plan_2.md](../plan_2/plan_2.md) |
| plan_3 | 项目介绍文档、架构 SVG、README 插图、协作约束抽取 | ✅ 完成 | — | [plan_3.md](../plan_3/plan_3.md) |
| plan_4 | Sparse 索引写入链路，完成 ingestion 侧 chunk_fts 构建 | ✅ 完成 | — | [plan_4.md](../plan_4/plan_4.md) |
| plan_5 | Java module 拆分，完成 `ai.cerbur.crag` 包名迁移、multi-module 迁移和启动模块收敛 | ✅ 完成 | — | [plan_5.md](../plan_5/plan_5.md) |
| plan_6 | Retrieval 查询链路，完成 Sparse/Dense/RRF/Rerank | ✅ 完成 | — | [plan_6.md](../plan_6/plan_6.md) |
| plan_7 | Query Parent Context、引用、DeepSeek V4 Flash Anthropic API、正式 UserQuery API 和自动化回归 | ✅ 完成 (8/8) | — | [plan_7.md](../plan_7/plan_7.md) |
| plan_8 | Plan 工作流 v2 工程治理，包含约束、模板、校验器、Gradle 接入与 plan_7 迁移 | ✅ 完成 (6/6) | — | [plan_8.md](../plan_8/plan_8.md) |
| plan_9 | Java 模块边界收紧，包含 crag-api、公开 API 包、crag-smoke 与 ArchUnit | ✅ 完成 (6/6) | — | [plan_9.md](../plan_9/plan_9.md) |
| plan_10 | Docker 正式健康检查与部署验收，包含 Actuator probes、双 App 并存、故障恢复和持久化回归 | ✅ 完成 (3/3) | [plan_10.hotfix_1](../plan_10/plan_10.hotfix_1.md) 🟡待开始 (0/1) | [plan_10.md](../plan_10/plan_10.md) |
| plan_11 | 测试分层与回归工作流治理，包含 Component/Architecture 分类、Docker HTTP 回归和验收规则 | ✅ 完成 (4/4) | — | [plan_11.md](../plan_11/plan_11.md) |
| plan_12 | 约束事实校准与防漂移护栏，包含 Docker 当前事实、受控例外、路由和机械校验 | ✅ 完成 (4/4) | — | [plan_12.md](../plan_12/plan_12.md) |
| plan_13 | Spring Boot 4.1.0、Spring Framework 7、Spring AI 2.0.0 与集中依赖治理基线升级 | ✅ 完成 (3/3) | — | [plan_13.md](../plan_13/plan_13.md) |
| plan_14 | 多服务骨架、gRPC 契约与身份、独立 Schema、五进程 Docker 基线 | ✅ 完成 (14/14) | — | [plan_14.md](../plan_14/plan_14.md) |
| plan_15 | 分布式 Snowflake ID、Redis Worker 租约与 RAG ID `BIGINT` 冷切换 | ✅ 完成 (5/5) | — | [plan_15.md](../plan_15/plan_15.md) |

---

## 当前执行队列

```text
plan_10.hotfix_1 — Docker 回归脚本 wait_for_http_status 计时修正（非优先，闲时修复）
```

- 同一时刻默认只执行队首计划；前置计划完成后才推进下一项。

---

## 当前验收队列

```text
（空）
```

- 仅列出状态为"待验收"的 Plan/Hotfix；验收必须由未参与实现的新 agent session 执行。
- 前置 Plan 在验收完成前不放行后续 Plan。

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
| [plan_3.hotfix_6.md](../plan_3/plan_3.hotfix_6.md) | Java 工程规范分层、约束路由更新与 Spotless 自动格式化 | ✅ 完成 (4/4) |
| [plan_3.hotfix_7.md](../plan_3/plan_3.hotfix_7.md) | 恢复 @Autowired 默认依赖注入规范并修复扩散代码 | ✅ 完成 (2/2) |

## Plan_4 明细

| 文件 | 主要功能 | 状态 |
| --- | --- | --- |
| [plan_4.md](../plan_4/plan_4.md) | Sparse CAS 状态推进、chunk_fts 幂等写入、SparseEmbeddingCron 定时构建 FTS 索引 | ✅ 完成 |

## Plan_5 明细

| 文件 | 主要功能 | 状态 |
| --- | --- | --- |
| [plan_5.md](../plan_5/plan_5.md) | Java module 拆分，包含 `ai.cerbur.crag` 包名迁移、`crag-admin` API service 和 multi-module 迁移 | ✅ 完成 |
| [plan_5.hotfix_1.md](../plan_5/plan_5.hotfix_1.md) | Gradle 依赖分层整理，消除循环依赖风险并移除非必要依赖 | ✅ 完成 |

## Plan_6 明细

| 文件 | 主要功能 | 状态 |
| --- | --- | --- |
| [plan_6.md](../plan_6/plan_6.md) | Retrieval 内部完成 Sparse/Dense 查询、RRF、Rerank | ✅ 完成 |
| [plan_6.hotfix_1.md](../plan_6/plan_6.hotfix_1.md) | Retrieval benchmark 长期化，建立 benchmark 目录、任务链路索引和 build 报告输出 | ✅ 完成 |
| [plan_6.hotfix_2.md](../plan_6/plan_6.hotfix_2.md) | Benchmark skill 化，沉淀随机测试数据生成、评分流程和项目内 skill 任务索引 | ✅ 完成 |
| [plan_6.hotfix_3.md](../plan_6/plan_6.hotfix_3.md) | Benchmark skill 评估集标准优化，补充黄金/对抗/分布样本、置信区间和回归检测能力 | ✅ 完成 |
| [plan_6.hotfix_4.md](../plan_6/plan_6.hotfix_4.md) | Benchmark skill 任务路由补齐，确保 benchmark / evaluation / 回归测试意图索引到 `crag-benchmark` | ✅ 完成 |
| [plan_6.hotfix_5.md](../plan_6/plan_6.hotfix_5.md) | 项目级 OpenCode Plan 执行 Skill，固化 Plan 完整度、SubAgent 实现、Review、测试与验收闭环 | ✅ 完成 |
| [plan_6.hotfix_6.md](../plan_6/plan_6.hotfix_6.md) | 新增 parent evidence 公共入口，区分真实命中与相邻扩展并返回完整 parent Context | ✅ 完成 (3/3) |
| [plan_6.hotfix_7.md](../plan_6/plan_6.hotfix_7.md) | Retrieval 查询召回率修正：Sparse `plainto_tsquery` AND 语义过严 + Dense 召回根因为 `chunk_embedding` 的 ivfflat 索引在空表上创建失效（非 Java、非 plan_15 引入）。6.hotfix_7.1（Dense ivfflat→hnsw）+ 6.hotfix_7.2（Sparse searchFts 改 `to_tsvector`-unnest OR 部分匹配）均独立验收通过 | ✅ 完成 (2/2) |

## Plan_7 明细

| 文件 | 主要功能 | 状态 |
| --- | --- | --- |
| [plan_7.md](../plan_7/plan_7.md) | Query Parent Context、引用、LLM contract/adapter、DeepSeek V4 Flash Anthropic API、正式 UserQuery API 和自动化 HTTP 回归 | ✅ 完成 (8/8) |

## Plan_8 明细

| 文件 | 主要功能 | 状态 |
| --- | --- | --- |
| [plan_8.md](../plan_8/plan_8.md) | Plan 工作流 v2 工程治理，包含约束、模板、静态校验、Gradle 接入和 plan_7 迁移 | ✅ 完成 (6/6) |
| [plan_8.hotfix_1.md](../plan_8/plan_8.hotfix_1.md) | 收敛 Plan 依赖顺序、状态机与跨约束冲突，增强依赖图和执行队列校验 | ✅ 完成 (4/4) |
| [plan_8.hotfix_2.md](../plan_8/plan_8.hotfix_2.md) | workflow v3 独立执行与验收 session、Plan 待验收状态、双队列和 v2 全量迁移 | ✅ 完成 (4/4) |
| [plan_8.hotfix_3.md](../plan_8/plan_8.hotfix_3.md) | 创建项目级 Plan 执行 Skill，覆盖首次执行、恢复和验收退回修复 | ✅ 完成 (1/1) |

## Plan_9 明细

| 文件 | 主要功能 | 状态 |
| --- | --- | --- |
| [plan_9.md](../plan_9/plan_9.md) | Java 模块边界收紧，包含 crag-api 重命名、公开 API 包、Embedding 契约、crag-smoke 和 ArchUnit | ✅ 完成 (6/6) |
| [plan_9.hotfix_1.md](../plan_9/plan_9.hotfix_1.md) | GlobalExceptionHandler HTTP 状态码修正（兜底 500、显式 404） | ✅ 完成 (3/3) |
| [plan_9.hotfix_2.md](../plan_9/plan_9.hotfix_2.md) | 收敛模块与 API 约束中的过期实现事实和已完成迁移历史 | ✅ 完成 (1/1) |
| [plan_9.hotfix_3.md](../plan_9/plan_9.hotfix_3.md) | HTTP API 契约边界收口，包含错误码、DTO 分包、AdminRagResponse、组件测试与 Docker HTTP 回归 | ✅ 完成 (3/3) |
| [plan_9.hotfix_4.md](../plan_9/plan_9.hotfix_4.md) | 修正 review 发现：构造器注入、@WebMvcTest、字段集合断言 | ✅ 完成 (2/2) |
| [plan_9.hotfix_5.md](../plan_9/plan_9.hotfix_5.md) | 四类异常测试迁移到 @WebMvcTest | ✅ 完成 (1/1) |

## Plan_10 明细

| 文件 | 主要功能 | 状态 |
| --- | --- | --- |
| [plan_10.md](../plan_10/plan_10.md) | Docker 正式健康检查与部署验收，包含 Actuator probes、Compose readiness、默认/Smoke 并存、数据库故障恢复和持久化回归 | ✅ 完成 (3/3) |
| [plan_10.hotfix_1.md](../plan_10/plan_10.hotfix_1.md) | Docker 回归脚本 `wait_for_http_status` 墙钟计时修正（`elapsed` 未计入 `curl -m` 耗时；plan_15 验收 Docker 重跑发现，非 plan_15 引入） | 🟡 待开始 (0/1) |

## Plan_11 明细

| 文件 | 主要功能 | 状态 |
| --- | --- | --- |
| [plan_11.md](../plan_11/plan_11.md) | 测试分层与回归工作流治理，包含轻量组件测试、架构测试、Docker HTTP 回归、数据隔离与验收规则 | ✅ 完成 (4/4) |

## Plan_12 明细

| 文件 | 主要功能 | 状态 |
| --- | --- | --- |
| [plan_12.md](../plan_12/plan_12.md) | 校准 Docker 与 Storage 约束事实，增加入口、链接、服务索引和术语防漂移校验 | ✅ 完成 (4/4) |

## Plan_13 明细

| 文件 | 主要功能 | 状态 |
| --- | --- | --- |
| [plan_13.md](../plan_13/plan_13.md) | Spring Boot 4.1.0、Spring Framework 7、Spring AI 2.0.0 与集中依赖治理基线升级 | ✅ 完成 (3/3) |

## Plan_14 明细

| 文件 | 主要功能 | 状态 |
| --- | --- | --- |
| [plan_14.md](../plan_14/plan_14.md) | 多服务骨架、gRPC 契约与身份、独立 Schema、五进程 Docker 基线 | ✅ 完成 (14/14) |

## Plan_15 明细

| 文件 | 主要功能 | 状态 |
| --- | --- | --- |
| [plan_15.md](../plan_15/plan_15.md) | 分布式 Snowflake ID、Redis Worker 租约、时钟回拨处理与 RAG `docId/chunkId/parentChunkId` 冷切换 | ✅ 完成 (5/5) |

---

## 维护规则

- 新主计划必须创建为 `plan/plan_N/plan_N.md`，其中 `N` 使用连续数字。
- 新 hotfix 必须放在对应主计划目录，例如 `plan/plan_3/plan_3.hotfix_5.md`。
- 不再新增 `plan_1.1`、`plan_2.1` 这类小数计划；现有小数计划仅作为历史文件保留。
- 每次新增、完成或迁移计划后，同步更新本索引。
- 具体命名、进度表和上下文读取规则以 [`constraints/plan-workflow.md`](../../constraints/plan-workflow.md) 为准。
