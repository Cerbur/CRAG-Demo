---
workflow_version: 3
plan_id: plan_7
type: main
status: draft
created: 2026-06-18
updated: 2026-06-19
---

# plan_7 — Query Parent Context 与 DeepSeek 问答链路

## 背景与目标

`plan_6.hotfix_6` 将在 Retrieval 内完成 child 检索结果到完整 parent evidence 的聚合，`plan_13` 将提供 Spring Boot 4.1.0 与 Spring AI 2.0.0 基线。本计划实现 Query 领域：接收用户问题、获取排序后的 parent evidence、构建受限 Context 和可引用 Prompt、通过中立 LLM 契约调用确定性 Stub 或 DeepSeek V4 Flash，并从正式 API 返回 answer 与可追溯 sources。

## 范围

- Query 配置绑定、合法性校验和 LLM Adapter 条件装配。
- 基于 parent evidence 构建字符预算受控的 Context、`[Sx]` 引用和 Prompt。
- 建立 Query 业务层、LLM 中立 contract 与 Provider Adapter 三层边界。
- 提供确定性 Stub Adapter 和 DeepSeek V4 Flash Adapter。
- 在 `UserQueryService` 编排 Retrieval、Context、Prompt、LLM、sources 与引用分析。
- 暴露 `POST /api/v1/query`，返回 answer 与 parent 维度 sources。
- 增加纯单元、轻量组件、架构测试、Stub Docker HTTP 回归和真实 DeepSeek 条件验收。

## 非目标

- 不修改 Sparse、Dense、RRF、Rerank 或 Parent Evidence 聚合算法。
- 不直接访问 Storage、DAO、Repository 或 Retrieval 内部阶段。
- 不实现流式输出、鉴权、多租户、对话记忆、Prompt 管理平台或 HTTP 调用方自定义模型参数。
- 不实现自动重试、熔断或多供应商降级；仅保留关联后续 Plan 的明确 TODO。
- 不新增 Query Smoke Controller，不绕过 Docker 启动真实业务链路。
- 不在本计划升级 Spring Boot 或 Spring AI 基线。

## 前置依赖

- **执行前置 Plan**：`plan_6.hotfix_6`、`plan_13`
- `plan_6.hotfix_6` 必须先提供 `RetrievalService.retrieveEvidence()` 与 `ParentEvidenceResult`。
- `plan_13` 必须先完成 Spring Boot 4.1.0、Spring AI 2.0.0 和现有回归迁移。
- `plan_9` 已完成 `crag-api`、公开 API 包与 `crag-smoke` 隔离。
- `plan_9.hotfix_3` 已完成 HTTP DTO 分包、稳定错误码和 API 组件测试基线。
- DeepSeek 凭据不是 Plan 转为 `ready` 的前提；它只阻塞任务 7.7 的真实供应商验收。
- 本计划在两个直接前置完成后重新读取其公开 API、依赖和验收证据，校准文件路径后转为 `ready`。

## 文件边界

- `crag-query/src/**`
- `crag-query/build.gradle.kts`
- `crag-api/src/**`
- `crag-api/build.gradle.kts`
- `crag-common/src/main/java/ai/cerbur/crag/common/dto/result/**`
- `crag-app/src/main/resources/**`
- `crag-app/src/test/**`
- `crag-app/build.gradle.kts`
- `docker-compose.yml`
- `.env.example`
- `scripts/tests/http/**`
- `constraints/api-style.md`
- `constraints/package-structure.md`
- `constraints/docker-structure.md`
- `README.md`
- `plan/plan_7/plan_7.md`
- `plan/index/README.md`

## 包结构与依赖流

