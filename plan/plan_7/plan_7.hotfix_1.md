---
workflow_version: 3
plan_id: plan_7.hotfix_1
type: hotfix
parent_plan: plan_7
status: verifying
created: 2026-06-25
updated: 2026-06-30
---

# plan_7.hotfix_1 — Query Stub success/failure evidence 链路修正

## 背景与目标

`scripts/tests/http/query_stub_failure_test.sh`（plan_7 任务 7.7 引入的 Stub failure Docker 回归）Phase 1 直接以 `{"question":"测试问题"}` 发起 failure 模式查询，未先写入任何证据文档。`UserQueryService.answer()` 在检索证据为空时短路返回 `"知识库证据不足"`（HTTP 200、`code=0`），**不调用 LLM**，因此 Stub failure 模式从不触发，脚本永远拿不到期望的 502，Docker 回归稳定失败。

该缺陷在 plan_16 独立验收时定位并复核：plan_16 对 `UserQueryService`、`StubLlmAdapter` 0 行改动（纯文件迁移），失败路径（LLM 不可用 → 502/50201）本身经独立复跑与 `UserQueryControllerComponentTest.llmUnavailableReturns502` 验证正确；问题纯粹是脚本未准备 evidence，属 plan_7 引入的测试设计遗漏。

Plan 21.4 后，Sparse、Dense 与 Parent Evidence 查询均要求匹配当前 `document_ingestion_head` 和状态为 `READY` 的 `ingestion_job`。旧 `/api/v1/smoke/admin/rag` 只写 chunk，不创建 head/job，因而已不能作为可召回 evidence 的 seed。`query_stub_success_test.sh` 仍使用同一旧路径，也已无法证明当前 Query success 链路。

Plan 21.11 又将 `rag-service-smoke:8083` 收口为启用 `CRAG_SERVICE_PROFILES=smoke` 的原 `rag-service:8082`。原 Hotfix 的根因仍成立，但 seed 方案、前置服务、文件边界与验收命令均已漂移。

**目标**：将 Query Stub success/failure 回归迁移到当前 Knowledge 上传 → `DOC_UPLOADED` → RAG ingestion READY → 显式 `knowledgeBaseId` Query 链路；failure 模式在已有可召回 evidence 的前提下稳定触发 LLM 异常并断言 502/50201。

## 范围

- 修改 `query_stub_success_test.sh`：通过 Knowledge Smoke API 创建唯一 KB、上传唯一文本、等待 RAG ingestion job `READY`，再以显式 `knowledgeBaseId` 查询并校验 sources。
- 修改 `query_stub_failure_test.sh`：先以 failure Stub 重建 `rag-service`（schema.sql 冷启动重建会清空旧召回证据），再 seed 唯一 KB 至 `READY` 并断言 502/50201/`success=false`；恢复 success Stub 后重新 seed 唯一 KB 至 `READY` 并断言成功。每个 Phase 在各自 Stub 模式下独立 seed。
- 当前服务拓扑固定为 `knowledge-service:8092` 与 `rag-service:8082`，通过 `CRAG_SERVICE_PROFILES=smoke` 启用 Smoke Controller。

## 非目标

- 不修改任何生产代码（`UserQueryService`、`StubLlmAdapter`、`GlobalExceptionHandler` 行为均已正确）。
- 不修改 failure 路径的 HTTP 契约、错误码或异常映射。
- 不改动 failure 断言结构（502/50201/`success=false`）或 `RUN_ID` 数据隔离约定。
- 不恢复旧 AdminRag 的召回资格，不给正式检索 SQL 增加 legacy 例外。
- 不迁移到 Console/Open 正式 API，不把本 Hotfix 扩大为 router4 全链路回归。
- 不触碰其他 HTTP 回归脚本。

## 前置依赖

- **执行前置 Plan**：无
- 所属主 Plan `plan_7` 已完成；Plan 19 的 Knowledge/RAG ingestion、Plan 21.4 的 active-version 召回与 Plan 21.11 的单服务 Smoke 拓扑均已完成。
- 执行依赖 Docker Compose 中 `db`、`redis`、`sidecar`、`knowledge-service`、`rag-service` 可构建；Knowledge 文件目录可写；`rag-service` 默认使用 success Stub。

## 文件边界

- `scripts/tests/http/query_stub_success_test.sh`
- `scripts/tests/http/query_stub_failure_test.sh`

