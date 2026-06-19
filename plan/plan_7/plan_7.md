---
workflow_version: 2
plan_id: plan_7
type: main
status: blocked
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
- 暴露 `POST /api/v1/query`，补充纯单元、轻量组件测试与自动化 Docker HTTP 回归。

## 非目标

- 不修改 Sparse、Dense、RRF 或 Rerank 内部算法。
- 不实现流式输出、鉴权、多租户、对话记忆或 Prompt 管理平台。
- 不绕过 Docker 直接启动 Java、Python 或临时数据库做真实运行时业务链路验证。

## 前置依赖

- **执行前置 Plan**：`plan_9`
- `plan_6` 已完成 Retrieval 查询链路，Query 只依赖 retrieval 门面能力。
- `plan_5` 已完成 Java module 拆分，Query 新代码落入 `crag-query` 模块。
- `plan_9` 必须先完成模块边界迁移；本计划随后使用 `crag-api` 和各领域 `api` 包执行，`crag-smoke` 仅保留内部诊断职责。
- `plan_9` 完成后本计划先转为 `draft`，校准 Spring AI 版本、配置属性、确定性 LLM Stub、文件边界与验收命令。
- DeepSeek API 凭据可用是本计划从 `draft` 转为 `ready` 的前提，凭据不得写入仓库。

## 文件边界

- `crag-query/src/**`
- `crag-api/src/**`（仅 UserQuery HTTP 入口与 DTO；由 plan_9 建立）
- `crag-app/src/**`（仅运行时配置与装配）
- `build.gradle.kts`
- `crag-query/build.gradle.kts`
- `crag-api/build.gradle.kts`
- `crag-app/build.gradle.kts`
- `scripts/tests/http/**`
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
- Query 必跑回归使用确定性 LLM Stub，并从正式 `POST /api/v1/query` 入口执行自动化 Docker HTTP 回归。
- 真实 DeepSeek 调用是完成门槛；缺少凭据不得静默跳过或把 Plan 标记为完成。
- `crag-smoke` 只诊断内部阶段，本计划不新增 Query Smoke Controller。

## 未决问题

- 阻塞解除后、转为 `ready` 前确认当前 Spring AI 版本、DeepSeek ChatClient 配置属性、Stub 激活方式和凭据可用性；确认结果写入关键决策与变更记录。

## 风险与回滚

- 外部 LLM 不可用或限流：映射为可理解错误并通过纯单元测试覆盖失败路径。
- Context 过长：在 Query 内实施明确上限，边界输入必须测试。
- sources 与实际 context 漂移：从同一排序结果生成，测试顺序、截断与元信息映射。
- 配置或依赖接入失败时，回滚对应任务提交；本计划不迁移数据库，无不可逆数据变更。

## 测试与验证计划

- 纯单元与轻量组件测试：按 `plan_11` 完成后的分类执行 `./gradlew :crag-query:test :crag-api:test`，覆盖 context、sources、服务编排、请求校验和外部依赖失败。
- 全量回归：`./gradlew test`。
- Docker HTTP 回归：使用 `docker compose up -d --build` 启动完整依赖，运行 `scripts/tests/http/` 中基于确定性 Stub 的 Query 脚本。
- 条件验收：使用真实 DeepSeek 凭据调用正式 Query API，验证供应商配置与协议。
- 最终执行 `python3 scripts/validate_plans.py --strict --verify-git plan/plan_7/plan_7.md`。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 7.1 | Query 侧 Context 工程与 sources 结构 | ⏳ 待开始 | — | — |
| 7.2 | 建立 LLM 契约与确定性 Stub | ⏳ 待开始 | — | — |
| 7.3 | 接入 DeepSeek Adapter 并实现 UserQueryService | ⏳ 待开始 | — | — |
| 7.4 | UserQueryController 实现 | ⏳ 待开始 | — | — |
| 7.5 | 自动化 Query HTTP 回归与真实 DeepSeek 验收 | ⏳ 待开始 | — | — |

整体进度：0 / 5（0%）

## 7.1 Query 侧 Context 工程与 sources 结构

**目标**：把 Retrieval 返回的已排序 chunks 组装为受限 context，并生成顺序稳定、可追溯的 sources。

**前置任务**：无

**范围**：定义 Query 侧 context、source 与答案响应模型；实现长度上限、空结果和元信息映射。

**非目标**：不修改 Retrieval 排序，不调用 LLM，不暴露 HTTP。

**验收标准**：sources 可追溯到 chunk/document 元信息；context 长度有上限；空结果、截断边界和顺序稳定均有纯单元测试。

**验证方式**：运行 `./gradlew :crag-query:test`，核对正常、空输入、超长输入和 sources 映射用例。

**涉及文件**：`crag-query/src/main/**`、`crag-query/src/test/**`

## 7.2 建立 LLM 契约与确定性 Stub

**目标**：先建立供应商无关的 LLM 契约和可重复的确定性 Stub，为业务编排与 HTTP 回归提供稳定依赖。

