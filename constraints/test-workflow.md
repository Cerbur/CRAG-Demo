# CRAG-Demo 测试工作流约束

> 本文档是 CRAG-Demo 测试分类、执行入口、验证证据和失败处理的唯一维护入口。`AGENTS.md`、`CLAUDE.md`、代码风格、Skill 和 Plan 只保留路由或当前任务所需的验收要求，不得另行定义冲突规则。

## 一、测试分层

项目测试分为四类：纯单元测试、轻量组件测试、架构测试和 Docker HTTP 回归。测试类型由它实际加载的运行环境与验证目标决定，不由开发者主观命名决定。

### 1.1 纯单元测试

纯单元测试用于验证隔离的 Java 行为：

- 不启动 Spring Context。
- 不访问真实网络、数据库或文件系统。
- 外部依赖和模块边界使用 Mock、Fake 或 Stub。
- 文件与类名使用 `*Test`。
- 可证明算法、排序、状态流转、输入校验、异常分支和局部业务编排。
- 不能证明 Spring Bean 装配、配置绑定、数据库方言、HTTP 序列化或真实外部服务兼容性。

### 1.2 轻量组件测试

轻量组件测试用于验证受控框架环境中的组件协作：

- 允许使用 Spring Context、Spring Slice、Mock HTTP Client 和 H2。
- 不访问真实网络、PostgreSQL、pgvector 或 Python Sidecar。
- 文件与类名使用 `*ComponentTest`。
- 可证明 Bean 装配、配置绑定、Controller 请求校验、通用序列化映射和受控替身下的业务编排。
- 不能证明 PostgreSQL 方言、native SQL、JSONB、pgvector、锁、CAS、真实事务隔离、容器网络或 Sidecar 协议正确。

H2 仅是轻量组件测试替身。H2 测试通过不得表述为真实数据库兼容、持久化链路完成或业务端到端回归通过。

### 1.3 架构测试

架构测试用于验证静态结构规则：

- 验证包边界、模块依赖、公开 API、命名和禁止依赖。
- 文件与类名使用 `*ArchitectureTest`。
- 不承担业务流程、HTTP 行为或真实基础设施验证。
- 临时例外必须关联未完成 Plan 任务，写明移除条件；禁止无期限宽泛豁免。

### 1.4 Docker HTTP 回归

Docker HTTP 回归用于验证真实运行时业务链路：

- 必须通过项目 Docker Compose 启动所需的 Spring Boot、PostgreSQL、pgvector、Sidecar 或其他真实依赖。
- 必须从 Compose 暴露的 HTTP 入口发起业务调用。
- 自动化脚本统一位于 `scripts/tests/http/`。
- `docker compose ps`、`logs`、`exec` 和数据库查询只可作为健康状态、诊断或结果辅助证据，不得替代 HTTP 业务入口。
- 可证明容器装配、真实配置、HTTP 契约、PostgreSQL 与 pgvector 行为、Sidecar 协议和跨模块业务链路。

可靠事件基础设施的真实 Redis Streams 行为（`XREADGROUP`/`XPENDING`/`XCLAIM`）由 `scripts/tests/http/event_smoke_{success,dlq,default_disabled}_test.sh` 通过原 `knowledge-service`（启用 `CRAG_SERVICE_PROFILES=smoke`）的 `/api/v1/smoke/events/**` HTTP 入口证明；H2/fake 单元与组件测试不得表述为该真实链路的替代证据。

Knowledge 垂直链路（KnowledgeBase、Document 单次流式上传、文件存储、读取、`DOC_UPLOADED` 发布）由 `scripts/tests/http/knowledge_smoke_{default_disabled,upload_txt,upload_md,upload_invalid,event_published}_test.sh` 通过原 `knowledge-service`（启用 `CRAG_SERVICE_PROFILES=smoke`）的 `/api/v1/smoke/knowledge/**` HTTP 入口（固定本地诊断端口 8092）证明真实 PostgreSQL、文件 volume 与 Redis Streams 发布链路；默认 profile 不暴露该入口。

router2 RAG 多知识库链路（消费 `DOC_UPLOADED`、Knowledge gRPC 读取、切分、Dense/Sparse 索引、按 `knowledgeBaseId` 查询隔离、状态事件发布）由 `scripts/tests/http/rag_smoke_multi_kb_{ingestion,isolation}_test.sh`、`rag_smoke_doc_uploaded_{idempotency,dlq}_test.sh` 与 `rag_smoke_ingestion_status_event_test.sh` 通过原 `knowledge-service`（启用 `CRAG_SERVICE_PROFILES=smoke`，上传入口 8092）与原 `rag-service`（启用 `CRAG_SERVICE_PROFILES=smoke`，`/api/v1/smoke/rag/ingestion/**`、`/api/v1/smoke/query`、`/api/v1/smoke/test/retrieval/**` HTTP 入口，固定本地诊断端口 8082）证明真实 PostgreSQL + pgvector + Redis Streams + Knowledge gRPC 全链路；默认 profile 不暴露 router2 smoke 入口。

