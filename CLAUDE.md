# CRAG-Demo — 项目索引

## 项目概述

基于 RAG（Retrieval-Augmented Generation）的问答机器人后端服务。技术栈：Java 21 + Spring Boot + Gradle + Docker + pgvector。

## 核心规范

1. **规划优先**：所有代码修改必须先落为 `plan/` 目录下的计划文档，不直接在对话中修改代码。
2. **计划分层**：
   - `plan/plan_main.md` — 总业务方向，持续迭代。
   - `plan/plan_N.md`（plan_1, plan_2, plan_3 ... 数字编号）— 具体执行计划，将大任务拆分为小任务，每完成一个小任务更新状态并记录 commit。
   - `plan/plan_{N}.hotfix_{M}.md` — 前置修正计划。准备执行 `plan_{N+1}` 前发现必须先处理的内容，归入上一阶段 `plan_N.hotfix_M`。
   - `plan/plan_archive/` — 方向性变更记录，记录 before / after 及时间。
3. **任务编号规范**：
   - Plan 文件使用连续数字：`plan_1.md` → `plan_2.md` → `plan_3.md`
   - 禁止后续新增小数计划文件：例如 `plan_1.1.md`、`plan_2.1.md`、`plan_2.2.md`
   - 如果准备执行 `plan_2` 前发现 Sidecar、schema、ignore 等前置修正，写入 `plan_1.hotfix_1.md`；还需要修复则继续 `plan_1.hotfix_2.md`
   - 如果准备执行 `plan_3` 前发现 `plan_2` 遗留问题，写入 `plan_2.hotfix_1.md`，以此类推
   - 历史遗留的 `plan_1.1.md`、`plan_2.1.md` 等文件暂不强制重命名，但不得作为新计划命名范式继续使用
   - 每个 plan 内小任务使用 `plan-id.task-id` 编号，如 `1.1`, `1.2`, `2.1`
   - 每完成一个子任务，更新 plan 文档中的 `[ ]` → `[x]`，记录对应 commit hash
4. **Plan 进度追踪表规范**（新增）：
   - 每个 `plan_N.md` 必须在任务详情之前放置进度追踪表，格式如下：

     | 编号 | 任务 | 状态 | 提交 | 完成时间 |
     | --- | --- | --- | --- | --- |

   - 状态图例：⏳ 待开始 / 🔄 进行中 / ✅ 完成 / ❌ 阻塞
   - 表格下方标注整体进度：`整体进度：X / N（Y%）`
   - 每完成一个子任务，同步更新进度表（状态 + 提交 + 完成时间）
5. **README.md** — 使用中文维护，随项目目标持续更新。
6. **开源协议**：MIT。

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
│   ├── chunk/                        — 文档分块（ChunkSplit）
│   ├── dense/                        — Dense 检索通道（Embedding + Query）
│   ├── sparse/                       — Sparse 检索通道（BM25/FTS）
│   ├── rrf/                          — RRF 融合
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
- [x] plan_main 细化 — 混合检索流水线 + 7 项技术决策全部确认
- [x] plan_1 执行计划创建（脚手架 + 分包 + DAO + Dockerfile）→ [plan_1.md](plan/plan_1.md)
- [ ] plan_2 Core 全链路 + LLM 接入
- [ ] plan_3 Docker 化部署 + 联调

## 对话约定

- 每次对话产出为 plan 文档更新，不直接修改代码。
- 如有实现过程中的失误或前置修正，不创建小数 plan；按阶段归属创建 `plan_N.hotfix_M.md`。
- 所有 plan_N / hotfix 执行完成后，更新 `plan/plan_main.md` 标记进度。
- Plan 数字编号连续递增，子任务编号 `plan-id.task-id`。
