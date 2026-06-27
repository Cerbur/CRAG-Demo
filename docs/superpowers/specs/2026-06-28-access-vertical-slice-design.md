# CRAG-Demo Access 垂直链路设计

日期：2026-06-28

状态：设计已确认，待书面规格复核

范围：router3 / plan20 的 Access Provider；不包含正式 Console/Open HTTP 入口与生命周期补偿

## 1. 背景

`plan_18` 已完成 Knowledge 垂直链路，`plan_19` 已完成 RAG 多知识库化。当前 `crag-access-service` 仍只有服务骨架、Platform Probe 和 Access Schema readiness，没有真实用户、租户、会话、权限或 API Key 能力，也没有 Access 领域 gRPC 契约。

router3 需要在不提前实现 router4 HTTP 编排和 router5 生命周期补偿的前提下，完整交付 Access Provider：User、Account、Tenant、Membership、JWT、Refresh Session、API Key、`api_key_scope`、失效事件生产端、gRPC 和 smoke-only 验收入口。

## 2. 目标

1. 建立 User 与 Login Account 分离的身份模型，以 Snowflake UID 作为永久用户标识。
2. 支持 Username/密码注册与登录，注册时原子创建默认 Tenant 和 OWNER Membership。
3. 支持身份型 Access JWT、Refresh Session 轮换、复用检测和 Session Family 撤销。
4. 支持 OWNER/MEMBER Membership 生命周期和固定权限矩阵，并保证 Tenant 始终至少有一名有效 OWNER。
5. 支持 KnowledgeBase 授权投影、单 KnowledgeBase API Key 的完整生命周期和鉴权。
6. 通过本地 Outbox 可靠发布 API Key 缓存失效事实。
7. 以窄 gRPC 契约暴露 Access 能力，以 smoke-only HTTP 和 Docker 回归证明真实 PostgreSQL/Redis 环境行为。

## 3. 非目标

- 不实现正式 Console API、Open API、Cookie、CSRF 或浏览器传输策略。
- 不实现 Open API 的 API Key 鉴权缓存或失效事件消费者。
- 不实现邀请 Token、邀请邮件、待接受 Membership 或跨租户用户搜索。
- 不实现 Email、OAuth、手机号等登录流程；Account 模型仅为后续扩展保留类型边界。
- 不实现改 Username、改密码、密码找回、退出全部设备、MFA、验证码、设备指纹或登录限流。
- 不实现 Document/KnowledgeBase 删除编排、Scope Reconciler、补偿扫描、告警平台或运维重放界面。
- 不迁移旧 Demo 数据，不拆分新的 Access 服务进程或额外 Gradle 业务模块。

## 4. 总体结构

### 4.1 Gradle 模块

新增 `crag-access-contracts`，只保存 Access 领域 Protobuf/gRPC 契约，不依赖 Spring、runtime 或任何 Service module。

`crag-access-service` 是唯一 Access 部署单元。它可以依赖 `crag-access-contracts`、`crag-platform-contracts`、`crag-grpc-runtime`、`crag-common`、`crag-event` 和 `crag-id`，禁止依赖 Knowledge、RAG、Console 或 Open Service module。

### 4.2 包结构

```text
ai.cerbur.crag.access
├── AccessServiceApplication
├── core
│   ├── identity/       User、Account、注册与密码认证
│   ├── membership/     Tenant、Membership 与权限矩阵
│   ├── session/        JWT、Refresh Session、轮换与复用检测
│   └── apikey/         Scope、API Key 生命周期与鉴权
├── dao/
│   ├── entity/
│   └── repository/
├── grpc/
│   ├── provider/
│   ├── mapper/
│   └── error/
├── security/           Argon2id、HMAC、随机秘密和 JWT 签名适配
├── producer/           API_KEY_INVALIDATED Outbox 事件
├── controller/smoke/   仅 smoke Profile 的验收入口
└── probe/
```

边界规则：

- gRPC Provider 只负责协议校验、映射和错误转换，业务规则位于 `core`。
- Repository 只负责单表持久化且只能由同模块 DAO 调用；事务边界位于 Core Service 或 DAO。
- `core` 不依赖 gRPC、Controller、Producer 或持久化 Entity。
- `controller.smoke` 与 gRPC Provider 复用同一组 Core Service，不复制业务逻辑。
- Access 不跨 Schema 查询，不调用 Knowledge/RAG Service，只保存外部资源 ID。

## 5. gRPC 契约

Access contracts 按调用目的拆分为三个窄服务。

### 5.1 IdentityService