```text
crag-api
└── ai.cerbur.crag.api
    ├── controller/UserQueryController
    └── dto.query/
        ├── UserQueryRequest
        ├── UserQueryResponse
        └── QuerySourceResponse
                │
                ▼
crag-query
└── ai.cerbur.crag.query
    ├── api/
    │   ├── UserQueryService
    │   ├── UserQueryResult
    │   ├── QuerySource
    │   └── QueryUnavailableException
    ├── context/
    │   ├── ContextBuilder
    │   └── QueryContext
    ├── prompt/
    │   └── PromptBuilder
    ├── reference/
    │   ├── ReferenceAnalyzer
    │   └── ReferenceAnalysis
    ├── llm/
    │   ├── contract/
    │   │   ├── LlmClient
    │   │   ├── LlmRequest
    │   │   ├── LlmResult
    │   │   └── LlmUnavailableException
    │   ├── adapter/
    │   │   ├── deepseek/DeepSeekLlmAdapter
    │   │   └── stub/StubLlmAdapter
    │   └── config/
    │       ├── QueryProperties
    │       └── QueryLlmConfiguration
    │
    └──────────────► ai.cerbur.crag.retrieval.api
                     RetrievalService / ParentEvidenceResult
```

依赖方向固定为：

```text
HTTP Adapter → Query 公开 API → Query 业务逻辑 → LLM contract
                                                   ▲
                                      Provider Adapter
```

- Query 业务层决定 Retrieval、Context、Prompt、sources 和失败语义。
- `llm.contract` 只表达供应商无关的生成能力，不出现 Spring AI、DeepSeek、HTTP 或模型名。
- `llm.adapter` 负责 contract 与具体 Provider 的双向转换。
- `QueryLlmConfiguration` 只选择和装配 Adapter，不参与业务流程。
- `crag-app` 只保存配置值，不新增 Query Java 装配或业务调用。

## 关键决策

- 调用流程固定为：

```text
UserQueryController
→ UserQueryService.answer(question)
→ RetrievalService.retrieveEvidence(question, topN)
→ ContextBuilder.build(parentEvidence, maxCharacters)
→ 空 Context：直接返回“知识库证据不足”，不调用 LLM
→ PromptBuilder.build(question, queryContext)
→ LlmClient.generate(llmRequest)
→ ReferenceAnalyzer 分析 answer 中的 [Sx]
→ UserQueryResult(answer, sources)
→ Controller 映射为 HTTP DTO
```

- Context 以完整 parent chunk 为单位，按 Retrieval 排序依次加入；总预算按字符数计算，默认 `12000`。
- 单个 parent 超过剩余预算时完整跳过并继续尝试后续 evidence，不截断文本；日志记录 `parentChunkId`、字符数和预算，不记录内容。
- Retrieval `topN` 默认 `8`，表示 parent evidence 数量。
- 每个纳入 Context 的 parent 获得请求内临时脚注编号 `S1`、`S2`；同一编号在 Prompt、answer 引用和 API source 中对应同一 parent。
- `QuerySource` 包含 `reference`、`parentChunkId`、`matchedChildIds`；不返回 parent 内容、检索分数、单个 `chunkIndex` 或模型参数。
- API `sources` 返回全部实际送入模型的 parent evidence，不根据模型是否引用进行过滤。
- Prompt 使用 system/user 两类消息；要求模型仅依据 Context 回答、在对应陈述后就近引用 `[Sx]`、禁止虚构编号，证据不足时回答固定文案。
- `LlmRequest` 包含 `systemPrompt`、`userPrompt` 和 `sourceCount`；Provider Adapter 映射为 Spring AI Message/Prompt。
- DeepSeek Adapter 直接依赖 Spring AI `ChatModel`，不使用 Advisors、Memory 或 Spring AI RAG 抽象。
- Provider 通过 `crag.query.llm.provider=stub|deepseek` 选择，默认 `stub`；不复用 `smoke` Profile。
- Stub Adapter 支持 `success|failure` 模式；成功答案确定，失败统一抛中立异常，不通过特殊问题或测试 HTTP 参数触发。
- DeepSeek 默认模型为 `deepseek-v4-flash`，temperature 为 `0`，显式关闭 reasoning；模型和 temperature 可配置但不暴露给 HTTP 调用方。
- `DEEPSEEK_API_KEY` 是唯一密钥变量。仅 `provider=deepseek` 时启动校验非空；Stub 模式不要求凭据。
- DeepSeek 连接超时默认 5 秒、响应超时默认 60 秒；一期不自动重试，避免重复计费和延迟失控。
- DeepSeek 认证、限流、超时、空响应或不可解析响应统一转换为 Query 公开的 `QueryUnavailableException`，由 API 映射为 `LLM_UNAVAILABLE` / HTTP 502；不降级为 Stub。
- Retrieval 正常空结果返回 HTTP 200、固定答案“知识库证据不足”和空 sources；Retrieval 异常保留为内部错误，不伪装成空知识库。
- 引用分析是旁路观测：解析 `[S数字]` 并记录引用总数、有效数、无效数和未引用 source 数；分析失败只记 warning，不修改 answer 或 HTTP 结果。
- 每次 Query 记录 requestId、provider、model、retrieved parent 数、纳入 Context 数、Context 字符数、引用统计、总耗时和结果；禁止记录完整 question、Context、Prompt、answer、密钥或 Authorization。
- 在 DeepSeek Adapter 的失败策略附近保留 TODO，明确后续通过独立 Plan 实现重试、熔断和供应商降级；TODO 必须引用后续 Plan，不允许无归属悬挂。

