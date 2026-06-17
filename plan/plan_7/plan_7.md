# plan_7 — Query 问答链路

> 创建日期：2026-06-18  
> 状态：⏳ 待开始
> 来源：由 `plan_6` 拆分迁移，主攻 Query 链路、Prompt 拼接、LLM 接入和 UserQuery API。

## 范围

本计划覆盖 Query 问答链路：

1. `crag-query` 调用 `RetrievalService` 获取已完成召回、融合和重排的 chunks。
2. 构建可控长度的 prompt context，并保留 answer sources。
3. 接入 LLM Client，在 UserQueryService 中编排 retrieval、prompt、LLM 生成。
4. 暴露 UserQuery API，并补充单测与端到端冒烟验证。

**前置依赖**：

- `plan_6` 已完成 Retrieval 查询链路，Query 只依赖 retrieval 门面能力。
- `plan_5` 已完成 Java module 拆分，Query 新代码落入 `crag-query` 模块。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 7.1 | Query 侧 Context 工程与 sources 结构 | ⏳ | — | — |
| 7.2 | LLM Client 接入与 UserQueryService 编排 | ⏳ | — | — |
| 7.3 | UserQueryController 实现 | ⏳ | — | — |
| 7.4 | 单元测试与端到端冒烟测试 | ⏳ | — | — |

整体进度：0 / 4（0%）

## 7.1 Query 侧 Context 工程与 sources 结构

`crag-query` 调用 `RetrievalService` 获取已经完成召回、融合和重排的 chunks，将其组装为 LLM prompt context，并保留 sources。

**验收**：sources 可追溯到 chunk/document 元信息；context 长度有上限保护。

## 7.2 LLM Client 接入与 UserQueryService 编排

接入 DeepSeek / Spring AI，并在 UserQueryService 中串联 retrieval、context、LLM 生成。UserQueryService 只调用 retrieval 门面方法获取 chunks，不感知 Sparse/Dense/RRF/Rerank 的内部步骤。

**验收**：正常返回 answer；LLM 失败时返回可理解错误。

## 7.3 UserQueryController 实现

实现 `POST /api/v1/query`。

**验收**：请求校验、响应结构和错误响应与现有 API 风格一致。

## 7.4 单元测试与端到端冒烟测试

补充核心服务单测，并通过 Docker Compose 或本地依赖完成端到端冒烟验证。

**验收**：遵守 `constraints/test-workflow.md`；测试结果回填本计划。