- `Register`：创建 User、USERNAME Account、默认 Tenant、OWNER Membership 和首个 Refresh Session，返回身份摘要与 Token 材料。
- `Login`：校验 Username/密码并创建新的 Session Family。
- `Refresh`：轮换 Refresh Token 并签发新的 Access JWT。
- `Logout`：撤销当前 Session Family。
- `GetJwtVerificationKeys`：返回当前可用于本地验签的公钥集，不返回私钥。

### 5.2 MembershipService

- `AuthorizeTenantAction`：按固定 `TenantAction`、实时用户状态、Membership 状态和角色做权限判断。
- `AddMemberByUsername`：OWNER 将已注册用户直接加入 Tenant。
- `ChangeMemberRole`：在 OWNER/MEMBER 间调整角色。
- `RemoveMember`：移除成员，同时保护最后一名 OWNER。
- `GetMembership` / `ListMemberships`：返回 Tenant Membership 的安全投影。

`TenantAction` 是 Protobuf enum，不接受自由字符串。涉及删除 Document 时，受信任 Console 可同时传入资源上传者 UID，Access 据此区分删除自己的资源与删除他人的资源。

同一用户被移除后再次加入同一 Tenant 时，Access 重新激活原 Membership 并将角色设为 MEMBER，不创建第二行关系。

### 5.3 ApiKeyService

- `RegisterScope`：注册 KnowledgeBase 的最小授权投影。
- `BlockScope`：将 Scope 终态阻塞并原子禁用其全部有效 Key。
- `CreateApiKey`、`DisableApiKey`、`EnableApiKey`、`RotateApiKey`、`RevokeApiKey`。
- `AuthenticateApiKey`：校验完整 Key，成功只返回 `apiKeyId`、`tenantId`、`knowledgeBaseId` 和过期时间。

调用方权限按现有 gRPC Service Identity 收紧：Console 可以调用身份、Session、Membership、Scope 和 Key 管理 RPC；Open API 只可调用 API Key 鉴权与 JWT 公钥读取；其他服务默认无权调用 Access 业务 RPC。Smoke 使用独立受控测试入口，不放宽正式配置。

## 6. 数据模型

### 6.1 platform_user

- `user_id BIGINT`：Snowflake UID，永久用户标识。
- `nickname`：展示名称，可重复、可修改，不参与登录。
- `status`：`ACTIVE / DISABLED`。
- `created_at`、`updated_at`、`version`。

### 6.2 login_account

- `account_id BIGINT`、`user_id BIGINT`。
- `account_type`：首期只允许 `USERNAME`。
- `login_identifier` 与 `normalized_identifier`：当类型为 USERNAME 时分别表示原始 Username 与规范化 Username。
- `credential_hash`：Argon2id 密码哈希。
- `status`、`created_at`、`updated_at`、`version`。

`(account_type, normalized_identifier)` 全局唯一。首期 Username 规范化为小写后全局唯一且不支持修改。Account 类型边界服务于已确认的多账号方向，但 plan20 不创建任何非 USERNAME 流程。

### 6.3 tenant

- `tenant_id BIGINT`、展示名称、`status`。
- `created_at`、`updated_at`、`version`。

### 6.4 tenant_membership

- `membership_id BIGINT`、`tenant_id BIGINT`、`user_id BIGINT`。
- `role`：`OWNER / MEMBER`。
- `status`：`ACTIVE / REMOVED`。
- `created_at`、`updated_at`、`version`。

`(tenant_id, user_id)` 唯一。添加、改角色和移除成员必须在同一事务中锁定 Tenant 及有效 OWNER 集合，提交后至少保留一名有效 OWNER。

### 6.5 refresh_session

每次 Refresh Token 签发保存一行：

- `session_id BIGINT`、`family_id BIGINT`、`user_id BIGINT`。
- Token HMAC、状态、签发时间、过期时间、轮换时间、撤销时间和替代 Session ID。
- `created_at`、`updated_at`、`version`。

状态至少包含 `ACTIVE / ROTATED / REVOKED / EXPIRED`。JWT 的 `sid` 指向 Session Family，而不是某一次 Token。旧 Token 被轮换后再次使用视为复用攻击，并原子撤销整个 Family。Refresh Token 使用 32 字节随机秘密的 Base64URL 无填充编码，数据库按唯一 HMAC 定位，不保存原文。

### 6.6 api_key_scope

- `knowledge_base_id BIGINT`：唯一授权投影标识。
- `tenant_id BIGINT`。
- `status`：`ACTIVE / BLOCKED`。
- `created_at`、`updated_at`、`version`。