## 配置契约

```yaml
crag:
  query:
    retrieval:
      top-n: 8
    context:
      max-characters: 12000
    llm:
      provider: stub
      connect-timeout: 5s
      response-timeout: 60s
      deepseek:
        api-key: ${DEEPSEEK_API_KEY:}
        model: deepseek-v4-flash
        temperature: 0
        reasoning-enabled: false
      stub:
        mode: success
```

Compose 显式支持：

```text
CRAG_QUERY_LLM_PROVIDER
DEEPSEEK_API_KEY
CRAG_QUERY_LLM_DEEPSEEK_MODEL
CRAG_QUERY_LLM_DEEPSEEK_TEMPERATURE
CRAG_QUERY_LLM_STUB_MODE
```

未知 provider、非法 Stub mode、非正 `top-n`、非正 Context 预算或 DeepSeek 模式缺少 API key 均导致启动失败并输出不含密钥的明确错误。

## HTTP 契约

请求：

```json
{
  "question": "项目使用什么数据库和向量存储？"
}
```

- `question` trim 后长度为 1 至 2000。
- 调用方不能覆盖 topN、Context 预算、模型、temperature 或 Provider。

成功响应：

```json
{
  "success": true,
  "code": 0,
  "result": {
    "answer": "项目使用 PostgreSQL，并通过 pgvector 存储向量。[S1]",
    "sources": [
      {
        "reference": "S1",
        "parentChunkId": "p100",
        "matchedChildIds": ["c101", "c102"]
      }
    ]
  }
}
```

## 未决问题

- `DEEPSEEK_API_KEY` 当前是否可用尚未确认，但只影响 7.7 的真实 Provider 验收，不阻塞前置任务执行。
- `plan_6.hotfix_6` 与 `plan_13` 完成后必须按实际公开 API 和 Spring AI 2 最终配置属性校准路径；负责人为 Plan owner，处理时机为本计划转 `ready` 前。

## 风险与回滚

- Parent Context 过长：通过完整 parent 跳过和字符预算限制；日志只记录 parent 标识与长度。
- Provider 不可用或协议变化：统一 502，保留 cause 和安全日志，不自动切换 Stub。
- sources 与 Context 漂移：两者从同一 `QueryContext` 生成，测试顺序、预算和映射。
- 模型可能漏标或虚构引用：旁路分析记录质量，不篡改回答；后续 evaluation 可消费指标。
- 配置错误导致应用无法启动：通过绑定校验提供明确失败，Stub 默认保证无密钥环境可运行。
- 自动化回归依赖异步 Dense/Sparse 索引：脚本使用唯一 runId 和有限轮询，不直接查询数据库或调用 Smoke 端点。
- 每项任务独立提交，可逆序撤销 HTTP、业务编排、Provider、Stub、Prompt 与配置提交；无数据库迁移和不可逆数据变更。
- 若真实凭据、额度、网络或 Provider 可用性不足，7.7 与 Plan 转为 blocked；前面已完成任务不回退。

## 测试与验证计划

