# 归档决策：从单进程 RAG Demo 演进为多租户知识平台

- **日期**：2026-06-22
- **变更原因**：现有纯文本入库和单知识空间已经完成 RAG 主链路验证，无法承载租户隔离、文件生命周期、API Key 查询和跨服务可靠性要求，需要建立下一阶段的长期产品与架构方向。
- **关联提交**：`f12bdef`

## Before（变更前）

- 项目定位为单个 Spring Boot 进程承载的 RAG Demo。
- 对外只有 AdminRag 纯文本写入和 UserQuery 查询入口。
- 不实现用户、租户、权限、多知识库和文件上传。
- Access、KnowledgeBase 仅作为未来领域边界占位。
- 异步处理统一依赖进程内 Cron 扫表。
- Chunk 使用 UUID，所有 RAG 数据属于同一个隐含知识空间。

## After（变更后）

- 项目演进为支持 Tenant、User、Membership、KnowledgeBase、Document 和 API Key 的多租户知识平台。
- 目标部署包含 Console API、Open API、Access Service、Knowledge Service 和 RAG Service 五个独立进程。
- Access、Knowledge、RAG 使用独立 PostgreSQL Schema、账号和迁移边界。
- 同步服务通信使用 gRPC，异步生命周期事件使用 Redis Streams、Outbox 和幂等消费。
- ID 统一演进为 Snowflake `long / BIGINT`，跨语言边界使用十进制字符串。
- 文件首版支持 `.txt / .md` 和 Docker Volume；RAG 全链路强制按 `knowledgeBaseId` 隔离。
- Ingestion、Retrieval、Query 保持独立模块职责，但继续共同部署于 RAG Service。
- 上传、索引、API Key 失效和删除建立可靠投递、补偿、死信和可观测性闭环。

## 影响范围

- 受影响的 Plan：后续新建的多租户平台主 Plan；已完成历史 Plan 保持不变。
- 受影响的模块：现有所有 Java 模块、数据库结构、Docker 拓扑和 HTTP 入口将在分阶段 Plan 中迁移。
- 受影响的约束：后续 Plan 需要按实际落地同步更新包结构、Docker、API、持久化、Retrieval 和测试约束。
- 设计事实来源：`docs/superpowers/specs/2026-06-22-multi-tenant-knowledge-platform-design.md`。

## 迁移与兼容

- 不迁移当前 Demo 数据，进入新数据模型时允许重建数据库。
- 已完成 Plan 和实现提交作为历史基线保留，不回写其范围或完成状态。
- 迁移按服务化基线、分布式 ID、事件基础设施、Knowledge、RAG 隔离、Access、双 API、生命周期可靠性分阶段执行。
- 每个阶段通过独立主 Plan、实现提交和独立验收交付；后续阶段只有在显式前置 Plan 完成后才可执行。
- 迁移期间允许旧入口与新服务骨架短期并存，但具体兼容窗口必须由对应 Plan 明确。

## 回滚可能性

本次变更只更新方向文档，不改变运行时代码、数据或部署，可以通过撤销本归档和 `plan_main.md` 的对应提交恢复旧方向。进入具体实现 Plan 后，单阶段按其回滚策略撤销；由于新平台不迁移现有数据，不承诺从新 Schema 反向迁移到旧 Demo Schema。