## 关联范围与规模说明

- 关联主 Plan：`plan_7`（两份 Stub Query 脚本与 failure 路径测试的引入者，任务 7.7）；Plan 19–21 改变了合法 seed 与服务拓扑，但「failure 查询未准备可召回 evidence」仍源自 plan_7 原始测试设计。
- 规模：2 个有效任务、2 个测试脚本，仍属于同一 Query Stub 回归能力，不升级为主 Plan。

## 关键决策

- seed 必须走当前正式摄取状态机的 Smoke 入口：Knowledge 创建 KB + 上传文件，RAG 消费事件并等待 ingestion job `READY`；禁止继续使用只写 chunk 的旧 AdminRag。
- success 脚本先迁移并成为 evidence 准备模式的基线；failure 脚本复用相同的 seed 顺序和断言口径，但两个脚本默认保持自包含，避免引入难以观察的跨脚本状态。
- **schema.sql 冷启动重建使「同一 evidence 跨重建复用」不可行**：`application.yml` 设 `spring.sql.init.mode: always`，`schema.sql` 在每次 rag-service 启动时 `DROP` `chunk`/`chunk_embedding`/`chunk_fts`/`ingestion_job`（`document_ingestion_head` 不在此列）。切换 `CRAG_QUERY_LLM_STUB_MODE` 必须重建 rag-service，重建即触发 schema 重置、清空全部召回证据。因此 failure 脚本改为「先按目标模式重建 rag-service → 再 seed evidence → 再查询」：每个 Phase 在各自 Stub 模式下独立 seed 唯一 KB 并等待 `READY`。
- failure 路径断言不变（仍 502/50201/`success=false`）。Phase 1 的 502 本身即自证 evidence 已被召回（否则 `UserQueryService` 在证据为空时短路返回 `code=0`，不会触达 LLM）。
- Phase 2 恢复不能只用无 evidence 查询检查 `code=0`；必须在恢复 success Stub 后重新 seed 唯一 KB 并等待 `READY`，再断言 `code=0`、固定 Stub answer 与非空 sources，证明 LLM Stub 和 Query 链路均已恢复。
- 不引入破坏性清理：临时上传文件必须删除，业务数据沿用唯一 `RUN_ID` 保留，因为当前没有安全精确删除入口。
- 残留风险（非本 Hotfix 范围）：`spring.sql.init.mode: always` + `DROP TABLE` 使 rag-service 任何重启都清空召回证据，影响所有「seed 后重启」类工作流；建议后续 Hotfix 评估是否改为幂等 `CREATE` 并按需关闭 `mode: always`，本 Hotfix 仅在脚本层规避。

## 未决问题

无。

## 风险与回滚

- 风险：Knowledge 上传、Redis Event、gRPC 读取和双索引引入更多异步依赖。预防：以 ingestion job `READY` 作为唯一放行条件，遇到 `FAILED` 立即输出 RAG/Knowledge 日志并失败。
- 风险：模式重建与 evidence 写入顺序错误会导致 failure 路径仍不可达（重建会清空 evidence）。预防：固定「先按目标模式重建 rag-service → 再 seed → 等待 READY → 再查询」顺序，并在注释中标注；禁止在重建之前 seed 并指望 evidence 存活。
- 风险：重建 failure Stub 后脚本异常退出，环境残留 failure 模式。预防：failure 脚本使用 trap 恢复 success+smoke Stub；Phase 2 完成恢复后置位，trap 不再重复重建。回滚：`git revert` 对应脚本实现提交。
- 风险：两个脚本复制 evidence 准备逻辑后发生漂移。预防：failure 脚本用 `seed_and_wait` helper 复用 seed 顺序与断言口径；只有重复代码已明显妨碍维护时才跨脚本提取公共 helper，提取需先更新 Plan 文件边界。

## 测试与验证计划

