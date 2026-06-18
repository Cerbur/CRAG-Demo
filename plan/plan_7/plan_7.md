---
workflow_version: 2
plan_id: plan_7
type: main
status: ready
owner: parent-agent
created: 2026-06-18
updated: 2026-06-19
---

# plan_7 — Query 问答链路

## 背景与目标

`plan_6` 已完成 Retrieval 的 Sparse、Dense、RRF 和 Rerank 链路。本计划实现 Query 领域：接收用户问题、调用 Retrieval 获取 chunks、构建受限 context、调用 LLM，并返回 answer 与可追溯 sources。

## 范围

- `crag-query` 调用 `RetrievalService` 获取已完成召回、融合和重排的 chunks。
- 构建可控长度的 prompt context，并保留 answer sources。
- 接入 DeepSeek / Spring AI，在 UserQueryService 中编排 retrieval、prompt 与生成。
- 暴露 `POST /api/v1/query`，补充单元测试与 Docker Compose 端到端冒烟验证。

## 非目标

- 不修改 Sparse、Dense、RRF 或 Rerank 内部算法。
- 不实现流式输出、鉴权、多租户、对话记忆或 Prompt 管理平台。
- 不绕过 Docker 直接启动 Java、Python 或临时数据库做非单元验证。

## 前置依赖

- `plan_6` 已完成 Retrieval 查询链路，Query 只依赖 retrieval 门面能力。
- `plan_5` 已完成 Java module 拆分，Query 新代码落入 `crag-query` 模块。
- DeepSeek API 凭据与 Spring AI 具体配置在执行前确认，不写入仓库。

## 文件边界

- `crag-query/src/**`
- `crag-admin/src/**`（仅 UserQuery HTTP 入口与 DTO）
- `crag-app/src/**`（仅运行时配置与装配）
- `build.gradle.kts`
- `crag-query/build.gradle.kts`
- `crag-admin/build.gradle.kts`
- `crag-app/build.gradle.kts`
- `docker-compose.yml`
- `.env.example`
- `constraints/package-structure.md`
- `constraints/docker-structure.md`
- `README.md`
- `plan/plan_7/plan_7.md`
- `plan/index/README.md`

## 关键决策

- Query 只依赖 Retrieval 门面，不感知 Sparse、Dense、RRF、Rerank 的内部步骤。
- Context 由排序后的 child chunks 组装，必须有长度上限并保留稳定 sources 映射。
- LLM 接入位于 integration 边界；业务编排不直接依赖供应商 SDK 类型。
- 一期使用 DeepSeek API + Spring AI；密钥仅通过环境变量注入。
- 非单元验证遵守 `constraints/test-workflow.md`，统一使用 Docker Compose。

## 未决问题

- 非阻塞：执行 7.2 时根据当前 Spring AI 版本确认 DeepSeek ChatClient 配置属性名称；由 Plan owner 在修改构建或配置前记录最终选择，不改变既定供应商与边界。

## 风险与回滚

- 外部 LLM 不可用或限流：映射为可理解错误并通过单元测试覆盖失败路径。
- Context 过长：在 Query 内实施明确上限，边界输入必须测试。
- sources 与实际 context 漂移：从同一排序结果生成，测试顺序、截断与元信息映射。
- 配置或依赖接入失败时，回滚对应任务提交；本计划不迁移数据库，无不可逆数据变更。

## 测试与验证计划

- 单元测试：`./gradlew :crag-query:test :crag-admin:test`，覆盖 context、sources、服务编排、请求校验和外部依赖失败。
- 全量回归：`./gradlew test`。
- 非单元测试：使用 `docker compose up -d --build` 启动完整依赖，通过 Compose 暴露端口调用 `POST /api/v1/query`。
- 最终执行 `python3 scripts/validate_plans.py --strict --verify-git plan/plan_7/plan_7.md`。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 7.1 | Query 侧 Context 工程与 sources 结构 | ⏳ 待开始 | — | — |
| 7.2 | LLM Client 接入与 UserQueryService 编排 | ⏳ 待开始 | — | — |
| 7.3 | UserQueryController 实现 | ⏳ 待开始 | — | — |
| 7.4 | 单元测试与端到端冒烟测试 | ⏳ 待开始 | — | — |

