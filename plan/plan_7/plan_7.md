---
workflow_version: 3
plan_id: plan_7
type: main
status: in_progress
created: 2026-06-18
updated: 2026-06-20
---

# plan_7 — Query Parent Context 与 DeepSeek 问答链路

## 背景与目标

`plan_6.hotfix_6` 已在 Retrieval 内完成 child 检索结果到完整 parent evidence 的聚合，`plan_13` 已提供 Spring Boot 4.1.0 与 Spring AI 2.0.0 基线。本计划实现 Query 领域：接收用户问题、获取排序后的 parent evidence、构建预算受控且抗边界伪造的 Context、生成可引用 Prompt，通过项目自有 LLM 契约调用确定性 Stub 或 DeepSeek V4 Flash，并从正式 API 返回 answer 与可追溯 sources。

DeepSeek 使用其 Anthropic 兼容 API；Spring AI 的 Anthropic 模型实现只存在于 Provider Adapter 与配置边界。业务层不感知 DeepSeek、Anthropic、Spring AI 或 HTTP 协议类型。

## 范围

- Query 配置绑定、合法性校验和按 Provider 条件装配。
- 基于 parent evidence 构建字符预算受控、边界防碰撞的 Context、`[Sx]` 引用和 Prompt。
- 建立 Query 业务层、项目自有 LLM contract 与 Provider Adapter 三层边界。
- 提供确定性 Stub Adapter 和 DeepSeek Anthropic Adapter。
- 在 `UserQueryService` 编排 Retrieval、Context、Prompt、LLM、sources、引用分析与安全日志。
- 暴露 `POST /api/v1/query`，返回 answer 与 parent 维度 sources。
- 增加纯单元、轻量组件、架构测试、Stub Docker HTTP 回归和真实 DeepSeek 验收。
- 在总体路线记录后续 LLM 重试、熔断与多供应商降级候选，不在本期预建空 Plan。

## 非目标

- 不修改 Sparse、Dense、RRF、Rerank 或 Parent Evidence 聚合算法。
- 不直接访问 Storage、DAO、Repository 或 Retrieval 内部阶段。
- 不实现流式输出、鉴权、多租户、对话记忆、工具调用、Prompt 管理平台或 HTTP 调用方自定义模型参数。
- 不实现自动重试、熔断、多供应商降级或启动期供应商连通性探测。
- 不新增 Query Smoke Controller，不绕过 Docker 启动真实业务链路。
- 不在本计划升级 Spring Boot 或 Spring AI 基线。
- 不引入 Micrometer 指标；一期只保留结构化日志和可供后续指标消费的引用分析结果。

## 前置依赖

- **执行前置 Plan**：`plan_6.hotfix_6`、`plan_13`
- `plan_6.hotfix_6` 已提供 `RetrievalService.retrieveEvidence()` 与 `ParentEvidenceResult`。
- `plan_13` 已完成 Spring Boot 4.1.0、Spring AI 2.0.0、dependency-management/version catalog 基线和现有回归迁移。
- `plan_9` 已完成 `crag-api`、公开 API 包与 `crag-smoke` 隔离。
- `plan_9.hotfix_3` 已完成 HTTP DTO 分包、稳定错误码和 API 组件测试基线。
- DeepSeek 凭据不是开始 7.1 至 7.7 的前提；用户已确认执行环境具备可用于 7.8 的凭据并允许一次产生少量费用的真实验收。凭据只通过宿主环境变量 `DEEPSEEK_API_KEY` 临时注入，禁止写入 `.env`、脚本、Plan 或验收记录。若届时缺少凭据、额度、网络或模型权限，7.8 与 Plan 必须进入阻塞，不能完成。

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

## 实施文件映射

以下文件名在开始编码前固定，避免执行阶段临时发明同职责类型或把不同测试层混在一个文件中。测试方法可按实现细节微调，但测试文件职责不得漂移。

| 职责 | 创建或修改 |
| --- | --- |
| Query 配置与装配 | 创建 `crag-query/src/main/java/ai/cerbur/crag/query/llm/config/QueryProperties.java`、`DeepSeekApiKey.java`、`QueryLlmConfiguration.java`；修改 `crag-query/build.gradle.kts`、`crag-app/src/main/resources/application.yml`、`crag-app/src/test/resources/application.yml` |
| Context 与 Prompt | 创建 `crag-query/src/main/java/ai/cerbur/crag/query/context/ContextBuilder.java`、`QueryContext.java`、`SourceBoundaryFactory.java`、`crag-query/src/main/java/ai/cerbur/crag/query/prompt/PromptBuilder.java`、`crag-query/src/main/java/ai/cerbur/crag/query/api/QuerySource.java` |
| LLM contract 与 Adapter | 创建 `crag-query/src/main/java/ai/cerbur/crag/query/llm/contract/*.java`、`llm/adapter/stub/StubLlmAdapter.java`、`llm/adapter/deepseek/DeepSeekAnthropicLlmAdapter.java`；删除 `crag-query/src/main/java/ai/cerbur/crag/query/llm/ChatClient.java` |
| Query 编排与引用 | 修改 `crag-query/src/main/java/ai/cerbur/crag/query/api/UserQueryService.java`；创建同包 `UserQueryResult.java`、`InvalidQueryException.java`、`LlmUnavailableException.java` 及 `reference/ReferenceAnalyzer.java`、`ReferenceAnalysis.java` |
| HTTP API | 修改 `crag-api/src/main/java/ai/cerbur/crag/api/controller/UserQueryController.java`、`controller/advice/GlobalExceptionHandler.java`、`dto/query/UserQueryRequest.java`、`crag-common/src/main/java/ai/cerbur/crag/common/dto/result/ResponseCode.java`；创建 `dto/query/UserQueryResponse.java`、`QuerySourceResponse.java` |
| 自动化回归 | 创建 `scripts/tests/http/query_stub_success_test.sh`、`query_stub_failure_test.sh`、`query_deepseek_acceptance_test.sh`；创建 `.env.example`；修改 `docker-compose.yml` |
| 文档与约束 | 修改 `README.md`、`constraints/api-style.md`、`constraints/package-structure.md`、`constraints/docker-structure.md` |

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
    │   ├── InvalidQueryException
    │   └── LlmUnavailableException
    ├── context/
    │   ├── ContextBuilder
    │   ├── QueryContext
    │   └── SourceBoundaryFactory
    ├── prompt/
    │   └── PromptBuilder
    ├── reference/
    │   ├── ReferenceAnalyzer
    │   └── ReferenceAnalysis
    └── llm/
        ├── contract/
        │   ├── LlmClient
        │   ├── LlmRequest
        │   ├── LlmResult
        │   ├── LlmUsage
        │   ├── LlmProviderException
        │   └── LlmFailureCategory
        ├── adapter/
        │   ├── deepseek/DeepSeekAnthropicLlmAdapter
        │   └── stub/StubLlmAdapter
        └── config/
            ├── QueryProperties
            └── QueryLlmConfiguration