router3 Access 垂直链路（注册/登录/刷新、Refresh 复用撤销 Family、Membership 角色与最后 OWNER 保护、Scope/API Key 生命周期与鉴权、`API_KEY_INVALIDATED` 失效事件、并发刷新仅一次成功）由 `scripts/tests/http/access_smoke_{default_disabled,identity,membership,session_reuse,api_key,event,concurrent_refresh}_test.sh` 通过原 `access-service`（启用 `CRAG_SERVICE_PROFILES=smoke`）的 `/api/v1/smoke/access/**` HTTP 入口（固定本地诊断端口 8091）证明真实 PostgreSQL + Redis（Snowflake Worker lease + Redis Streams）+ RS256 JWT + Argon2id 全链路；默认 profile 不暴露 Access smoke 入口。

## 二、Gradle 与 Docker 执行入口

### 2.1 Gradle 任务

- `./gradlew test` 执行纯单元测试、轻量组件测试和架构测试，不启动 Docker。
- `./gradlew check` 在测试基础上执行格式、静态检查和 Plan 校验，不隐式启动 Docker。
- 可使用模块任务或 `--tests` 缩小开发期反馈范围，但最终验收必须执行 Plan 要求的完整任务。
- 测试命名必须兼容 Gradle 默认发现规则；若需定制发现规则，必须在对应 Plan 中说明原因和影响。

### 2.2 Docker HTTP 回归入口

- Docker HTTP 回归使用 `docker compose` 启动项目内定义的服务。
- 禁止为回归验证绕过 Docker 直接启动 Java 或 Python 服务。
- 禁止示例：`./gradlew bootRun`、`java -jar`、直接运行 Python Sidecar 或 `uvicorn`。
- 允许示例：`docker compose up -d --build`、执行 `scripts/tests/http/` 下的脚本、`docker compose logs`。
- 测试 Java 后端时调用 Compose 暴露的 Spring Boot 端口；测试 Sidecar 时调用 Compose 中 Sidecar 暴露的 HTTP 接口。
- 不得用宿主机临时数据库或直接 import Python 模块替代 Compose 中的真实依赖。

## 三、行为覆盖要求

新增或修改核心逻辑时，必须补充与风险匹配的测试。核心逻辑包括但不限于：

- RAG 写入、检索、融合、重排、上下文组装和 LLM 调用编排。
- 算法、排序、去重、分数计算、阈值过滤、状态流转和幂等控制。
- DAO / Repository 中的 native SQL 参数、返回列映射、CAS、锁和向量格式转换。
- Controller / Service 的请求校验、边界输入、异常转换和失败路径。

测试至少覆盖适用的以下行为：

- 正常路径。
- 空输入、非法输入、空结果和边界值。
- 核心分支与失败路径。
- 已修复缺陷的可复现回归。
- 外部依赖异常、状态冲突、版本冲突或数据映射异常。

项目不设置全局覆盖率百分比门禁。覆盖是否充分以可观察行为、风险和验收标准为依据，不以行覆盖率替代测试设计。

暂时无法覆盖的核心行为必须在对应 Plan 中记录原因、替代验证、风险、责任任务和移除条件。

## 四、Docker HTTP 回归触发规则

变更涉及以下任一范围时，至少执行受影响业务链路的 Docker HTTP 回归：

- Controller、HTTP DTO、统一响应或异常映射。
- Spring 配置、Bean 装配、Profile 或运行时配置绑定。
- Entity、Repository、DAO、native SQL、事务、锁或 CAS。
- PostgreSQL、pgvector、数据库 schema 或迁移。
- Sidecar Client、外部服务协议、序列化或超时错误处理。
- 跨模块业务编排、正式业务入口或 Docker 部署文件。

新增业务链路或跨模块大改必须执行完整相关链路回归，不能只证明局部组件通过。仅修改纯算法且公共契约、装配和基础设施均未变化时，可由纯单元测试完成验证，但 Plan 或 Review 必须说明风险判断。

稳定核心链路必须沉淀为自动化 HTTP 回归脚本。首次新增或实质修改尚无自动化脚本的稳定链路时，当前 Plan 负责补齐脚本。手工 `curl` 可用于探索和故障定位，但不能作为完成验收的唯一证据。

自动化脚本必须：