整体进度：0 / 4（0%）

## 7.1 Query 侧 Context 工程与 sources 结构

**目标**：把 Retrieval 返回的已排序 chunks 组装为受限 context，并生成顺序稳定、可追溯的 sources。

**前置任务**：无

**范围**：定义 Query 侧 context、source 与答案响应模型；实现长度上限、空结果和元信息映射。

**非目标**：不修改 Retrieval 排序，不调用 LLM，不暴露 HTTP。

**验收标准**：sources 可追溯到 chunk/document 元信息；context 长度有上限；空结果、截断边界和顺序稳定均有单元测试。

**验证方式**：运行 `./gradlew :crag-query:test`，核对正常、空输入、超长输入和 sources 映射用例。

**涉及文件**：`crag-query/src/main/**`、`crag-query/src/test/**`

## 7.2 LLM Client 接入与 UserQueryService 编排

**目标**：通过供应商无关边界接入 DeepSeek，并在 UserQueryService 串联 Retrieval、Context 与 LLM。

**前置任务**：7.1

**范围**：Spring AI 依赖与配置、LLM integration adapter、UserQueryService、失败映射及单元测试。

**非目标**：不实现流式生成、重试框架、对话记忆或多供应商动态切换。

**验收标准**：正常返回 answer 与 sources；Retrieval 空结果和 LLM 失败返回明确行为；Query 不依赖 Retrieval 内部组件或供应商 SDK 类型。

**验证方式**：运行 `./gradlew :crag-query:test`，检查模块依赖并通过服务编排测试覆盖成功与失败路径。

**涉及文件**：`crag-query/**`、`crag-app/src/**`、`crag-query/build.gradle.kts`、`crag-app/build.gradle.kts`、`.env.example`

## 7.3 UserQueryController 实现

**目标**：提供 `POST /api/v1/query` 的稳定请求与响应契约。

**前置任务**：7.2

**范围**：请求校验、Controller、DTO、错误响应转换和 HTTP 层单元测试。

**非目标**：不增加鉴权、流式接口或第二套 Query API。

**验收标准**：合法请求返回 answer 与 sources；空问题和非法输入被拒绝；错误响应与现有 API 风格一致。

**验证方式**：运行 `./gradlew :crag-admin:test`，覆盖成功、校验失败和服务异常转换。

**涉及文件**：`crag-admin/src/main/**`、`crag-admin/src/test/**`、`crag-admin/build.gradle.kts`

## 7.4 单元测试与端到端冒烟测试

**目标**：完成 Query 链路回归并证明 Docker Compose 环境可从 HTTP 问题得到 answer 与 sources。

**前置任务**：7.1、7.2、7.3

**范围**：补齐缺失单元测试、Compose 配置、端到端请求和验收证据。

**非目标**：不以宿主机直接启动服务替代 Docker，不把 benchmark 扩展为本任务内容。

**验收标准**：核心正常、边界与失败路径单测通过；Docker Compose 中接口返回可理解答案和可追溯 sources；所有命令与摘要回填。

**验证方式**：运行 `./gradlew test`；使用 `docker compose up -d --build` 后调用 `POST /api/v1/query`；完成后检查 Compose 日志并清理环境。

**涉及文件**：`crag-query/src/test/**`、`crag-admin/src/test/**`、`docker-compose.yml`、`README.md`、`plan/plan_7/plan_7.md`

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |

## 阻塞记录

无。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-18 | 创建 plan_7 | 从 plan_6 拆分 Query 链路 | 建立 4 项业务任务 |
| 2026-06-19 | 迁移为 workflow v2，状态为待开始 | plan_8 工作流治理 | 补齐元信息、边界、固定任务结构与验证计划；业务范围不变 |