该表不保存 KnowledgeBase 名称或其他 Knowledge 业务字段，也不建立跨 Schema 外键。`BLOCKED` 在 plan20 中是终态，删除误操作的恢复必须由后续生命周期计划显式设计，Access 不提供通用 Unblock RPC。

### 6.7 api_key

- `api_key_id BIGINT`、`tenant_id BIGINT`、`knowledge_base_id BIGINT`。
- Key 名称、唯一可检索前缀、秘密 HMAC、状态和创建者 UID。
- 创建、最后使用、过期、禁用、吊销时间及轮换前后关系。
- `created_at`、`updated_at`、`version`。

状态至少包含 `ACTIVE / DISABLED / REVOKED / EXPIRED`。`DISABLED` 可恢复，`REVOKED` 不可恢复。完整 Key 使用 `crag_<12字符随机前缀>_<32字节随机秘密的Base64URL无填充编码>`，永不落库，只在创建或轮换成功时返回一次。

### 6.8 outbox_event

复用 `crag-event` 本地 Outbox 表结构、状态机和版本 CAS，不另造事件表语义。Access Schema 内部外键只能指向 Access 表。

## 7. 身份、会话与授权规则

### 7.1 输入规则

- Username：去除首尾空白并转为小写，长度 3–32，只允许字母、数字、点、下划线和短横线。
- Nickname：去除首尾空白，长度 1–64 个 Unicode 字符。
- 密码：长度 12–128，不要求人为组合大小写或特殊字符。
- API Key 名称：长度 1–64。
- API Key 必须过期，默认 90 天，最长 365 天。

所有时间判断使用注入的 `Clock`，所有 Token/Key 秘密使用 `SecureRandom`。

### 7.2 注册与登录

注册在一个 Access 本地事务中创建 User、Account、默认 Tenant、OWNER Membership 和 Refresh Session。任一步失败则整体回滚。登录按规范化 Username 定位 Account，使用 Argon2id 校验密码，并在 User 与 Account 均为 ACTIVE 时创建新 Session Family。

登录失败统一为“凭据无效”，不得区分 Username 不存在、密码错误、账号禁用或用户禁用。

### 7.3 JWT

Access JWT 使用 RS256，RSA 密钥至少 2048 位，默认有效期 15 分钟。私钥仅由 Access 持有，配置包含 `kid`、issuer 和 audience。Access 使用一个当前私钥签发，同时可配置只验签的旧公钥；验证公钥集通过 gRPC 提供给 router4 本地缓存。plan20 不提供在线生成或轮换密钥的管理 RPC。

JWT 只承载 `sub/userId`、`sid`、`jti`、`iss`、`aud`、`iat`、`nbf` 和 `exp` 等身份/会话声明，不包含 `tenantId`、Membership 或角色。Tenant 权限和敏感操作始终由 Access 实时校验。

### 7.4 Refresh Session

Refresh Token 默认有效期 30 天，使用独立 Pepper 的 HMAC-SHA-256 保存。刷新事务锁定 Token 与 Session Family：

1. ACTIVE Token 原子变为 ROTATED。
2. 同事务创建替代 Token 并签发新 JWT。
3. 过期或已撤销 Token 直接拒绝。
4. ROTATED Token 再次出现时撤销整个 Family。
5. 用户或 Account 被禁用时禁止刷新。

并发刷新只允许一次成功；第二次提交按复用检测处理。Logout 撤销当前 Family。Cookie 写入、清除和 CSRF 归 router4。

### 7.5 Membership 权限

首版权限矩阵沿用总体设计：

| 操作 | OWNER | MEMBER |
| --- | --- | --- |
| 管理成员 | 允许 | 禁止 |
| 创建/查看 KnowledgeBase | 允许 | 允许 |
| 上传 Document | 允许 | 允许 |
| 删除自己上传的 Document | 允许 | 允许 |
| 删除其他用户上传的 Document | 允许 | 禁止 |
| 删除 KnowledgeBase | 允许 | 禁止 |
| 管理 API Key | 允许 | 禁止 |

Owner 通过 Username 添加已注册用户。首期不创建邀请或待接受状态；未来邀请使用限时、单次消费的邀请 Token，不依赖发送 Email。

## 8. API Key 与 Scope 流程

### 8.1 创建与鉴权

Console 在 KnowledgeBase 创建成功后调用 Access 注册 Scope。只有 Scope ACTIVE 且操作者是对应 Tenant OWNER 时才能创建 API Key。