- 语法：`bash -n scripts/tests/http/query_stub_success_test.sh scripts/tests/http/query_stub_failure_test.sh`。
- Success 基线：`bash scripts/tests/http/query_stub_success_test.sh`，确认唯一文档 ingestion READY、显式 KB Query 返回固定 Stub answer 且 sources 命中本次文档。
- Failure 回归：`bash scripts/tests/http/query_stub_failure_test.sh`，确认 Phase 1 在 failure Stub 下 seed 后返回 502/50201/`success=false`（自证 evidence 已召回）；Phase 2 恢复 success Stub 后重新 seed 并返回 `code=0`、固定 Stub answer 与非空 sources。
- 运行后检查 `docker compose ps`，确认 `rag-service` 已恢复 success Stub；检查临时文件已删除，未执行 `down -v` 或共享数据清空。
- 无 Gradle/单元测试变更（纯 shell 脚本）。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 7.hotfix_1.1 | 迁移 Query Stub success 回归到当前 ingestion READY evidence | ⏳ 待验收 | 114715cd | — |
| 7.hotfix_1.2 | 在当前 evidence 链路上修复 failure 与恢复验证 | ⏳ 待验收 | af46eda6 | — |

整体进度：0 / 2（0%）

## 7.hotfix_1.1 迁移 Query Stub success 回归到当前 ingestion READY evidence

**目标**：让 `query_stub_success_test.sh` 通过当前 Knowledge/RAG 摄取状态机准备可召回 evidence，为 success 与 failure 回归建立可信基线。
**前置任务**：无
**范围**：脚本启动当前五项依赖服务并等待 Knowledge/RAG readiness；通过 `POST /api/v1/smoke/knowledge/knowledge-bases` 创建唯一 KB，通过 multipart `/documents/upload` 上传含 `VERIFICATION_CODE` 的临时 `.txt`；轮询 `/api/v1/smoke/rag/ingestion/job?knowledgeBaseId=...&docId=...` 到 `READY`；向 `http://localhost:8082/api/v1/smoke/query` 发送包含显式 `knowledgeBaseId` 的请求；断言 `code=0`、固定 Stub answer、sources 非空且属于本次 KB 文档；使用 trap 删除临时文件。
**非目标**：不继续使用 `/api/v1/smoke/admin/rag`；不修改生产代码、正式 Console/Open API 或其他脚本；不清空表、volume 或历史回归数据。
**验收标准**：success 脚本在干净或已有共享数据的 Compose 环境中退出码 0；ingestion 必须实际到达 `READY`；查询明确携带本次新建 `knowledgeBaseId`，该 KB 仅含本次文档且 sources 非空；脚本结束删除临时文件且不执行破坏性清理。
**验证方式**：`bash -n scripts/tests/http/query_stub_success_test.sh`；`bash scripts/tests/http/query_stub_success_test.sh`；人工检查输出含 KB/doc ID、ingestion READY、固定 Stub answer 与新建 KB 的非空 sources；`git diff -- scripts/tests/http/query_stub_success_test.sh` 确认旧 AdminRag seed 已完全移除。
**涉及文件**：`scripts/tests/http/query_stub_success_test.sh`

## 7.hotfix_1.2 在当前 evidence 链路上修复 failure 与恢复验证

**目标**：在可召回的当前 ingestion evidence 上触发 failure Stub 并断言 502/50201/`success=false`，退出前恢复 success Stub 并复验成功链路。
**前置任务**：7.hotfix_1.1
**范围**：failure 脚本先以 `CRAG_QUERY_LLM_STUB_MODE=failure CRAG_SERVICE_PROFILES=smoke docker compose up -d --build rag-service` 重建 rag-service（schema.sql 冷启动重建会清空旧 evidence，故必须先重建再 seed），再用 7.hotfix_1.1 的 Knowledge 上传 → ingestion `READY` → 显式 `knowledgeBaseId` Query 顺序 seed 唯一 KB，断言 HTTP 502、code 50201、`success=false`（该 502 自证 evidence 已召回）；随后以 success 模式重建 rag-service，重新 seed 唯一 KB 并断言 `code=0`、固定 Stub answer、sources 非空；trap 与 Phase 2 都以 success+smoke 模式重建 rag-service。
**非目标**：不修改 502/50201 HTTP 契约；不迁移到 Open API；不修改任务 7.hotfix_1.1 之外的脚本；不改动生产代码、`docker-compose.yml` 或 schema 初始化（schema.sql 冷启动重置作为已知约束在脚本层规避，不在本任务修复）。
**验收标准**：failure 脚本退出码 0；Phase 1 在 failure Stub 下 seed 至 `READY` 后查询返回 502/50201/`success=false`（证明 failure 路径在已有 evidence 下可达）；Phase 2 恢复 success Stub 后重新 seed 至 `READY` 并返回固定 Stub answer 与非空 sources；无论中途成功或失败，脚本退出后 `rag-service` 均恢复 success+smoke Stub。
**验证方式**：`bash -n scripts/tests/http/query_stub_failure_test.sh`；`bash scripts/tests/http/query_stub_failure_test.sh`；随后再次运行 `bash scripts/tests/http/query_stub_success_test.sh`；检查 `docker compose ps rag-service` 健康（success Stub），并确认日志不输出完整文档、Prompt、Context 或密钥。
**涉及文件**：`scripts/tests/http/query_stub_failure_test.sh`

