# Java 模块边界收紧与诊断能力隔离

- **日期**：2026-06-19
- **变更原因**：现有包结构文档混合了现状清单、未来设计和架构约束；`crag-admin` 命名无法覆盖 UserQuery，`crag-app` 承载了可直接访问内部组件的 `TestController`，跨模块公开入口也缺少统一边界。
- **关联提交**：`774d5ad`（创建）, `95ca45e`（9.1 ArchUnit）, `2a257a2`（9.2 crag-api）, `0bac69b`（9.3 api 包）, `b8bfab5`（9.4 embedding）, `4796117`（9.5 crag-smoke）, 最终收紧通过 `./gradlew check`。

## Before（变更前）

- `crag-admin` 同时承载管理端和用户端 HTTP API。
- `crag-app` 同时负责启动装配和冒烟诊断 Controller。
- 跨模块调用通过普通 `service`、`embedding`、`result` 等实现包完成。
- 模块依赖方向有文档说明，但没有默认禁止的白名单和自动化校验。

## After（变更后）

- 正式 HTTP 适配层统一命名为 `crag-api`，只负责协议适配。
- 新增 `crag-smoke`，仅在显式 `smoke` Profile 下提供冒烟与内部阶段诊断端点。
- 普通业务模块只能通过被依赖模块的 `api` 包跨模块调用；Storage 保留受限的迁移期例外。
- 模块依赖采用白名单；Gradle 声明由轻量校验器验证，Java 包访问、Repository 内聚、Controller 位置、代码依赖环和 smoke Profile 由 ArchUnit 验证。

## 影响范围

- 受影响的 Plan：`plan_7` 增加 `plan_9` 前置依赖；新增 `plan_9` 执行迁移。
- 受影响的模块：`crag-admin`、`crag-app`、`crag-ingestion`、`crag-retrieval`、`crag-query`，并新增 `crag-smoke`。
- 受影响的约束：`constraints/package-structure.md`、`constraints/test-workflow.md`、`constraints/docker-structure.md`。

## 迁移与兼容

- 迁移按 ArchUnit 基线、`crag-api` 重命名、公开 API 收口、Embedding 契约迁移、`crag-smoke` 建立和最终收紧的顺序执行。
- 正式 AdminRag 和 UserQuery HTTP 路径保持不变。
- `/api/v1/test/**` 只在显式启用 `smoke` Profile 时保留；默认环境不再暴露。
- `plan_7` 在 `plan_9` 完成后继续，业务目标不变，文件路径改用新模块边界。

## 回滚可能性

本次不涉及数据库或不可逆数据迁移。实施过程中每个阶段独立提交；失败时可按逆序撤销模块重命名、包迁移、smoke 装配和 ArchUnit 规则，并恢复原 Gradle module 与默认冒烟端点。若已开始 `plan_7`，必须先停止执行并恢复其记录的 `plan_9` 前置状态。