完整 Key 由可检索前缀和高熵秘密组成。Access 先按前缀定位记录，再以 API Key 专用 Pepper 计算 HMAC-SHA-256 并恒定时间比较。鉴权同时检查 Key、Scope、Tenant 状态和到期时间。日志只允许记录前缀，不记录完整 Key。

### 8.2 状态变化

- `ACTIVE → DISABLED → ACTIVE`：临时停用与恢复。
- `ACTIVE / DISABLED → REVOKED`：终态吊销。
- 轮换：同一事务创建新 Key、吊销旧 Key，并只返回一次新秘密。
- Scope Block：同一事务终态阻塞 Scope、禁用其全部有效 Key并写失效 Outbox。

停用、启用、轮换、吊销或 Scope Block 都必须在状态事务中写入 `API_KEY_INVALIDATED` Outbox。router3 只负责生产事件，Open API 消费和短 TTL 鉴权缓存归 router4。

## 9. 安全、错误与并发

### 9.1 密钥

- 密码：Argon2id；默认参数为 64 MiB 内存、3 次迭代、并行度 1、16 字节随机 Salt 和 32 字节输出，参数可通过受控配置提高但不得低于该基线。
- Refresh Token：独立 Pepper 的 HMAC-SHA-256。
- API Key：另一份独立 Pepper 的 HMAC-SHA-256。
- JWT：RS256 私钥签名，公钥验签。

正式 Profile 缺少 JWT 私钥、公钥或任一 Pepper 时 readiness 失败，禁止回退到 Demo 默认值。Smoke Profile 使用独立测试 Secret。

### 9.2 错误语义

- 登录、Refresh Token 或 API Key 鉴权失败统一使用 `UNAUTHENTICATED`，不泄漏具体原因。
- Username 冲突使用 `ALREADY_EXISTS`。
- 最后一名 OWNER、Scope Block、非法状态转换使用带稳定业务详情的 `FAILED_PRECONDITION`。
- 权限不足或其他 Tenant 资源不存在时，对外采用不泄漏资源存在性的稳定语义。
- SQL、堆栈、哈希、密码、完整 Token 和完整 API Key 不得进入 gRPC 错误或日志。

### 9.3 并发与事务

- Username 唯一性与 Key Prefix 唯一性由数据库唯一约束兜底；Prefix 冲突只做有限次数重新生成。
- Refresh 通过行锁与状态条件保证单 Token 单次成功轮换。
- Membership 修改锁定 Tenant 与有效 OWNER 集合，保护最后一名 OWNER。
- API Key 轮换、Scope Block、Key 状态变化和失效 Outbox 在本地事务中提交。
- 自定义更新遵守版本 CAS；冲突转换为明确异常，不盲目重试。

## 10. Schema 初始化决策

总体设计早期写有“各服务首次业务表阶段落地版本化迁移机制”，但已经验收的 router1/plan18 和 router2/plan19 实际采用幂等 `schema-knowledge.sql` 与 `schema.sql`，仓库当前没有 Flyway/Liquibase 基线。

plan20 沿用已验收的统一启动方式，使用幂等 `schema-access.sql`，不让 Access 独自形成第三种数据库启动机制。正式创建 plan20 时，应按 `constraints/plan-workflow.md` 为这项方向校准创建决策归档，并同步修正 `plan_main.md` 与总体设计中的过期迁移表述。未来若引入 Flyway/Liquibase，必须通过独立治理 Plan 同时覆盖 Access、Knowledge 与 RAG，而不是只迁移一个服务。

## 11. 可观测性

HTTP、gRPC 与事件继续传播 traceId。至少提供以下指标或可聚合日志事实：

- 注册、登录成功/失败、刷新成功/失败、Refresh 复用检测。
- Membership 权限拒绝和最后 OWNER 保护。
- API Key 鉴权成功/失败、状态变更、Scope Block 和失效事件发布。
- gRPC 调用延迟、错误率和超时。
- Outbox 待发布数量、最老事件年龄、失败与 DEAD 数量。

业务日志可以携带 UID、Tenant ID、Key ID/Prefix、Session Family ID 和 traceId，禁止记录密码、私钥、Pepper、完整 Token 或完整 API Key。

## 12. 测试策略

### 12.1 纯单元测试

- Username/Nickname/密码与 API Key 有效期规则。
- OWNER/MEMBER 权限矩阵和最后 OWNER 规则。
- JWT claims、签名、篡改、issuer/audience 和过期判断。
- Refresh Session 轮换、过期、撤销和复用检测状态机。
- API Key HMAC、状态、过期、轮换与 Scope Block。
- 稳定错误分类和安全消息。