crag-query ──────────────► ai.cerbur.crag.retrieval.api
                           RetrievalService / ParentEvidenceResult
```

依赖方向固定为：

```text
HTTP Adapter → Query 公开 API → Query 业务逻辑 → 项目 LLM contract
                                                   ▲
                                      Provider Adapter
                                                   │
                                           Spring AI ChatModel
                                                   ▲
                                    AnthropicChatModel（配置层创建）
```

- `UserQueryService` 保持单一具体 `@Service`，依赖按项目规范使用 `@Autowired` 字段注入，不创建机械的 Interface/Impl。
- Query 业务层决定 Retrieval、Context、Prompt、sources 和失败语义。
- `llm.contract` 只表达供应商无关的生成能力，不出现 Spring AI、DeepSeek、Anthropic、HTTP 或模型名。
- DeepSeek Adapter 明确处理 Anthropic 协议和 Anthropic 专属 options，但依赖 Spring AI `ChatModel` 接口。
- `QueryLlmConfiguration` 手动创建具体 `AnthropicChatModel`，通过限定 Bean 注入 Adapter；不使用 Starter 自动配置。
- `crag-app` 只保存配置值，不新增 Query Java 装配或业务调用。
- 删除旧 `query.llm.ChatClient` 骨架，由 `llm.contract.LlmClient` 取代。

## 关键决策

### 调用与失败流程

```text
UserQueryController
→ UserQueryService.answer(question)
→ trim + 业务输入校验
→ RetrievalService.retrieveEvidence(question, topN)
→ ContextBuilder.build(parentEvidence, maxCharacters)
→ 空 Context：直接返回“知识库证据不足”，不调用 LLM
→ PromptBuilder.build(question, queryContext)
→ LlmClient.generate(llmRequest)
→ ReferenceAnalyzer 分析 answer 中的 [Sx]
→ UserQueryResult(answer, sources)
→ Controller 映射为 HTTP DTO
```

- `UserQueryRequest` compact constructor 先 trim，再由 `@NotBlank` 与 `@Size(max = 2000)` 校验。
- `UserQueryService` 对所有调用再次 trim，并以 trim 后文本校验、检索、构建 Prompt 和记录 DEBUG 日志。
- 非法问题抛公开 `InvalidQueryException`，内部原因区分 `QUESTION_REQUIRED` 与 `QUESTION_TOO_LONG`；API 统一映射 `VALIDATION_ERROR` / HTTP 400。
- Retrieval 异常不伪装为空结果或 LLM 失败，走安全的内部错误 / HTTP 500。
- Retrieval 空结果与预算后空 Context 都返回 HTTP 200、固定答案“知识库证据不足”和空 sources；日志分别标记 `retrieval_empty` 与 `context_budget_empty`。
- 有 Context 时模型仍可逐字返回“知识库证据不足”，不附引用，但 API 仍返回全部实际送入模型的 sources。
- LLM 认证、限流、超时、协议、截断、空响应及未知失败统一转换为 Query 公开 `LlmUnavailableException`，由 API 映射为 `LLM_UNAVAILABLE` / `50201` / HTTP 502；不降级为 Stub。

### Context、source 与 Prompt

- Retrieval `topN` 默认 `8`，合法范围 `1..50`，表示 parent evidence 数量。
- Context 预算默认 `12000`，合法范围 `256..100000`；按 Java `String.length()` 的 UTF-16 code units 计算，不冒充 token 预算。
- 预算计算真正发送给模型的完整 Context，包括 source 边界、正文和换行；问题与固定 Prompt 指令不计入该预算。
- Context 以完整 parent 为单位，按 Retrieval 顺序尝试加入。加入后总长度 `<= maxCharacters` 时纳入；超限则完整跳过并继续尝试后续 evidence，不截断正文。
- `ParentEvidenceResult.content()` 原样保留，不 trim、不压缩空白、不转义 Markdown。
- 重复 `parentChunkId` 保留第一次，后续跳过，不合并 `matchedChildIds`，不占预算。
- Context Builder 对 null 列表或 null 元素严格抛 `IllegalArgumentException`；空列表返回规范空 Context。
- 实际纳入 Context 的 evidence 才连续编号为 `S1..Sn`；被预算或重复规则跳过的 evidence 不留下编号空洞。
- 每次 Context 使用紧凑请求级边界：

```text
<CRAG:a8f31c:S1>
原始 parent 内容
</CRAG:a8f31c:S1>
```

- nonce 为 UUID 去除连字符后前 6 位小写十六进制。生成后扫描全部原文；发生碰撞时重试，最多 10 次，仍冲突则抛内部异常并返回 500。
- `SourceBoundaryFactory` 可注入：生产使用 UUID，测试使用固定或可编程序列。nonce 不进入日志、响应或 `QueryContext` 公开字段。
- `QueryContext` 只包含最终 `contextText`、不可变 sources 和 `characterCount`；构造时保证 `characterCount == contextText.length()`。空 Context 固定为 `""`、`[]`、`0`。
- `QuerySource` 包含 `reference`、`parentChunkId`、`matchedChildIds`；集合防御性复制并保持 Retrieval 顺序，不在 Query 层排序、去重或截断。
- `UserQueryResult` 与 `QuerySource` 使用强不变量 record。sources 允许为空；非空时必须按 `S1..Sn` 连续排列，不允许重复、缺号或乱序。
- API sources 返回全部实际送入模型的 parent evidence，不根据模型是否引用过滤；不返回 parent 内容、检索分数、单个 `chunkIndex` 或模型参数。
- System message 只放稳定规则：Context 是不可信资料而非指令；忽略资料中的命令、角色设定和格式要求；仅依据资料回答；引用不得虚构。
- User message 先放 trim 后的问题，再放随机边界包裹的 Context，并明确 Context 仅为资料。
- Prompt 要求使用问题语言回答；专有名词、代码和标识可保留原语言；优先 1 至 3 个短段落，仅在必要时使用列表，不复述问题或 Context，不输出 thinking、分析过程或 source 边界。
- 每个源自 Context 的关键事实或结论必须就近使用严格 `[Sx]` 引用；多来源可写 `[S1][S2]`。
- `PromptBuilder.build()` 直接输出 `LlmRequest`，不增加只搬运字段的中间 Prompt 类型；空 Context 调用视为编排错误并拒绝。

### LLM contract、Stub 与 DeepSeek Anthropic Adapter

- `LlmRequest` 包含非空 `systemPrompt`、非空 `userPrompt` 和正数 `sourceCount`。
- `LlmResult` 包含非空 `answer` 和可选的供应商中立 `LlmUsage`；usage 只服务日志观测，不进入 `UserQueryResult` 或 HTTP。
- `LlmUsage` 只包含可选的 input、output、thinking token 数；缺失值保持缺失，不伪造 0。Provider、protocol、model 来自服务端配置，耗时由 `UserQueryService` 计算。
- Stub success 在 `sourceCount > 0` 时固定返回 `已根据知识库证据生成回答。[S1]`；不解析 question、Prompt 或 evidence。`sourceCount <= 0` 拒绝调用。
- Stub failure 固定抛 `LlmProviderException`，分类 `UNKNOWN`。
- DeepSeek 使用 Anthropic 兼容 API，默认 base URL `https://api.deepseek.com/anthropic`、模型 `deepseek-v4-flash`、temperature `0`、max output tokens `4096`。
- thinking 使用 DeepSeek 默认开启；不显式发送 thinking、top_p 或 top_k。temperature 必须显式发送 `0`，可配置范围 `0.0..1.0`。
- max output tokens 可配置范围 `256..16384`。
- 使用 `DEEPSEEK_API_KEY`，按 Anthropic 协议发送 `x-api-key` 与 `anthropic-version: 2023-06-01`，不发送 `Authorization: Bearer`。
- base URL 必须为绝对 HTTPS URI，允许 `/anthropic` path，禁止 userinfo、query 和 fragment；实际官方验收必须使用默认官方地址。
- API key trim 后校验与发送；Stub 模式完全不校验 DeepSeek key、base URL、model 或其他 DeepSeek 配置。
- DeepSeek 模式在启动期校验 key、base URL、model、temperature、max tokens 与超时，但不探测网络连通性。
- DeepSeek 密钥配置使用自定义不可变类并覆盖安全 `toString()`；其余无密钥配置可使用嵌套 record。
- Spring AI 2.0.0 的 `spring-ai-anthropic` 基于官方 Anthropic Java SDK，`AnthropicChatModel.builder()` 暴露单一请求 `timeout` 而非独立连接/响应双超时。本计划只配置 `request-timeout`，默认 120 秒；`maxRetries=0`。不得保留无法实际生效的 `connect-timeout` 配置。
- 协议组件测试必须通过 `QueryLlmConfiguration` 创建真实 `AnthropicChatModel`，对本地 JDK `HttpServer` 发请求，并证明超时或 5xx 失败时仅发出一次请求；只 Mock `ChatModel` 不能作为 Header、URL、JSON 或零重试证据。
- Adapter 按顺序提取全部最终 text blocks，以单个 `\n` 拼接后整体 trim；保留内部 Markdown、换行和引用位置。
- thinking blocks 忽略且绝不记录内容；出现 tool-use/tool-result block、多个 generation 或不可识别结构视为 `PROTOCOL`。
- 0 generation、无 text block 或 trim 后空白为 `EMPTY_RESPONSE`。
- stop reason 表示 `max_tokens` 等截断时，即使已有文本也归类 `TRUNCATED_RESPONSE` 并返回 502；只有正常 stop/end_turn 才接受答案。
- Spring AI 2.0.0 无法可靠解析 DeepSeek thinking + text fixture 时，7.4 与 Plan 进入阻塞；不得在编码中静默改用原始 HTTP。若需薄 HTTP Client，必须先更新并提交本 Plan 的关键决策。
- 官方环境拒绝或无权访问 `deepseek-v4-flash` 时，7.8 与 Plan 进入阻塞；不得静默切换模型。
- DeepSeek Anthropic API 会把不支持的模型名自动映射到 `deepseek-v4-flash`。因此本计划不以“请求成功”证明模型名正确：7.4 必须断言请求 JSON 中的 `model` 精确为配置值，7.8 日志中的 model 只表示客户端配置，不宣称供应商实际路由结果。
- DeepSeek Anthropic API 官方允许 temperature `0.0..2.0`；本项目有意收紧为 `0.0..1.0`，属于服务端配置治理边界，不是对供应商能力上限的描述。

