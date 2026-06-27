# 归档决策：三业务 Schema 统一沿用幂等初始化基线

- **日期**：2026-06-28
- **变更原因**：早期多租户设计要求 Knowledge、RAG、Access 在首次业务表阶段分别落地版本化迁移，但已经验收的 plan18/plan19 实际采用幂等 Schema SQL，仓库也没有 Flyway/Liquibase 基线。plan20 若单独引入迁移框架，会造成三个业务服务启动机制分叉。
- **关联提交**：plan20 创建提交（同提交创建）

## Before（变更前）

- `plan_main.md` 和 2026-06-22 总体设计要求 router1、router2、router3 分别在首次业务表阶段落地版本化迁移机制。
- 总体设计仍使用早期预留的 plan17–plan20 阶段编号，与实际完成的 plan16–plan19 不一致。
- plan18 Knowledge 与 plan19 RAG 已验收实现使用幂等 `schema-knowledge.sql` / `schema.sql`，没有引入 Flyway 或 Liquibase。

## After（变更后）

- Knowledge、RAG、Access 在当前阶段统一使用各自拥有的幂等 Schema SQL 初始化，不允许 Access 单独形成第三种启动机制。
- plan20 使用 `schema-access.sql`，只访问 Access Schema，并由 Access 数据库账号拥有。
- 若未来引入 Flyway/Liquibase，必须创建独立工程治理主 Plan，同时设计三个业务 Schema 的迁移、基线、回滚和 Docker/测试切换。
- 总体设计的阶段编号同步为实际已创建的 plan14–plan20，尚未创建的双 API 与生命周期阶段继续使用 router4/router5 占位。

## 影响范围

- 受影响的 Plan：`plan_18`、`plan_19` 的已验收实现事实保持不变；`plan_20` 按幂等 Schema 基线执行。
- 受影响的模块：`crag-knowledge-service`、`crag-rag-service`、`crag-access-service` 的数据库启动方向。
- 受影响的约束：本次只校准 `plan_main.md` 和总体设计；持久化硬约束不新增迁移框架规则。

## 迁移与兼容

现有 Knowledge/RAG 代码和数据无需迁移。Access 尚无业务表，plan20 直接创建幂等 `schema-access.sql`。未来迁移框架治理必须先为现有数据库建立可验证 baseline，禁止重复执行已生效 DDL，也不得要求清空共享数据库作为升级条件。

## 回滚可能性

本决策本身不修改运行时代码或数据，可通过撤销本归档和方向文档同步恢复旧表述。但 plan20 实现后若要改为版本化迁移，不能只恢复文字，必须通过新的治理 Plan 迁移三个服务并提供数据库兼容与回滚证据。