## 验收记录

> 待执行 session 完成实现与自测后，由未参与实现的独立验收 session 给出最终判定。

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-30 | Docker Compose（macOS；knowledge-service:8092 / rag-service:8082，CRAG_SERVICE_PROFILES=smoke；sidecar 模型已缓存） | `bash -n scripts/tests/http/query_stub_success_test.sh scripts/tests/http/query_stub_failure_test.sh` | 通过 | 两脚本语法 OK |
| 2026-06-30 | 同上 | `bash scripts/tests/http/query_stub_success_test.sh` | 通过 | KB=81/doc=69（首次）/KB=89/doc=77（failure 后复跑）→ ingestion READY → Query `code=0`、固定 Stub answer、sources 非空命中本次文档；旧 AdminRag seed 已完全移除 |
| 2026-06-30 | 同上 | `bash scripts/tests/http/query_stub_failure_test.sh` | 通过 | Phase 1：failure Stub 重建 → seed KB=87/doc=75 → READY → Query `HTTP 502 / code=50201 / success=false`（502 自证 evidence 已召回）；Phase 2：success Stub 重建 → re-seed KB=88/doc=76 → READY → Query `code=0`/固定 Stub answer/sources=1 |
| 2026-06-30 | 同上 | `docker compose ps rag-service` + rag-service 日志敏感词检查 | 通过 | rag-service 恢复 success Stub 且 healthy；日志未输出完整文档、Prompt、Context 或密钥 |
| 2026-06-30 | 同上 | 执行期根因排查（见变更记录 2026-06-30） | N/A | `spring.sql.init.mode: always` + `schema.sql` 每次 rag-service 启动 `DROP` chunk/ingestion_job 等表；最小复现重建后 `chunk` 2→0、job READY→NONE。`SPRING_SQL_INIT_MODE` 未在 Compose 暴露，无代码/部署改动的同源 workaround 不可达 → 7.hotfix_1.2 改为「先按模式重建 → 再 seed」 |

## 阻塞记录

无。发生阻塞时记录原因、当前进度、解除条件、解除方、下一步与日期。

## 废弃任务记录

无。任务废弃时记录原因、日期及替代任务或决策。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-25 | 创建 Hotfix | plan_16 独立验收定位 query_stub_failure_test.sh 因未 seed evidence 导致 failure 路径不可达；缺陷源自 plan_7 任务 7.7 原始测试设计 | 初始范围为单脚本 evidence 准备修复 |
| 2026-06-30 | 按 Plan 19–21 当前落地校准 | active-version 召回使旧 AdminRag seed 不再可召回；单服务 Smoke 拓扑替代 `rag-service-smoke:8083`；success 脚本存在同源漂移 | 状态保持 ready；扩展为 2 个脚本、2 个任务，改走 Knowledge 上传与 ingestion READY |
| 2026-06-30 | 执行期发现并校准 7.hotfix_1.2 evidence 策略 | 执行 session 在 Docker 回归中发现 `spring.sql.init.mode: always` + `schema.sql` 每次 rag-service 启动 `DROP` `chunk`/`ingestion_job` 等表，使「切换 Stub 模式重建后复用同一 evidence」不可行（最小复现：重建后 `chunk` 2→0、job READY→NONE）。`SPRING_SQL_INIT_MODE` 未在 Compose `environment` 暴露，无代码/部署改动的同源 workaround 不可达 | 7.hotfix_1.2 改为「先按目标模式重建 → 再 seed 唯一 KB → 等 READY → 查询」：Phase 1 failure 下断言 502/50201/`success=false`（502 自证 evidence 已召回），Phase 2 success 下重新 seed 断言成功；文件边界不变（仅 2 个脚本），502/50201 契约不变；schema.sql 冷启动重置作为残留风险记录，留待后续 Hotfix |