**前置任务**：7.1

**范围**：LLM 请求/结果契约、失败语义、确定性 Stub、Profile 或配置切换及纯单元测试。

**非目标**：不接入真实 DeepSeek，不实现 UserQueryService、流式生成或多供应商动态切换。

**验收标准**：Stub 对固定输入产生确定输出；失败模式可控；业务契约不暴露 Spring AI 或供应商 SDK 类型。

**验证方式**：运行 `./gradlew :crag-query:test`，覆盖确定输出、空输入和失败模式。

**涉及文件**：`crag-query/src/**`、`crag-app/src/**`

## 7.3 接入 DeepSeek Adapter 并实现 UserQueryService

**目标**：通过 Spring AI 接入真实 DeepSeek，并在 UserQueryService 串联 Retrieval、Context 与 LLM。

**前置任务**：7.2

**范围**：Spring AI 依赖与配置、DeepSeek adapter、UserQueryService、失败映射，以及纯单元与必要的轻量组件测试。

**非目标**：不实现流式生成、重试框架、对话记忆或第二供应商。

**验收标准**：正常返回 answer 与 sources；Retrieval 空结果和 LLM 失败行为明确；Query 不依赖 Retrieval 内部组件或供应商 SDK 类型。

**验证方式**：运行 `./gradlew :crag-query:test`，检查模块依赖并覆盖成功与失败路径。

**涉及文件**：`crag-query/**`、`crag-app/src/**`、`crag-query/build.gradle.kts`、`crag-app/build.gradle.kts`、`.env.example`

## 7.4 UserQueryController 实现

**目标**：提供 `POST /api/v1/query` 的稳定请求与响应契约。

**前置任务**：7.3

**范围**：请求校验、Controller、DTO、错误响应转换和 HTTP 层轻量组件测试。

**非目标**：不增加鉴权、流式接口、Smoke Controller 或第二套 Query API。

**验收标准**：合法请求返回 answer 与 sources；空问题和非法输入被拒绝；错误响应与现有 API 风格一致。

**验证方式**：运行 `./gradlew :crag-api:test`，覆盖成功、校验失败和服务异常转换。

**涉及文件**：`crag-api/src/main/**`、`crag-api/src/test/**`、`crag-api/build.gradle.kts`

## 7.5 自动化 Query HTTP 回归与真实 DeepSeek 验收

**目标**：从正式 HTTP 入口证明 Stub 模式可重复回归，并验证真实 DeepSeek 配置与协议可用。

**前置任务**：7.1、7.2、7.3、7.4

**范围**：补齐缺失测试、Compose 配置、确定性 Stub HTTP 脚本、真实供应商调用和验收证据。

**非目标**：不新增 Query Smoke Controller，不以手工 curl 作为唯一证据，不扩展 benchmark。

**验收标准**：核心正常、边界与失败路径测试通过；自动化脚本通过正式 API 返回确定 answer 与可追溯 sources；真实 DeepSeek 调用通过；所有命令与摘要回填。

**验证方式**：运行 `./gradlew test`；使用 Docker Compose 后执行 `scripts/tests/http/` Query 回归；使用真实凭据调用 `POST /api/v1/query`；检查日志并清理本次 runId 数据。

**涉及文件**：`crag-query/src/test/**`、`crag-api/src/test/**`、`scripts/tests/http/**`、`docker-compose.yml`、`.env.example`、`README.md`、`plan/plan_7/plan_7.md`

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |

## 阻塞记录

- **日期**：2026-06-19
- **原因**：`plan_9` 将先完成 `crag-admin → crag-api`、公开 API 包和 `crag-smoke` 迁移，避免本计划向旧边界继续新增代码后再次搬迁。
- **当前进度**：5 个任务均未开始，无需回滚实现。
- **解除条件**：`plan_9` 完成并通过 Architecture、纯单元、轻量组件测试及默认/smoke Docker HTTP 验收。
- **解除方**：`plan_9` owner。
- **恢复后的下一步**：转为 `draft`，重新读取迁移后的公开 API，确认 Spring AI、LLM Stub、DeepSeek 凭据与文件边界；提交校准后的 Plan 和索引，再转为 `ready`。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-18 | 创建 plan_7 | 从 plan_6 拆分 Query 链路 | 建立 4 项业务任务 |
| 2026-06-19 | 迁移为 workflow v2，状态为待开始 | plan_8 工作流治理 | 补齐元信息、边界、固定任务结构与验证计划；业务范围不变 |
| 2026-06-19 | 状态调整为阻塞并增加 plan_9 前置依赖 | 避免 Query 功能继续写入即将废弃的模块与包边界 | 业务目标不变；执行路径切换到 crag-api、公开 api 包与 crag-smoke |
| 2026-06-19 | 重拆为 5 项串行任务并收紧完成门槛 | 先建立 Stub，正式 API 回归不得混入 Smoke，真实 DeepSeek 必须验收 | plan_9 完成后先回 draft 校准，不直接恢复 ready |
