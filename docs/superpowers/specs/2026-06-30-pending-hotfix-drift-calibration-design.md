# Pending Hotfix 漂移校准设计

## 背景

`plan_10.hotfix_1` 与 `plan_7.hotfix_1` 均创建于 2026-06-25，随后项目完成了 Plan 19–21 的多知识库、摄取版本隔离、正式双 API 和单服务 Smoke Profile 收口。两份 Hotfix 仍处于 `ready`，执行前需要重新核对原始缺陷、实现边界和验收方式是否仍符合当前代码。

本设计只确定两份 Hotfix 的校准方向，不直接修改 Plan、脚本或生产代码。

## 审计结论

### plan_10.hotfix_1

原始缺陷仍然存在。`docker_readiness_test.sh` 的 `wait_for_http_status` 使用固定的 `elapsed += 5`，未计算每次最长 35 秒的 `curl` 耗时，因此声明的 120 秒上限可能变成约 16 分钟。`wait_for_health_endpoint` 也存在同类问题；容器状态轮询函数虽然单次命令通常很快，但仍应使用一致的墙钟口径。

原 Plan 的实现决策需要校准：仅将 `elapsed` 改为 `date +%s`，同时保留固定的 35 秒 `curl` timeout，仍可能在截止时间前启动一次完整的 35 秒请求，使实际耗时超过 `max_wait`。因此实现应使用绝对 deadline，并把单次请求和 sleep 裁剪到剩余时间。

### plan_7.hotfix_1

“无 evidence 导致 failure Stub 不被调用”的原始缺陷仍然存在，但原定修复方案已经失效。

Plan 21.4 后，Sparse、Dense 和 Parent Evidence 查询都会关联 `document_ingestion_head` 与状态为 `READY` 的 `ingestion_job`，只召回当前 operation version。旧 `/api/v1/smoke/admin/rag` 入口只写入 chunk，不创建 ingestion head/job，因此不能再作为可召回 evidence 的 seed 来源。

此外，原 Plan 中的 `rag-service-smoke` 和端口 `8083` 已被 Plan 21.11 的单服务 Smoke Profile 收口替代。当前入口是启用 `CRAG_SERVICE_PROFILES=smoke` 的 `rag-service:8082`。

`query_stub_success_test.sh` 仍使用相同的旧 AdminRag seed，因此也已发生相同漂移。若不一并修正，它无法继续作为 failure Hotfix 的“不破坏 success 回归”验收证据。

## 方案比较

### 方案 A：校准现有 Hotfix，继续保留 RAG Smoke 回归

- `plan_10.hotfix_1` 使用 deadline 轮询。
- `plan_7.hotfix_1` 通过 Knowledge Smoke 上传、事件消费和 RAG ingestion READY 准备 evidence。
- 同步修正 `query_stub_success_test.sh`。

优点是保留既有测试意图，改动仍集中在测试基础设施和 Smoke 脚本；缺点是 plan_7 Hotfix 的文件边界和运行依赖会扩大。

### 方案 B：修复旧 AdminRag 诊断入口

让旧 AdminRag 写入同时创建 ingestion head/job，使旧脚本继续可用。

该方案会把正式摄取生命周期逻辑带回遗留诊断入口，扩大生产代码和数据状态机风险，与当前“真实业务只经 Knowledge 上传”的边界冲突，不采用。

### 方案 C：将 Stub failure 验证迁移到正式 Open API

复用 router4 的注册、KB、上传、API Key 和 Open Query 流程，在 failure Stub 下验证正式 API 的 502。

该方案更贴近正式入口，但会把单一 LLM failure 回归扩大为完整 Access/Knowledge/RAG/Open 跨服务场景，执行成本和故障定位成本更高。当前 Hotfix 目标不需要这一级扩张，暂不采用。

## 选定设计

采用方案 A，在原 Hotfix 归属下校准，不新增主 Plan。

### plan_10.hotfix_1 校准

轮询函数统一采用以下语义：