- 纯单元测试：`./gradlew :crag-query:test`，覆盖配置值校验、Context 预算、完整 parent 跳过、sources、Prompt、Stub、DeepSeek 映射、业务编排和引用分析。
- 轻量组件测试：`./gradlew :crag-query:test :crag-api:test :crag-app:test`，覆盖条件 Bean、配置绑定、DeepSeek 模式启动校验、MVC 请求校验、DTO 映射与 502 异常转换；不访问真实网络。
- 架构测试：`./gradlew :crag-app:test --tests '*ArchitectureTest'`，确认 API 只依赖 `query.api`，Query 只依赖 `retrieval.api`，Spring AI 类型只存在于 DeepSeek Adapter/配置边界。
- 全量测试：`./gradlew test` 与 `./gradlew check`。
- Stub Docker HTTP 回归：`docker compose up -d --build` 后通过正式 AdminRag API 写入含唯一 runId 的文档；轮询正式 Query API，每 3 秒一次、最长 90 秒，直到 sources 非空且 Stub answer 符合确定断言。
- Stub 失败回归：通过 `CRAG_QUERY_LLM_STUB_MODE=failure` 启动应用，从正式 Query API 验证 HTTP 502 与 `LLM_UNAVAILABLE`，不增加测试专用参数。
- 真实 DeepSeek 验收：以 `CRAG_QUERY_LLM_PROVIDER=deepseek` 和宿主 `DEEPSEEK_API_KEY` 注入 Compose；正式 API 断言 HTTP 200、`code=0`、answer 非空且不是证据不足、sources 非空且字段完整，并核对 provider/model 日志无敏感信息。
- 测试数据使用唯一 runId；当前无精确删除入口时保留可识别数据，不执行清表、删 volume 或 `docker compose down -v`。
- 最终执行 `python3 scripts/validate_plans.py --strict --verify-git` 与 `git diff --check`。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 7.1 | Query 配置模型、校验和 Adapter 条件装配 | ⏳ 待开始 | — | — |
| 7.2 | Parent Context、sources 与 Prompt 工程 | ⏳ 待开始 | — | — |
| 7.3 | LLM contract 与确定性 Stub Adapter | ⏳ 待开始 | — | — |
| 7.4 | DeepSeek V4 Flash Adapter | ⏳ 待开始 | — | — |
| 7.5 | UserQueryService 编排与引用分析 | ⏳ 待开始 | — | — |
| 7.6 | UserQuery HTTP 契约、错误码和组件测试 | ⏳ 待开始 | — | — |
| 7.7 | Stub Docker HTTP 回归与真实 DeepSeek 验收 | ⏳ 待开始 | — | — |

整体进度：0 / 7（0%）

## 7.1 Query 配置模型、校验和 Adapter 条件装配

**目标**：建立 Query 自有配置契约和可预测的 Provider 选择机制。
**前置任务**：无
**范围**：定义 `QueryProperties`、Provider/Stub mode 枚举和 `QueryLlmConfiguration`；绑定 topN、Context 预算、超时、DeepSeek 与 Stub 配置；实现条件 Bean 与启动校验；补充纯单元和轻量组件测试。
**非目标**：不实现 Adapter 调用、Context、Prompt 或业务编排。
**验收标准**：默认只装配 Stub；DeepSeek 模式只装配 DeepSeek Adapter；缺失 key、未知 provider/mode、非法预算和 topN 启动失败；错误提示不泄露密钥；配置 Java 类全部留在 `crag-query`。
**验证方式**：运行 `./gradlew :crag-query:test :crag-app:test`，覆盖默认值、环境变量覆盖、合法/非法配置和条件装配。
**涉及文件**：`crag-query/src/main/**/llm/config/**`、`crag-query/src/test/**`、`crag-app/src/main/resources/application.yml`、`crag-app/src/test/**`

## 7.2 Parent Context、sources 与 Prompt 工程