### 引用分析

- 只识别严格格式 `[S1]`、`[S2]`、`[S12]`：`S` 大写、无空格、无前导零；代码块内不特殊处理。
- `S0` 或超过 `sourceCount` 的编号无效。
- `ReferenceAnalysis` 包含：
  - `totalOccurrences`：全部严格格式引用出现次数，含重复和无效。
  - `validOccurrences`：有效引用出现次数，含重复。
  - `validSourceCount`：被引用的不同 source 数。
  - `invalidReferences`：按首次出现顺序去重的无效编号。
  - `unreferencedSourceCount`：未被引用的 source 数。
- 分析失败只记录 warning，不修改 answer、sources 或 HTTP 结果。
- 线上答案含无效引用仍原样返回 HTTP 200；真实 Provider 验收遇到无效引用必须失败。

### 日志与可观测性

- `UserQueryService` 优先复用 MDC 中已有的非空 `requestId`；没有时生成 UUID；同步调用结束后恢复原值，避免线程复用串号。
- INFO 记录 requestId、provider、protocol、model、questionCharacters、retrieved/included/duplicate skipped/budget skipped 数量、Context 字符数、引用统计、usage 可用性、token 数值、总耗时与结果分类。
- usage 缺失记录 `usageAvailable=false`，不伪造 0；Stub 固定为不可用。若协议提供 thinking token 数，只记录数值。
- DEBUG 是 Demo 调试的受控例外，记录完整 question、完整 answer、全部 source ID 映射、有效引用 source 映射、无效编号及被跳过 parent ID/长度；question 与 answer 的 `\r`、`\n` 转为可见转义字符，防止日志注入。
- DEBUG source 映射格式包含完整 `reference → parentChunkId → matchedChildIds`，不截断，以便从 answer 的 `[Sx]` 回溯 parent 与真实命中 child。
- 任何级别都禁止记录 Context、Prompt、parent 内容、thinking 内容、API key、Authorization 或其他认证 Header。
- 默认 Compose 和生产日志保持 INFO；README 明确 Query DEBUG 会包含用户问题和模型回答，不得在生产开启。
- Spring AI、Anthropic Client 和 HTTP wire logger 不启用 body logging，并保持 INFO 或更高；真实回归扫描日志确认无密钥、Header、完整 Context 或 Prompt。

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
      request-timeout: 120s
      deepseek:
        api-key: ${DEEPSEEK_API_KEY:}
        base-url: https://api.deepseek.com/anthropic
        model: deepseek-v4-flash
        temperature: 0
        max-output-tokens: 4096
      stub:
        mode: success