- 以非零退出码表达失败。
- 对 HTTP 状态、响应结构和关键业务结果做明确断言。
- 输出可定位失败阶段的简洁信息。
- 可重复执行，不依赖人工修改容器或数据库状态。

## 五、测试数据隔离

每次 Docker HTTP 回归必须生成唯一 `runId`，并将其写入可查询的业务标识、测试文档内容或其他可追踪字段。

- 只清理本次 `runId` 创建的数据。
- 禁止清空共享表、重建数据库、删除共享 volume 或执行 `docker compose down -v`。
- 不得删除其他开发者、Agent、Plan 或历史回归的数据。
- 清理失败时必须保留可识别标记，并在验收记录中说明残留范围和后续处理。
- 若当前业务没有安全的精确清理入口，脚本必须保证数据唯一且不影响后续断言，不得用破坏性清理绕过设计限制。

## 六、LLM 与外部供应商验收

Query 等依赖生成式模型的必跑回归必须使用确定性的 LLM Stub，保证输出、错误和边界场景可稳定断言。

真实供应商调用属于条件验收：

- 修改供应商配置、认证、SDK、HTTP 协议、模型参数、请求或响应映射时，真实调用是完成门槛。
- 缺少凭据、额度、网络或供应商可用性时，不得静默跳过或把 Stub 结果表述为真实兼容。
- 条件不满足时，Plan 必须进入阻塞或保留未完成任务，并记录解除条件、风险和责任方。
- 不涉及供应商边界的纯业务改动，可由 Stub 完成必跑回归；真实调用是否执行按 Plan 风险说明。

测试输出、日志和报告禁止记录密钥、Authorization、完整用户文档、完整 Prompt 或敏感响应。

## 七、失败、重跑、跳过与 flaky

- 测试失败必须先保留原始失败证据并分析原因，再修正代码、测试或环境。
- 未做任何修正就重跑通过，视为疑似 flaky，不得直接作为稳定通过证据。
- 疑似 flaky 测试必须重复验证、定位非确定性来源，并记录观察结果；未消除时不得作为完成门槛的通过项。
- 禁止通过放宽断言、增加无依据等待时间、关闭测试或吞掉异常来隐藏失败。
- 临时跳过必须关联未完成 Plan 任务，注明原因、风险、责任方和移除条件。
- 必需测试存在跳过、环境阻塞或未解释残余风险时，任务和 Plan 不得标记完成。
- 时间、随机数、ID、排序和异步结果等非确定性来源必须可控或有明确稳定断言。

## 八、Plan 与验收证据

Plan 的测试与验证部分必须按适用层级列出：

- 测试文件、用例和所属测试类型。
- 每项用例覆盖的验收标准或风险。
- 可复现的精确命令。
- 外部依赖、Profile、凭据和测试数据准备。
- 预期结果。
- 实际执行结果、日期和环境。
- 未执行或跳过项的原因、风险、责任任务和后续动作。

独立验收 session 负责运行并核对最终验收命令，且不得依赖执行 session 的口头结论。纯单元、轻量组件和架构测试可通过 Gradle 执行；Docker HTTP 回归必须通过 Docker Compose 执行。

大型测试报告保存在既有 `build/` 路径，Plan 只记录链接或摘要，不粘贴大段终端输出。完成标准同时受 `constraints/plan-workflow.md` 约束。

## 九、Benchmark / Evaluation Skill 路由

涉及以下意图时，必须先查看 `skill/README.md`，并按 `skill/crag-benchmark/SKILL.md` 执行：

- benchmark、evaluation、评估集、质量评估和链路质量。
- 随机测试数据、golden tests、adversarial examples 和 distribution samples。
- Retrieval / Query / RAG 的质量回归、前后对比、Prompt 或 Rerank 参数评估。
- Top1、TopK、命中率、置信区间、回归检测和样本量判断。
- 生成或分析 `build/benchmark/` 下的报告。

Benchmark 不改变本文件的执行边界：纯逻辑可用 Gradle；涉及真实 Spring Boot、PostgreSQL、pgvector、Sidecar 或业务 HTTP 入口时必须使用 Docker Compose。

## 十、维护同步

- 新增测试、脚本、文档或 Plan 验收标准时，必须使用本文件定义的四类术语。
- `constraints/code-style.md` 只维护测试代码写法，不重复定义执行分类。
- Skill 只路由到本文件并按风险选择测试，不复制完整规则。
- 新增或修改核心逻辑的 Plan 必须包含相应行为测试和失败路径要求。
- Docker 服务名、端口或部署结构变化时，同步检查本文档与 `constraints/docker-structure.md`。
- 测试分类、命名、执行入口或完成门槛变化属于全局工程治理，必须通过对应 Plan 修改。