### 12.2 轻量组件测试

- Spring Context + H2 下的 DAO 映射、唯一约束和事务回滚。
- 注册事务同时创建 User、Account、Tenant、OWNER Membership 与 Session。
- Membership 修改和最后 OWNER 保护。
- Refresh Token 轮换及 Session Family 撤销。
- Scope/Key 状态与 `API_KEY_INVALIDATED` Outbox 同事务写入。
- gRPC Provider 映射、Service Identity 限制与稳定错误详情。

H2 组件测试不宣称覆盖 PostgreSQL 锁语义。

### 12.3 架构测试

- `crag-access-contracts` 不依赖 Spring、runtime 或 Service module。
- Repository 只允许 DAO 调用；Entity 不越过持久化边界。
- `core` 不依赖 gRPC、Controller、Producer 或其他 Service module。
- Smoke Controller 受 `smoke` Profile 限制，默认 Profile 不可达。
- 模块依赖、公开 API 和包结构符合现行约束。

### 12.4 Docker HTTP 回归

使用真实 PostgreSQL、Redis 和 Access 进程，通过 smoke-only HTTP 验证：

1. 注册、默认 Tenant 和 OWNER Membership。
2. 登录、JWT claims、刷新轮换、旧 Token 复用撤销 Family。
3. Owner 添加成员、调整角色、移除成员与最后 OWNER 保护。
4. Scope 注册、API Key 创建、鉴权、停用、启用、轮换、吊销和过期。
5. Scope Block 与失效 Outbox/Redis Streams 发布。
6. 并发刷新只有一次成功，后续按复用检测撤销 Family。
7. 默认 Profile 不暴露 Access smoke HTTP。

Docker 脚本使用唯一 runId/Username，不清空共享表或 Volume。

## 13. plan20 任务切片建议

1. 建立 `crag-access-contracts`、模块依赖和包边界护栏。
2. 建立 Access Schema、Entity、DAO 和安全配置基线。
3. 实现 User/Account 注册、密码认证和默认 Tenant。
4. 实现 Membership 生命周期与权限矩阵。
5. 实现 RS256 JWT 与 Refresh Session 轮换/复用检测。
6. 实现 `api_key_scope`、API Key 生命周期和鉴权。
7. 接入 `API_KEY_INVALIDATED` Outbox Producer。
8. 完成 gRPC Provider、smoke-only HTTP 与 Docker 回归。
9. 同步迁移方向决策、约束、README、静态校验器并执行全量验证。

第 4、5 项都依赖第 3 项，逻辑上可并行，但会共享 Schema 与核心配置；默认串行执行以降低合并风险。其余任务按顺序推进。

## 14. 风险与回滚

- 风险：Access 范围扩张到正式 HTTP 或生命周期补偿。通过模块依赖、文件边界和非目标限制在 Provider。
- 风险：Refresh 并发产生多个有效 Token。通过行锁、状态条件和 Docker 并发回归验证。
- 风险：最后 OWNER 检查发生竞态。通过 Tenant/OWNER 锁定和真实 PostgreSQL 验收降低风险。
- 风险：秘密进入日志、错误或数据库明文。通过安全适配器、日志审查、负向测试和只返回一次语义控制。
- 风险：API Key 状态已变更但缓存未失效。状态事务内写 Outbox；router4 继续使用短 TTL 作为最终失效上限。
- 风险：Access 单独引入迁移框架造成服务间启动漂移。plan20 沿用幂等 Schema，迁移框架另行统一治理。
- 回滚：plan20 不迁移旧数据或修改其他 Schema。可以按任务提交逆序撤销 contracts、Access 表、Core、gRPC、Producer、smoke 和配置。若本地 Access Schema 已产生测试数据，可删除并由幂等 Schema 重建；Knowledge/RAG 数据不受影响。

## 15. 成功标准

- Access 能在独立 Schema 中完整执行注册、登录、刷新、Membership 和 API Key 流程。
- JWT 不携带 Tenant/角色，Console 可获取公钥并在本地验签。
- Refresh Token 轮换、并发和复用检测具有可重复验证的行为。
- Tenant 不能失去最后一名有效 OWNER。
- API Key 只绑定一个 KnowledgeBase，完整 Key 只返回一次，状态变化可靠发布失效事件。
- 正式 Profile 只暴露 gRPC，smoke HTTP 默认禁用。
- 单元、组件、架构、Docker 回归和 Plan 严格校验全部通过，无跳过的必需项。