1. 进入函数时计算 `deadline = start + max_wait`。
2. 每轮操作前计算剩余秒数；无剩余时间时立即超时。
3. 带网络等待的调用将 timeout 设置为 `min(原 timeout, 剩余时间)`，且不得小于可执行的最小值。
4. sleep 同样裁剪到剩余时间。
5. 日志展示真实墙钟 elapsed。

范围包括 `wait_for_http_status`、`wait_for_health_endpoint`，并核验 `wait_for_healthy`、`wait_for_unhealthy` 是否应共享相同 deadline 计算。业务断言、数据库故障测试步骤和恢复流程不变。

验收不要求毫秒级硬实时；允许 shell 调度和命令启动造成少量误差，但不得再出现与 `curl` timeout 成倍累积的分钟级超时。

测试应包含：

- `bash -n scripts/tests/http/docker_readiness_test.sh`。
- 可控 hang endpoint 的脚本级 deadline 回归，避免必须通过真实数据库故障才能验证计时算法。
- 完整 Docker readiness 回归，确认失败时仍执行数据库恢复与环境清理。

### plan_7.hotfix_1 校准

failure 和 success 脚本共享当前有效的 evidence 准备流程：

1. 以 `CRAG_SERVICE_PROFILES=smoke` 启动 `db`、`redis`、`sidecar`、`knowledge-service` 和 `rag-service`。
2. 在 success Stub 模式下通过 Knowledge Smoke API 创建唯一知识库。
3. 上传包含唯一 verification code 的文本文件。
4. 轮询 RAG ingestion job，直到该文档进入 `READY`；遇到 `FAILED` 立即失败。
5. 使用显式 `knowledgeBaseId` 调用 `/api/v1/smoke/query`，确认 sources 来自本次文档。
6. failure 脚本仅重建 `rag-service` 为 failure Stub，再用同一 `knowledgeBaseId` 和 verification code 查询，断言 HTTP 502、业务码 50201、`success=false`。
7. 恢复 success Stub 后，再用同一 evidence 查询并确认成功响应，证明环境恢复而非仅进程存活。

`query_stub_success_test.sh` 应同步迁移到该 evidence 准备方式。两个脚本可以提取小型公共 shell helper，但只有在能明显减少重复且不降低单脚本可读性时才这样做；默认优先保持两个脚本自包含。

当前端口和服务名统一为：

- Knowledge Smoke：`knowledge-service:8092`
- RAG Smoke：`rag-service:8082`
- Smoke 启用方式：`CRAG_SERVICE_PROFILES=smoke`

测试数据继续使用唯一 `RUN_ID`，不清空共享表或 volume。临时上传文件必须删除，持久化业务数据因缺少安全精确删除入口而保留唯一标识。

## Plan 文档校准范围

`plan_10.hotfix_1` 需要更新目标精度、关键决策、风险、测试方法和涉及文件。

`plan_7.hotfix_1` 需要更新：

- Plan 19–21 带来的漂移背景。
- Knowledge 上传与 ingestion READY 的新 seed 流程。
- `query_stub_success_test.sh` 文件边界。
- `knowledge-service`、Redis、文件存储和事件链路前置依赖。
- `8082` 端口、统一 `rag-service` 名称和 Smoke Profile 启用方式。
- failure 后的恢复验证必须复用已准备 evidence。

两份 Plan 在校准并通过完整度检查后仍保持 `ready`；先提交 Plan 与索引校准，再开始实现。

## 风险与边界

- 不修改正式业务 API、生产摄取状态机或检索隔离规则。
- 不为兼容旧脚本给正式召回 SQL增加 legacy 例外。
- 不把 plan_7 Hotfix 扩大成完整 router4 正式 API 回归。
- 若校准过程中发现 `query_stub_success_test.sh` 之外还有依赖旧 AdminRag seed 的必跑 Query 脚本，应登记为关联漂移并判断是否纳入同一 Hotfix；不得无记录扩散修改。
- Docker 全链路验证必须由独立验收 session 复跑，不能只依据脚本语法或执行 session 的结果完成 Hotfix。