```

Compose 显式支持：

```text
CRAG_QUERY_LLM_PROVIDER
DEEPSEEK_API_KEY
CRAG_QUERY_LLM_DEEPSEEK_BASE_URL
CRAG_QUERY_LLM_DEEPSEEK_MODEL
CRAG_QUERY_LLM_DEEPSEEK_TEMPERATURE
CRAG_QUERY_LLM_DEEPSEEK_MAX_OUTPUT_TOKENS
CRAG_QUERY_LLM_REQUEST_TIMEOUT
CRAG_QUERY_LLM_STUB_MODE
```

- Provider 与 Stub mode 使用大小写不敏感枚举绑定：`STUB | DEEPSEEK`、`SUCCESS | FAILURE`；YAML 示例使用小写。
- 未知 provider/mode、越界 topN/Context/max tokens/temperature、非法超时或 DeepSeek 模式缺少必要配置均导致启动失败，错误信息不得包含密钥。
- HTTP 调用方不能覆盖 topN、Context 预算、Provider、model、temperature、max tokens 或超时。

## HTTP 契约

请求：

```json
{
  "question": "项目使用什么数据库和向量存储？"
}
```

- `question` trim 后长度为 1 至 2000；中间空白原样保留并计入长度。
- 请求 JSON 未知字段沿用当前 Jackson 默认行为，不读取、不记录，也不能覆盖服务端配置。

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

- `sources` 与 `matchedChildIds` 始终输出数组，绝不为 null。
- 新增 `LLM_UNAVAILABLE = 50201`，默认安全消息对应上游模型不可用，HTTP 状态为 502。

## 未决问题

无。真实凭据、额度、网络、模型权限或官方协议可用性属于 7.8 的执行条件；条件不满足时按已定义阻塞流程处理，不构成 Plan 内容未决。

## 风险与回滚

- Parent Context 过长：通过完整 parent 跳过、UTF-16 字符预算和配置上限控制；日志不记录正文。
- 固定边界被原文伪造：使用短请求级 nonce，并在所有原文中碰撞检测；这不替代 system prompt 的不可信 Context 规则。
- 默认 thinking 增加延迟和输出预算：request timeout 设为 120 秒，max output tokens 默认 4096；截断明确失败，不返回半截答案。
- Spring AI Anthropic 实现与 DeepSeek thinking blocks 不兼容：先以去敏 fixture 和协议组件测试验证；失败时阻塞并先更新 Plan，不临时换协议实现。
- Provider 不可用或模型权限不足：统一 502，保留安全分类和 cause，不自动重试或切换 Stub。
- sources 与 Context 漂移：两者从同一 `QueryContext` 生成，并以强不变量、连续编号和映射测试约束。
- DEBUG 日志可能包含用户输入和模型回答：默认 INFO，README 警告；Context、Prompt、parent 内容和认证信息始终禁止记录。
- 自动化回归依赖异步 Dense/Sparse 索引：脚本使用唯一 runId 和有限轮询，不直接查询数据库或调用 Smoke 端点。
- 每项任务独立提交，可逆序撤销配置、Context、Stub、Provider、业务编排、HTTP 与 Docker 脚本；无数据库迁移和不可逆数据变更。
- 后续重试、熔断和多供应商降级只作为 `plan_main` 未来演进候选记录，待真实故障率、限流率、超时率和成本数据证明需要后再创建主 Plan。

## 测试与验证计划

- 纯单元测试：`./gradlew :crag-query:test`。固定创建 `QueryPropertiesTest`、`QuerySourceTest`、`QueryContextTest`、`ContextBuilderTest`、`PromptBuilderTest`、`LlmContractTest`、`StubLlmAdapterTest`、`DeepSeekAnthropicLlmAdapterTest`、`ReferenceAnalyzerTest`、`UserQueryServiceTest`，分别覆盖配置值、强不变量、Context 预算/边界/碰撞/并发、Prompt、Stub、DeepSeek `ChatResponse` 映射、失败分类、引用分析与业务编排。
- 轻量组件测试：`./gradlew :crag-query:test :crag-api:test :crag-app:test`，覆盖配置绑定、默认 Stub、DeepSeek 条件 Bean、启动校验、MVC 请求校验、DTO 映射与 400/502/500。
- 轻量组件测试固定创建 `QueryLlmConfigurationComponentTest`、`DeepSeekAnthropicProtocolComponentTest` 和 `UserQueryControllerComponentTest`。协议测试使用 JDK `HttpServer`，不新增 Mock Server 依赖；经真实 `QueryLlmConfiguration → AnthropicChatModel → HttpServer` 路径验证 `/anthropic/v1/messages`、`x-api-key`、`anthropic-version`、请求 JSON 中的 system/user/model/temperature/max_tokens、request timeout、零重试和 thinking + text 去敏 fixture。
- 架构测试：`./gradlew :crag-app:test --tests '*ArchitectureTest'`，确认 API 只依赖 `query.api`，Query 只依赖 `retrieval.api`，Spring AI/Anthropic 类型只存在于 `llm.adapter` 与 `llm.config`。
- 全量测试：`./gradlew test` 与 `./gradlew check`。
- Stub Docker HTTP 成功回归：通过正式 AdminRag API 写入含唯一 runId 和不可猜测验证码的短文；轮询正式 Query API，每 3 秒一次、最长 90 秒，直到 sources 非空；断言固定 Stub answer、reference、目标 parentChunkId 和 matchedChildIds。
- Stub 失败回归：以 `CRAG_QUERY_LLM_STUB_MODE=failure` 启动正式应用，断言 HTTP 502 与 `50201`，不增加测试专用 HTTP 参数。
- Stub 成功与失败脚本各自负责重建 `app` 容器并等待正式 `/api/v1/query` 可用；失败脚本结束时必须恢复 `CRAG_QUERY_LLM_STUB_MODE=success` 并再次确认应用可用，避免把共享开发环境留在故障模式。脚本不得启动 `app-smoke` 或调用 `/api/v1/test/**`。
- 真实 DeepSeek 验收：使用默认官方 base URL、`deepseek-v4-flash`、temperature 0、max tokens 4096 和宿主临时注入的 `DEEPSEEK_API_KEY`；只主动执行一次真实调用，通过正式 API 断言 HTTP 200、`code=0`、answer 包含本次唯一验证码、至少一个合法有效 `[Sx]`、无无效引用、sources 非空且目标 source 映射正确。
- 真实验收首次失败后立即停止自动调用，保留脱敏失败证据并先诊断；任何再次调用都需要用户明确批准。模型不可用或账号无权限时必须阻塞，不得切换模型、端点、协议或 Stub。
- 真实验收允许读取容器日志作为补充证据：只记录脱敏后的 provider/protocol/model、引用、usage 和结果摘要；禁止保存完整响应、Prompt、Context 或认证信息。HTTP 响应仍是业务成功的主证据。
- 测试数据使用唯一 runId；当前无精确删除入口时允许保留可识别数据，并在验收记录中写明 runId 与残留范围；不执行清表、删 volume 或 `docker compose down -v`。
- 最终执行 `python3 scripts/validate_plans.py --strict --verify-git` 与 `git diff --check`。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 7.1 | Query 配置模型与合法性校验 | ✅ 完成 | 025dd49 | 2026-06-20 |
| 7.2 | Parent Context、sources 与 Prompt 工程 | ✅ 完成 | a6a29f5 | 2026-06-20 |
| 7.3 | LLM contract、确定性 Stub 与默认装配 | ✅ 完成 | ab8f4a4 | 2026-06-20 |
| 7.4 | DeepSeek Anthropic Adapter 与协议组件测试 | ✅ 完成 | 3059c44 | 2026-06-20 |
| 7.5 | UserQueryService 编排、引用分析与日志 | ✅ 完成 | 8dca74a | 2026-06-20 |
| 7.6 | UserQuery HTTP 契约、错误码和组件测试 | ✅ 完成 | c78a5fd | 2026-06-20 |
| 7.7 | Stub Docker HTTP 回归与运行配置收口 | 🔄 进行中 | bc08a15 | — |
| 7.8 | 真实 DeepSeek Anthropic API 验收 | 🔄 进行中 | 5815de1 | — |

整体进度：6 / 8（75%）

## 验收状态

独立验收发现 7.7、7.8 的自动化脚本无法满足验收标准，Plan 已退回进行中。修复脚本并重新交接后，须由新的独立验收 session 重跑 Stub 成功、Stub 失败/恢复和真实 DeepSeek 验收。

## 7.1 Query 配置模型与合法性校验

**目标**：建立 Query 自有配置契约、默认值和可预测的启动校验边界。
**前置任务**：无
**范围**：定义 `QueryProperties`、Provider/Stub mode 枚举及 DeepSeek 安全密钥配置类；绑定 topN、Context 预算、单一 request timeout、DeepSeek 与 Stub 配置；实现单字段和跨字段校验；注册不可变 `@ConfigurationProperties`；只增加配置处理器与校验所需依赖。
**非目标**：不引入 Spring AI Anthropic 模块，不实现 Adapter、条件 Bean、Context、Prompt 或业务编排。
**验收标准**：默认值和合法范围与配置契约一致；枚举大小写不敏感；Stub 模式不被 DeepSeek 配置阻断；DeepSeek 模式缺少 key 或存在非法 URL/model/temperature/max tokens/request timeout 时启动失败；错误与 `toString()` 不泄露密钥；仓库不存在声明后未被 SDK 消费的连接超时配置。
**验证方式**：运行 `./gradlew :crag-query:test :crag-app:test`，由 `QueryPropertiesTest` 与应用 Context 测试覆盖默认值、`CRAG_QUERY_LLM_REQUEST_TIMEOUT` 环境变量覆盖、合法/非法配置、条件校验和密钥脱敏。
**涉及文件**：`crag-query/build.gradle.kts`、`crag-query/src/main/**/llm/config/**`、`crag-query/src/test/**`、`crag-app/src/main/resources/application.yml`、`crag-app/src/test/**`

## 7.2 Parent Context、sources 与 Prompt 工程

**目标**：把排序后的 parent evidence 转换为字符预算受控、边界防碰撞、引用映射稳定的 LLM 请求。
**前置任务**：7.1
**范围**：实现 `QuerySource`、`ContextBuilder`、`QueryContext`、`SourceBoundaryFactory`、`PromptBuilder`；按实际渲染长度加入或跳过完整 parent；处理重复 parent；生成连续 `[Sx]` 和不可信 Context 规则。
**非目标**：不调用 LLM，不实现 HTTP，不修改 Retrieval，不清洗或截断 parent 原文。
**验收标准**：预算按最终 Context 的 UTF-16 长度计算；nonce 边界紧凑且碰撞重试；sources 与实际 Context 一一对应并连续编号；超长 parent 跳过后继续尝试；全跳过等同空 Context；Prompt 角色分离并抵抗 Context 内指令；强不变量和防御性复制成立。
**验证方式**：运行 `./gradlew :crag-query:test`，覆盖空输入、null、正常/重复/多 parent、边界刚好、超预算、nonce 碰撞、恶意指令文本、source 映射和并发构建。
**涉及文件**：`crag-query/src/main/**/api/QuerySource.java`、`crag-query/src/main/**/context/**`、`crag-query/src/main/**/prompt/**`、`crag-query/src/test/**`

## 7.3 LLM contract、确定性 Stub 与默认装配

**目标**：建立供应商无关的 LLM contract、可重复 Stub 和默认无凭据运行路径。
**前置任务**：7.2
**范围**：定义 `LlmClient`、`LlmRequest`、`LlmResult`、`LlmUsage`、`LlmProviderException`、`LlmFailureCategory`；实现 success/failure Stub；在 `provider=stub` 时条件装配 Stub；删除旧 `ChatClient` 骨架。
**非目标**：不接入 DeepSeek，不实现 UserQueryService 或 HTTP。
**验收标准**：contract 不暴露 Spring AI 或 Provider 类型；usage 保持供应商中立且缺失值不伪造；相同输入输出完全确定；success 只依赖 sourceCount；failure 分类为 UNKNOWN；空/非法请求不能伪装成功；默认启动只装配 Stub 且不校验 DeepSeek 配置。
**验证方式**：运行 `./gradlew :crag-query:test :crag-app:test`，覆盖 contract 不变量、固定输出、失败模式和默认 Bean 装配。
**涉及文件**：`crag-query/src/main/**/llm/contract/**`、`crag-query/src/main/**/llm/adapter/stub/**`、`crag-query/src/main/**/llm/config/**`、`crag-query/src/test/**`、`crag-app/src/test/**`

## 7.4 DeepSeek Anthropic Adapter 与协议组件测试

**目标**：通过 Spring AI 2 Anthropic 模型实现调用 DeepSeek V4 Flash 的 Anthropic 兼容 API。
**前置任务**：7.3
**范围**：按 `plan_13` 约定在 `crag-query` 独立导入 Spring AI BOM，并引入非 Starter 的 `spring-ai-anthropic` 模块；使用 2.0.0 builder API 手动构造 `request-timeout=120s`、`maxRetries=0` 的 `AnthropicChatModel`；实现 `DeepSeekAnthropicLlmAdapter`、system/user Message 与 options 映射、Header/URL、text/thinking/tool/generation/stop reason/usage 解析和失败分类；增加 JDK `HttpServer` 协议组件测试及去敏 fixture。
**非目标**：不实现自动重试、熔断、Fallback、流式生成、Advisor、Memory、工具调用或原始 HTTP 备选实现。
**验收标准**：默认官方 URL、`deepseek-v4-flash`、temperature 0、max tokens 4096、thinking 使用供应商默认开启；请求发送 `x-api-key` 和 `anthropic-version`，且 JSON 中 model 精确为配置值；失败只请求一次；最终 text block 正确拼接，thinking 不泄露，tool/multi-generation/截断/空响应正确分类；Spring AI 类型不穿透边界。
**验证方式**：运行 `./gradlew :crag-query:test :crag-app:test --tests '*ArchitectureTest'`；`DeepSeekAnthropicLlmAdapterTest` 使用 Fake/Mock `ChatModel` 验证 Spring AI 响应映射，`DeepSeekAnthropicProtocolComponentTest` 使用真实配置 Bean 与 JDK `HttpServer` 验证协议请求、fixture、request timeout 和零重试。
**涉及文件**：`crag-query/build.gradle.kts`、`crag-query/src/main/**/llm/adapter/deepseek/**`、`crag-query/src/main/**/llm/config/**`、`crag-query/src/test/**`、`crag-app/src/test/**`

## 7.5 UserQueryService 编排、引用分析与日志

**目标**：串联 Parent Evidence、Context、Prompt 和 LLM，并提供稳定 Query 公开结果、引用分析和可回溯日志。
**前置任务**：7.2、7.3、7.4
**范围**：实现具体 `UserQueryService`、`UserQueryResult`、`InvalidQueryException`、`LlmUnavailableException`、`ReferenceAnalyzer`、`ReferenceAnalysis`、MDC requestId 和 INFO/DEBUG 日志；处理输入、空 evidence、预算空 Context、Retrieval 失败、LLM 失败、证据不足与引用旁路分析。
**非目标**：不实现 HTTP DTO，不根据引用过滤 sources，不修改模型答案，不记录 Context、Prompt、parent 内容或 thinking。
**验收标准**：正常返回 trim 后 answer 与全部 Context sources；两类空 Context 不调用 LLM；Retrieval 异常走 500，Provider 失败转公开 LLM 异常；截断或空 answer 不返回部分结果；严格引用统计正确；MDC 复用与恢复正确；DEBUG 可从引用回溯 parent/child 且防日志注入。
**验证方式**：运行 `./gradlew :crag-query:test`，Mock Retrieval 与 LLM contract，覆盖完整流程、调用顺序、异常边界、日志级别、敏感信息禁令和引用分析。
**涉及文件**：`crag-query/src/main/**/api/**`、`crag-query/src/main/**/reference/**`、`crag-query/src/test/**`

## 7.6 UserQuery HTTP 契约、错误码和组件测试

**目标**：提供稳定正式 Query HTTP 边界，并将输入和 LLM 失败映射为安全 HTTP 响应。
**前置任务**：7.5
**范围**：实现独立请求/响应/source DTO、Controller 映射、trim 后 1 至 2000 字符校验、`LLM_UNAVAILABLE=50201` 和 `GlobalExceptionHandler` 映射；增加 MVC Slice 组件测试；同步 API 与包结构约束。
**非目标**：不增加模型参数、topN、预算或 Provider HTTP 字段；不在 Controller 写业务逻辑或异常 try/catch；不因未知 JSON 字段增加原始请求体处理。
**验收标准**：合法请求返回约定字段；sources 为 parent 维度且数组永不为 null；空白、过长和缺失问题返回 `VALIDATION_ERROR`/400；Query 公开输入异常同样为 400；LLM 不可用返回 `50201`/502；内部错误仍为 500；未知字段不能覆盖服务端配置；API 只依赖 `query.api`。
**验证方式**：运行 `./gradlew :crag-api:test :crag-app:test --tests '*ArchitectureTest'`，覆盖成功、证据不足、trim、校验失败、未知字段、502 和 500。
**涉及文件**：`crag-api/src/main/**`、`crag-api/src/test/**`、`crag-common/src/main/**/ResponseCode.java`、`constraints/api-style.md`、`constraints/package-structure.md`

## 7.7 Stub Docker HTTP 回归与运行配置收口

**目标**：从正式 HTTP 入口证明确定性 Query 全链路可重复，并完成无真实凭据的运行配置、日志与文档收口。
**前置任务**：7.1、7.2、7.3、7.4、7.5、7.6
**范围**：Compose 环境变量、创建 `.env.example`、application 配置、`query_stub_success_test.sh`、`query_stub_failure_test.sh`、AdminRag 唯一 runId 数据准备、索引等待、日志扫描、README 和 Docker 约束同步。LLM 韧性治理候选已在 Plan ready 前完成归档与 `plan_main` 更新，本任务不重复修改。
**非目标**：不调用真实 DeepSeek，不新增 Smoke Controller，不以手工 curl 为唯一证据，不删除共享 volume。
**验收标准**：Stub 成功回归确定断言固定 answer、reference、目标 parentChunkId 和 matchedChildIds；failure 返回 502/50201 且脚本结束后恢复 success；配置覆盖生效但 HTTP 未知字段无法覆盖；默认日志不含问题/答案、Context、Prompt、parent 内容、密钥或 Header；README 说明 DEBUG 风险；全量检查通过。
**验证方式**：运行 `./gradlew test`、`./gradlew check`；运行 `docker compose up -d --build app` 后执行 `bash scripts/tests/http/query_stub_success_test.sh` 与 `bash scripts/tests/http/query_stub_failure_test.sh`；再次执行成功脚本确认环境恢复；运行 Plan 严格校验和 `git diff --check`。
**涉及文件**：`docker-compose.yml`、`.env.example`、`scripts/tests/http/query_stub_success_test.sh`、`scripts/tests/http/query_stub_failure_test.sh`、`crag-app/src/main/resources/application.yml`、`README.md`、`constraints/docker-structure.md`、`plan/plan_7/plan_7.md`、`plan/index/README.md`

## 7.8 真实 DeepSeek Anthropic API 验收

**目标**：证明默认官方 DeepSeek Anthropic 兼容端点、模型、认证、thinking 响应和引用 Prompt 在真实服务上可用。
**前置任务**：7.7
**范围**：创建 `query_deepseek_acceptance_test.sh`；从宿主环境变量临时注入 `DEEPSEEK_API_KEY`，使用官方默认 base URL 和 `deepseek-v4-flash` 重建 `app` 容器；写入唯一 runId 与不可猜测验证码；通过正式 Query API 验证答案、引用、sources、usage 与安全日志；记录脱敏的真实环境证据和无法清理时的数据残留范围；结束后恢复 Stub success 配置。
**非目标**：不把凭据写入任何文件；不静默切换模型、地址、协议或 Stub；不逐字断言自然语言措辞；首次失败后不自动重试。
**验收标准**：HTTP 200、`code=0`、answer 包含本次验证码且不是证据不足、至少一个合法有效引用、没有无效引用、目标 source 映射正确；日志显示 provider=deepseek、protocol=anthropic、配置 model 和 usage 可用性，且无完整响应、Prompt、Context、凭据或认证 Header；验收后 `app` 恢复 Stub success。凭据、额度、网络、模型权限或官方协议不满足时任务和 Plan 按阻塞规范记录，不能完成，也不得切换模型。
**验证方式**：确认 `DEEPSEEK_API_KEY` 非空后，通过 `bash scripts/tests/http/query_deepseek_acceptance_test.sh` 主动执行一次真实 DeepSeek 自动化验收；核对 HTTP 主证据与脱敏容器日志补充证据。首次失败时停止调用、保留证据并诊断，任何再次调用须经用户明确批准；最终运行 `./gradlew check`、`python3 scripts/validate_plans.py --strict --verify-git` 和 `git diff --check`。
**涉及文件**：`scripts/tests/http/query_deepseek_acceptance_test.sh`、`docker-compose.yml`、`.env.example`、`README.md`、`plan/plan_7/plan_7.md`、`plan/index/README.md`

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-20 | macOS / Java 21 / Docker Compose | `./gradlew test` | 通过 | 全量 Gradle 测试通过，34 个 actionable tasks，20 executed、14 up-to-date。 |
| 2026-06-20 | macOS / Java 21 | `./gradlew check` | 失败 | `validatePlans` 报 4 个错误：Plan YAML status、整体进度及执行/验收队列不一致；其他约束、框架依赖和模块依赖校验通过。 |
| 2026-06-20 | macOS / Python 3 | `python3 scripts/validate_plans.py --strict --verify-git` | 失败 | 与 `./gradlew check` 相同的 4 个 Plan 静态错误；本次验收状态回退后需由执行 session 修复并重新校验。 |
| 2026-06-20 | Docker Compose / Stub success | `bash scripts/tests/http/query_stub_success_test.sh` | 失败 | 脚本退出 0，但 AdminRag 响应不含 `parentChunkId`，关键目标 parent 映射断言被降级为 `SKIP`，不满足 7.7 验收标准。runId：`qs-1781965514-12001`；测试数据保留。 |
| 2026-06-20 | Docker Compose / Stub failure | `bash scripts/tests/http/query_stub_failure_test.sh` | 失败 | readiness 将 curl 失败输出 `000000` 误判为就绪；failure 请求和恢复确认均得到 HTTP `000`，脚本退出 1。runId：`qf-1781965584-13145`；测试数据保留。 |
| 2026-06-20 | Docker Compose / DeepSeek | `source ~/.zshrc; bash scripts/tests/http/query_deepseek_acceptance_test.sh` | 未执行 | 执行环境拒绝从 `.zshrc` 读取密钥并向第三方服务发送本地业务数据；未发起真实调用。再次尝试前需用户在知悉数据外发风险后明确批准，且应先修复与 Stub 脚本同源的 parent ID/readiness 缺陷。 |
| 2026-06-20 | macOS / Python 3 / Git | `python3 scripts/validate_plans.py --strict --verify-git`、`git diff --check` | 通过 | 验收失败状态、进度和队列同步后严格 Plan 校验为 0 error；仅保留 24 个历史 Plan 兼容 warning。 |
| 2026-06-20 | Docker Compose / Stub success | 正式 `POST /api/v1/query` 恢复检查 | 通过 | 失败脚本结束后等待应用稳定，正式 Query API 返回 HTTP 200、`code=0`，共享环境已恢复 Stub success。 |

## 阻塞记录

- **日期**：2026-06-21
- **原因**：`plan_3.hotfix_7` 修复全局依赖注入规范反转，并同步修正本 Plan 已实现的 Controller 与 Service。
- **当前进度**：7.1 至 7.6 已完成；7.7 与 7.8 仍在进行中，恢复点不变。
- **解除条件**：`plan_3.hotfix_7` 完成实现与验收交接。
- **解除方**：`plan_3.hotfix_7` 执行 session。
- **恢复后的下一步**：继续修复 7.7 Stub HTTP 回归，再执行 7.8 条件验收。

- **日期**：2026-06-19
- **原因**：历史架构阻塞来自 `plan_9` 模块迁移，随后发现 Parent Evidence 与框架基线需要独立前置计划。
- **当前进度**：8 个新任务均未开始。
- **解除条件**：`plan_9`、`plan_6.hotfix_6` 与 `plan_13` 完成。
- **解除方**：对应前置 Plan 的独立验收 session。
- **解除状态**：已于 2026-06-20 全部解除。
- **恢复后的下一步**：已完成最终 grilling 与依赖校准，Plan 转为 `ready`，可从 7.1 开始。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-18 | 创建 plan_7 | 从 plan_6 拆分 Query 链路 | 建立初始业务任务 |
| 2026-06-19 | 迁移为 workflow v2 | plan_8 工作流治理 | 补齐元信息、边界与固定任务结构 |
| 2026-06-19 | 适配 plan_9 模块边界 | crag-admin 迁移为 crag-api，公开 API 与 Smoke 隔离完成 | 使用 crag-api 和各领域 api 包 |
| 2026-06-19 | 完成首轮 grilling 并重写为 7 项任务 | 收敛 Parent Context、三层 LLM 边界、包结构、引用、配置、错误码和验收策略 | 直接前置变为 plan_6.hotfix_6 与 plan_13 |
| 2026-06-20 | 校准 plan_13 依赖治理边界 | Spring Boot 4.1.0 与 Spring AI 2.0.0 基线完成 | 7.4 在 crag-query 独立导入 Spring AI BOM |
| 2026-06-20 | 完成 plan_7 细节 grilling 并转为待开始 | 128 项设计问题全部收敛，前置计划均已完成 | 改用 DeepSeek Anthropic 兼容 API；新增随机 Context 边界、安全日志、协议测试和独立真实验收任务 7.8；整体任务变为 8 项 |
| 2026-06-20 | 补充 7.8 执行授权与安全边界 | 用户确认凭据、少量费用、单次调用、失败停止、密钥注入、日志与测试数据策略 | 真实验收仅主动调用一次；失败后再次调用须明确批准；禁止持久化凭据和敏感证据 |
| 2026-06-20 | 收口 usage 观测与方向归档 | ready 审查发现 LlmResult 与 UserQueryService usage 日志之间缺少数据通路，且 plan_main 技术方向变更缺少归档 | 新增供应商中立 LlmUsage；补充 LLM 韧性候选归档记录 |
| 2026-06-20 | 对照仓库与官方协议补全执行接缝 | Spring AI 2.0.0 Anthropic 已基于官方 SDK，原双超时配置无法由计划指定的 builder 准确兑现；Docker 回归文件和恢复行为未固定 | 收敛为单一 request timeout；固定实现/测试/脚本文件；协议测试必须穿过真实配置 Bean；Stub/DeepSeek 回归结束后恢复默认成功模式 |
| 2026-06-20 | 独立验收失败，退回 7.7 与 7.8 | Stub 成功脚本跳过目标 parent 映射；Stub failure readiness 误判导致失败与恢复均未验证；真实调用未获执行环境批准 | Plan 退回 `in_progress`，修复自动化回归后重新交接独立验收 |
| 2026-06-21 | 被 `plan_3.hotfix_7` 临时中断 | 全局依赖注入规范被错误反转，本 Plan 的 Service 与 Controller 受影响 | 保留 7.7/7.8 恢复点，Hotfix 完成后继续执行 |