**目标**：把排序后的 parent evidence 转换为字符预算受控、引用映射稳定的 Query Context 和结构化 LLM 请求。
**前置任务**：7.1
**范围**：实现 `ContextBuilder`、`QueryContext`、`PromptBuilder` 和内部 source 映射；完整 parent 加入/跳过；生成 `[Sx]`；固定系统规则与 user prompt。
**非目标**：不调用 LLM，不实现 HTTP，不修改 Retrieval。
**验收标准**：默认预算 12000；sources 与实际 Context 一一对应；超长 parent 跳过并继续后续 evidence；全跳过等同空 Context；日志记录 parentChunkId 和长度但不记录内容；Prompt 角色分离且只引用合法编号。
**验证方式**：运行 `./gradlew :crag-query:test`，覆盖空输入、正常、多 parent、超预算、单 parent 超长、顺序与 matched child 映射。
**涉及文件**：`crag-query/src/main/**/context/**`、`crag-query/src/main/**/prompt/**`、`crag-query/src/test/**`

## 7.3 LLM contract 与确定性 Stub Adapter

**目标**：建立供应商无关的 LLM contract 和可重复的 Stub 实现。
**前置任务**：7.2
**范围**：定义 `LlmClient`、`LlmRequest`、`LlmResult`、`LlmUnavailableException`；实现 success/failure Stub Adapter；确定性答案只依赖结构化请求元数据，不解析 Prompt 文本。
**非目标**：不接入 DeepSeek，不实现 UserQueryService 或 HTTP。
**验收标准**：contract 不暴露 Spring AI 或 Provider 类型；相同输入输出完全确定；failure mode 抛统一中立异常；空响应不能伪装成功。
**验证方式**：运行 `./gradlew :crag-query:test`，覆盖确定输出、sourceCount、空输入和失败模式。
**涉及文件**：`crag-query/src/main/**/llm/contract/**`、`crag-query/src/main/**/llm/adapter/stub/**`、`crag-query/src/test/**`

## 7.4 DeepSeek V4 Flash Adapter

**目标**：通过 Spring AI 2 `ChatModel` 把中立 LLM contract 转换为 DeepSeek V4 Flash 调用。
**前置任务**：7.3
**范围**：引入所需 Spring AI DeepSeek 依赖；实现 system/user Message 与 Prompt 映射、model/temperature/reasoning/timeout 配置、响应提取和异常转换；空或不可解析响应视为不可用。
**非目标**：不实现重试、熔断、Fallback、流式生成、Advisor 或 Memory。
**验收标准**：默认模型 `deepseek-v4-flash`、temperature 0、reasoning 关闭；Adapter 只实现 `LlmClient`；认证、限流、超时、协议和空响应统一保留 cause 转换；日志不泄露请求和凭据；后续降级 TODO 关联独立 Plan。
**验证方式**：使用 Mock `ChatModel` 运行 `./gradlew :crag-query:test`，覆盖消息映射、参数、正常响应和各失败类型；运行架构测试确认 Spring AI 类型未穿透。
**涉及文件**：`crag-query/build.gradle.kts`、`crag-query/src/main/**/llm/adapter/deepseek/**`、`crag-query/src/test/**`、`crag-app/src/test/**`

## 7.5 UserQueryService 编排与引用分析

**目标**：串联 Parent Evidence、Context、Prompt 和 LLM，并提供稳定的 Query 公开业务结果。
**前置任务**：7.2、7.3、7.4
**范围**：实现 `UserQueryService`、`UserQueryResult`、`QuerySource`、`QueryUnavailableException`、`ReferenceAnalyzer` 和 requestId/耗时日志；处理空 evidence、Retrieval 失败、LLM 失败与引用旁路分析。
**非目标**：不实现 HTTP DTO，不解析引用来过滤 sources，不修改模型答案。
**验收标准**：正常返回 answer 与全部 Context sources；空 evidence 不调用 LLM 并返回固定文案；Retrieval 异常不伪装空结果；LLM 异常转换为 Query 公开异常；引用统计覆盖正常、重复、无引用与越界引用；分析失败不影响回答。
**验证方式**：运行 `./gradlew :crag-query:test`，Mock Retrieval 与 LLM contract，覆盖完整流程、调用顺序和敏感日志禁令。
**涉及文件**：`crag-query/src/main/**/api/**`、`crag-query/src/main/**/reference/**`、`crag-query/src/test/**`

## 7.6 UserQuery HTTP 契约、错误码和组件测试

**目标**：提供稳定的正式 Query HTTP 边界，并将 Query 供应商失败映射为安全的 502。
**前置任务**：7.5
**范围**：实现独立请求/响应/source DTO、Controller 映射、1-2000 字符校验、`LLM_UNAVAILABLE` 业务码和 `GlobalExceptionHandler` 映射；增加 MVC Slice 组件测试。
**非目标**：不增加模型参数、topN、预算或 Provider HTTP 字段；不在 Controller 写业务逻辑或异常 try/catch。
**验收标准**：合法请求返回约定字段；sources 为 parent 维度；空白、过长和缺失问题返回 `VALIDATION_ERROR`/400；Query 不可用返回 `LLM_UNAVAILABLE`/502；内部错误仍为 500；API 只依赖 `query.api`。
**验证方式**：运行 `./gradlew :crag-api:test :crag-app:test --tests '*ArchitectureTest'`，覆盖成功、证据不足、校验失败、502 和 500。
**涉及文件**：`crag-api/src/main/**`、`crag-api/src/test/**`、`crag-common/src/main/**/ResponseCode.java`、`constraints/api-style.md`、`constraints/package-structure.md`

## 7.7 Stub Docker HTTP 回归与真实 DeepSeek 验收

**目标**：从正式 HTTP 入口证明确定性 Query 全链路可重复，并验证真实 DeepSeek 配置、认证和协议。
**前置任务**：7.1、7.2、7.3、7.4、7.5、7.6
**范围**：Compose 环境变量注入、`.env.example`、Stub 成功/失败脚本、AdminRag 数据准备、索引等待、真实 DeepSeek 调用、日志核对和文档收口。
**非目标**：不新增 Smoke Controller，不以手工 curl 为唯一证据，不对真实自然语言答案逐字断言，不删除共享 volume。
**验收标准**：Stub 成功回归对 HTTP、固定 answer、reference、parentChunkId 和 matchedChildIds 做确定断言；失败模式返回 502/业务码；真实调用满足 HTTP 200、非空 answer、非空 sources 和正确 provider/model；无敏感日志；全量检查通过。
**验证方式**：运行 `./gradlew test`、`./gradlew check`；使用 Compose 执行 Query Stub 成功/失败脚本；注入真实凭据执行 DeepSeek 验收；运行 Plan 严格校验和 `git diff --check`。
**涉及文件**：`docker-compose.yml`、`.env.example`、`scripts/tests/http/**`、`crag-app/src/main/resources/**`、`README.md`、`constraints/docker-structure.md`、`plan/plan_7/plan_7.md`、`plan/index/README.md`

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |

## 阻塞记录

- **日期**：2026-06-19
- **原因**：历史架构阻塞来自 `plan_9` 模块迁移。
- **当前进度**：7 个新任务均未开始。
- **解除条件**：`plan_9` 完成。
- **解除方**：`plan_9` owner。
- **解除状态**：已于 2026-06-19 解除；随后 grilling 发现 Parent Evidence 与框架基线需要独立前置计划。
- **恢复后的下一步**：保持 `draft`，等待 `plan_6.hotfix_6` 与 `plan_13` 完成后校准并转为 `ready`。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-18 | 创建 plan_7 | 从 plan_6 拆分 Query 链路 | 建立初始业务任务 |
| 2026-06-19 | 迁移为 workflow v2 | plan_8 工作流治理 | 补齐元信息、边界与固定任务结构 |
| 2026-06-19 | 适配 plan_9 模块边界 | crag-admin 迁移为 crag-api，公开 API 与 Smoke 隔离完成 | 使用 crag-api 和各领域 api 包 |
| 2026-06-19 | 完成 grilling 并重写为 7 项任务 | 收敛 Parent Context、三层 LLM 边界、包结构、引用、配置、错误码和验收策略 | 直接前置变为 plan_6.hotfix_6 与 plan_13；凭据只阻塞 7.7 |
