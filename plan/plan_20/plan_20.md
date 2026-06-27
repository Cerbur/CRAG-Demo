---
workflow_version: 3
plan_id: plan_20
type: main
status: verifying
created: 2026-06-28
updated: 2026-06-28
---

# plan_20 — Access 垂直链路

> **For agentic workers:** 执行本计划必须先读取 `skill/execute-crag-plan/SKILL.md`；实现步骤使用测试先行、任务级提交和独立验收交接，不得跳过项目 Plan session 流程。

**Goal**：完整落地 router3 的 Access Provider，支持 User/Account、默认 Tenant、Membership、身份型 JWT、Refresh Session、API Key、`api_key_scope`、失效事件和 smoke-only Docker 验收。

**Architecture**：新增 `crag-access-contracts` 保存 Access 领域 gRPC 契约；`crag-access-service` 作为单一部署单元，内部按 `identity / membership / session / apikey` 领域切片组织 Core，并通过统一 DAO、Security Adapter、gRPC Provider 和 Producer 暴露能力。正式 Console/Open HTTP、缓存消费者和删除补偿留给 router4/router5。

**Tech Stack**：Java 21、Spring Boot 4.1.0、Spring Framework 7、Gradle 9.4.1、gRPC + Protobuf、Spring Data JPA/JDBC、Spring Security Crypto/OAuth2 JOSE、Nimbus JOSE JWT、PostgreSQL 17、Redis、Docker Compose、`crag-id`、`crag-event`。

## 全局实现约束

- 设计事实来源：`docs/superpowers/specs/2026-06-28-access-vertical-slice-design.md`，设计提交 `ac97f1e`。
- 新增 `crag-access-contracts`；Access 领域 proto 不得放入 `crag-platform-contracts`。
- `crag-access-contracts` 禁止依赖 Spring、runtime 或任何 Service module。
- `crag-access-service` 可以依赖 `crag-access-contracts`、`crag-platform-contracts`、`crag-grpc-runtime`、`crag-common`、`crag-event` 和 `crag-id`，禁止依赖 Knowledge、RAG、Console 或 Open Service module。
- `AccessServiceApplication` 移到 `ai.cerbur.crag.access` 根包；不保留 `app` 包。
- Core 按 `identity / membership / session / apikey` 组织；DAO 是数据库访问唯一边界，Repository 只能由 DAO 调用，Entity 不得跨持久化边界。
- 所有 Access 本地实体 ID 使用 `CragIdGenerator` 生成 Snowflake `long`；gRPC 边界使用十进制字符串。
- 所有业务表包含 `created_at`、`updated_at`、`version`；自定义状态更新遵守版本 CAS。
- Username 规范化为 ASCII 小写，长度 3–32，只允许字母、数字、点、下划线和短横线；全局唯一且 plan20 不支持修改。
- Nickname 去除首尾空白，长度 1–64 个 Unicode 字符，可重复、可修改。
- 密码长度 12–128，使用 Argon2id：64 MiB、3 次迭代、并行度 1、16 字节 Salt、32 字节输出。
- Access JWT 使用至少 2048 位 RSA 的 RS256，有效期 15 分钟，只包含身份和 Session Family，不包含 Tenant 或角色。
- Refresh Token 使用 32 字节随机秘密、Base64URL 无填充编码、独立 Pepper 的 HMAC-SHA-256，有效期 30 天；每次刷新轮换，旧 Token 复用撤销整个 Family。
- API Key 使用 `crag_<12字符随机前缀>_<32字节随机秘密Base64URL无填充>`，独立 Pepper 的 HMAC-SHA-256；默认 90 天，最长 365 天，禁止永不过期。
- `api_key_scope.BLOCKED` 是终态；Scope Block 同事务禁用全部有效 Key 并写失效 Outbox。
- 私钥、Pepper、密码、完整 JWT/Refresh Token/API Key 禁止写入数据库明文字段、日志或错误响应。
- 正式 Profile 不暴露 Access HTTP 业务入口；`controller.smoke` 必须受 `@Profile("smoke")` 限制。
- Java 遵守 `constraints/code-style.md`；持久化遵守 `constraints/persistence-style.md`；HTTP smoke 遵守 `constraints/api-style.md`；测试遵守 `constraints/test-workflow.md`。

## 背景与目标

`plan_18` 已交付 Knowledge 垂直链路，`plan_19` 已交付 RAG 多知识库隔离与异步索引。当前 `crag-access-service` 只有服务骨架、Platform Probe 和 Access Schema readiness，没有领域 contracts、业务表、用户认证、租户权限、会话或 API Key 能力。

plan20 将 router3 收敛为完整但边界清楚的 Access Provider：注册和登录、默认 Tenant、OWNER/MEMBER Membership、身份型 JWT、可撤销 Refresh Session、KnowledgeBase 授权投影、单 KnowledgeBase API Key、失效事件生产端、gRPC Provider 和 smoke-only 验收入口。执行完成后，router4 可以只负责编排正式 HTTP、Cookie/JWT 本地验签、API Key 缓存与 RAG 调用，而不重复 Access 规则。

## 范围

- 新增 `crag-access-contracts` 与 Identity、Membership、API Key 三组 gRPC 契约。
- 扩展 `crag-id` Access 实体类型，并让 Access Service 使用 Redis Worker 租约发号。
- 建立幂等 `schema-access.sql`、Access Entity、Repository、DAO 与事务边界。
- 实现 `platform_user`、`login_account`、`tenant`、`tenant_membership`、`refresh_session`、`api_key_scope`、`api_key` 和 `outbox_event`。
- 实现 Username/密码注册登录、默认 Tenant、OWNER Membership 和安全错误语义。
- 实现 Membership 添加、重新激活、角色调整、移除、实时权限矩阵和最后 OWNER 保护。
- 实现 RS256 JWT、公钥集、Refresh Session 轮换、并发控制、复用检测与 Logout。
- 实现 Scope 注册/终态 Block、API Key 创建/鉴权/停用/启用/轮换/吊销/过期。
- 实现 `API_KEY_INVALIDATED` Outbox Producer 与 Redis Streams 发布接入。
- 实现按调用方收紧的 gRPC Provider、Access Metrics、smoke-only HTTP 和 Docker 回归。
- 同步 README、包结构、Docker、测试约束、依赖校验器和当前事实索引。

## 非目标

- 不实现正式 Console/Open HTTP API、Cookie、CSRF、浏览器传输或跨服务管理用例编排。
- 不实现 Open API 的 API Key 缓存和 `API_KEY_INVALIDATED` Consumer。
- 不实现邀请 Token、邀请邮件、待接受 Membership 或跨租户用户搜索。
- 不实现 Email、OAuth、手机号登录、改 Username、改密码、密码找回、退出全部设备、MFA、验证码、设备指纹或登录限流。
- 不实现 User/Account 禁用管理 RPC；状态字段只供认证守卫和后续管理能力使用。
- 不实现 Scope Unblock、删除状态机、删除补偿、Reconciler、告警平台或运维重放界面。
- 不迁移旧 Demo 数据，不修改 Knowledge/RAG Schema，不拆分新的 Access 服务进程。
- 不为单个服务引入 Flyway/Liquibase；版本化迁移框架必须由未来独立治理 Plan 同时覆盖三个业务 Schema。

## 前置依赖

- **执行前置 Plan**：`plan_19`
- `plan_14` 已建立 Access Service 骨架、Access Schema/账号和 gRPC Service Identity。
- `plan_15` 已提供 `crag-id` Snowflake 与 Redis Worker 租约。
- `plan_17` 已提供本地 Outbox、Redis Streams Publisher 和事件指标。
- `plan_19` 已完成并通过独立验收，router3 可以进入执行队首。
- 设计规格已提交并由用户复核：`ac97f1e`。

## 文件边界

- `settings.gradle.kts`
- `gradle/libs.versions.toml`
- `crag-access-contracts/**`
- `crag-id/src/main/java/ai/cerbur/crag/id/api/IdEntityType.java`
- `crag-id/src/test/**`
- `crag-access-service/build.gradle.kts`
- `crag-access-service/src/main/java/ai/cerbur/crag/access/**`
- `crag-access-service/src/main/resources/**`
- `crag-access-service/src/test/**`
- `docker-compose.yml`
- `docker/java-service.Dockerfile`
- `scripts/validate_module_dependencies.py`
- `scripts/tests/test_validate_module_dependencies.py`
- `scripts/validate_framework_dependencies.py`
- `scripts/tests/test_validate_framework_dependencies.py`
- `scripts/validate_constraints.py`
- `scripts/tests/test_validate_constraints.py`
- `scripts/tests/http/access_smoke_*.sh`
- `constraints/package-structure.md`
- `constraints/docker-structure.md`
- `constraints/test-workflow.md`
- `README.md`
- `plan/plan_20/plan_20.md`
- `plan/index/README.md`

## 实现文件地图

### Contracts 与边界

- `crag-access-contracts/build.gradle.kts`：纯 Protobuf/gRPC contracts module。
- `crag-access-contracts/src/main/proto/crag/access/v1/identity_service.proto`：注册、登录、刷新、退出和 JWT 公钥。
- `crag-access-contracts/src/main/proto/crag/access/v1/membership_service.proto`：Membership 管理、权限动作和安全投影。
- `crag-access-contracts/src/main/proto/crag/access/v1/api_key_service.proto`：Scope、API Key 生命周期和鉴权。
- `crag-access-contracts/src/main/proto/crag/access/v1/access_error.proto`：稳定业务错误详情枚举与消息。

### Access Core 与持久化

- `ai.cerbur.crag.access.AccessServiceApplication`：组合根，导入 gRPC、ID 与事件配置。
- `core.identity/**`：Username/Nickname/密码规则、注册、凭据认证、User/Account 结果。
- `core.membership/**`：Tenant 注册结果、Membership 生命周期、`TenantAction` 权限矩阵。
- `core.session/**`：Authentication Facade、JWT、Refresh Session、轮换与复用检测。
- `core.apikey/**`：Scope、API Key 生命周期、鉴权与状态机。
- `dao/*Dao.java`：User、Account、Tenant、Membership、Refresh Session、Scope、API Key 数据访问边界。
- `dao/entity/**`：持久化 Entity 和 enum converter。
- `dao/repository/**`：Spring Data Repository，仅 DAO 调用。
- `security/**`：`PasswordHasher`、`SecretHmac`、`SecretGenerator`、`JwtIssuer`、`JwtVerificationKeySet` 与受控配置。

### 协议、事件与验收

- `grpc/provider/**`：三组 gRPC Provider。
- `grpc/mapper/**`：Core result 与 proto 映射。
- `grpc/error/**`：稳定 gRPC Status 与 Access error detail。
- `grpc/security/AccessRpcAuthorizer.java`：按 `GrpcCallerContext` 限制 Console/Open RPC。
- `producer/**`：`API_KEY_INVALIDATED` payload、事件常量和 Outbox Writer。
- `metrics/AccessMetrics.java`：认证、权限、Session 复用和 API Key 计数。
- `controller/smoke/**`：`/api/v1/smoke/access/**` 验收入口、DTO 和异常映射。
- `schema-access.sql`：Access 业务表、约束、索引和事件表。
- `application.yml` / `application-smoke.yml`：正式安全配置与 smoke 测试配置。
- `scripts/tests/http/access_smoke_*.sh`：真实 PostgreSQL/Redis/Docker 回归。

## 关键决策

- plan20 对应 `router3`；早期总体设计中预留的旧编号不再作为执行事实。
- 使用领域切片式单服务，不拆分 Access 子模块或额外进程。
- User 与 Login Account 分离：UID 是永久身份，Nickname 是展示名，Username 是不可变登录标识。
- 首期 Account 类型只有 `USERNAME`；Email/OAuth 只保留表结构扩展边界，不实现流程。
- JWT 是身份型 Token，不携带 Tenant 或角色；Tenant 权限由 Access 实时判断。
- Refresh Token 单次使用并轮换；ROTATED Token 复用撤销整个 Session Family。
- Owner 通过 Username 添加已注册用户；移除后重新添加会激活原 Membership 并设为 MEMBER。
- Membership 更新锁定 Tenant 与有效 OWNER 集合，Tenant 永远保留至少一名有效 OWNER。
- API Key 单 Key 绑定单 KnowledgeBase；完整 Key 只在创建/轮换成功时返回一次。
- Scope Block 是终态，禁用全部有效 Key；失效事件由 Access 生产，缓存消费归 router4。
- Access 延续已验收的幂等 Schema 初始化方式；迁移框架未来统一治理。

## 未决问题

无。

## 风险与回滚

- 风险：认证、Membership、Session 和 API Key 互相缠绕。预防措施是四个 Core 切片、窄结果类型和架构测试；跨切片编排只放在 Authentication/API Key Facade。
- 风险：Refresh 并发产生多个有效 Token。预防措施是行锁、状态条件、唯一 HMAC 和 Docker 并发回归；任何疑似 flaky 必须按测试约束定位。
- 风险：最后 OWNER 检查竞态。预防措施是事务锁定 Tenant 与有效 OWNER 集合，并使用真实 PostgreSQL 验收。
- 风险：Service Identity 只认证调用方却未限制 RPC。预防措施是 Provider 入口调用 `AccessRpcAuthorizer`，并测试 Console/Open 交叉拒绝。
- 风险：秘密进入日志、数据库或错误。预防措施是安全适配器统一处理、负向测试和 diff 审查。
- 风险：Key 状态已变更但缓存失效延迟。预防措施是同事务 Outbox；router4 仍以短 TTL 限制旧缓存窗口。
- 风险：Access 新增 ID 类型影响全局位布局。预防措施是固定未占用 code 3–9，并用 `IdEntityTypeTest` 锁定编码，不修改既有 code 1–2。
- 回滚：按 20.9→20.1 逆序 revert 任务提交，删除 Access contracts、Access 业务表、Core、Provider、Producer、smoke 服务和新增 ID 类型。plan20 不修改 Knowledge/RAG Schema；本地 Access 测试数据可删除 Access Schema 后由 `schema-access.sql` 重建。若外部消费者已开始使用 contracts，必须先回滚消费者再删除 contracts。

## 测试与验证计划

- 纯单元测试：`./gradlew :crag-id:test :crag-access-service:test --tests '*PolicyTest' --tests '*NormalizerTest' --tests '*JwtIssuerTest' --tests '*RefreshSessionServiceTest' --tests '*ApiKeyServiceTest' --tests '*AccessErrorMapperTest'`，覆盖确定性规则、状态机、密码/JWT/HMAC 和错误分类。
- 轻量组件测试：`./gradlew :crag-access-service:test --tests '*DaoComponentTest' --tests '*RegistrationComponentTest' --tests '*MembershipComponentTest' --tests '*SessionComponentTest' --tests '*ApiKeyComponentTest' --tests '*GrpcProviderComponentTest' --tests '*SmokeControllerComponentTest'`，使用 Spring Context/H2 验证映射、事务、Provider 与 Profile；不把 H2 结果表述为 PostgreSQL 锁保证。
- 架构测试：`./gradlew test --tests '*AccessArchitectureTest' --tests '*ModuleDependencyArchitectureTest'`，验证 contracts、DAO/Repository、Core、Provider、Service module 和 smoke Profile 边界。
- Docker HTTP 回归：`docker compose --profile smoke up -d --build db redis access-service-smoke` 后依次执行 `scripts/tests/http/access_smoke_default_disabled_test.sh`、`access_smoke_identity_test.sh`、`access_smoke_membership_test.sh`、`access_smoke_session_reuse_test.sh`、`access_smoke_api_key_test.sh`、`access_smoke_event_test.sh`、`access_smoke_concurrent_refresh_test.sh`；必须使用唯一 runId/Username，不清表、不删除 Volume。
- 全量验证：`./gradlew spotlessCheck test check`、`python3 scripts/validate_plans.py --strict --verify-git`、`python3 scripts/validate_module_dependencies.py`、`python3 scripts/validate_framework_dependencies.py`、`python3 scripts/validate_constraints.py`。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 20.1 | 建立 Access contracts 与模块边界 | ⏳ 待验收 | 421531b0 | — |
| 20.2 | 建立 Access Schema、DAO、ID 与安全基线 | ⏳ 待验收 | 042aef76 | — |
| 20.3 | 实现 User/Account 注册、密码认证与默认 Tenant | ⏳ 待验收 | fe39b8da | — |
| 20.4 | 实现 Membership 生命周期与权限矩阵 | ⏳ 待验收 | 966d3590 | — |
| 20.5 | 实现 JWT 与 Refresh Session | ⏳ 待验收 | 7192fd97 | — |
| 20.6 | 实现 Scope 与 API Key 生命周期 | ⏳ 待验收 | 21ad2ffc | — |
| 20.7 | 接入 API Key 失效 Outbox Producer | ⏳ 待验收 | a9dbb90c | — |
| 20.8 | 完成 gRPC、Metrics、smoke HTTP 与 Docker 回归 | ⏳ 待验收 | 94113073, 0d3ebcfa | — |
| 20.9 | 同步约束、README 与全量验证 | ⏳ 待验收 | 1340bc4b | — |

整体进度：0 / 9（0%）

## 20.1 建立 Access contracts 与模块边界

**目标**：建立无 Spring 依赖的 Access gRPC 契约和可机械验证的模块边界。  
**前置任务**：无  
**范围**：新增 contracts module、四个 proto、Gradle/settings/Docker 构建复制、模块和框架依赖校验器测试。所有 ID 字段使用十进制字符串，时间使用 epoch millis，错误详情使用稳定 enum。  
**非目标**：不实现 Provider、业务 Service、数据库或 HTTP。  
**验收标准**：生成 `IdentityServiceGrpc`、`MembershipServiceGrpc`、`ApiKeyServiceGrpc`；contracts 不依赖 Spring/runtime/service；现有模块依赖校验通过。  
**验证方式**：`./gradlew :crag-access-contracts:build`、`python3 scripts/validate_module_dependencies.py`、`python3 scripts/validate_framework_dependencies.py`。  
**涉及文件**：`settings.gradle.kts`、`crag-access-contracts/**`、`docker/java-service.Dockerfile`、`scripts/validate_module_dependencies.py`、`scripts/tests/test_validate_module_dependencies.py`、`scripts/validate_framework_dependencies.py`、`scripts/tests/test_validate_framework_dependencies.py`

**Interfaces**：

```proto
service IdentityService {
  rpc Register(RegisterRequest) returns (AuthenticationResponse);
  rpc Login(LoginRequest) returns (AuthenticationResponse);
  rpc Refresh(RefreshRequest) returns (AuthenticationResponse);
  rpc Logout(LogoutRequest) returns (LogoutResponse);
  rpc GetJwtVerificationKeys(GetJwtVerificationKeysRequest) returns (JwtVerificationKeySet);
}

service MembershipService {
  rpc AuthorizeTenantAction(AuthorizeTenantActionRequest) returns (AuthorizationDecision);
  rpc AddMemberByUsername(AddMemberByUsernameRequest) returns (Membership);
  rpc ChangeMemberRole(ChangeMemberRoleRequest) returns (Membership);
  rpc RemoveMember(RemoveMemberRequest) returns (Membership);
  rpc GetMembership(GetMembershipRequest) returns (Membership);
  rpc ListMemberships(ListMembershipsRequest) returns (ListMembershipsResponse);
}

service ApiKeyService {
  rpc RegisterScope(RegisterScopeRequest) returns (ApiKeyScope);
  rpc BlockScope(BlockScopeRequest) returns (ApiKeyScope);
  rpc CreateApiKey(CreateApiKeyRequest) returns (CreatedApiKey);
  rpc DisableApiKey(ChangeApiKeyStateRequest) returns (ApiKeyView);
  rpc EnableApiKey(ChangeApiKeyStateRequest) returns (ApiKeyView);
  rpc RotateApiKey(RotateApiKeyRequest) returns (CreatedApiKey);
  rpc RevokeApiKey(ChangeApiKeyStateRequest) returns (ApiKeyView);
  rpc AuthenticateApiKey(AuthenticateApiKeyRequest) returns (AuthenticatedApiKey);
}
```

- [ ] 先在校验器测试中登记 `crag-access-contracts`，运行测试并确认因 module 缺失而失败。
- [ ] 创建 module 与 proto；错误详情 enum 固定包含 `INVALID_CREDENTIALS`、`INVALID_REFRESH_TOKEN`、`USERNAME_CONFLICT`、`MEMBERSHIP_NOT_FOUND`、`LAST_OWNER`、`SCOPE_BLOCKED`、`API_KEY_INVALID`、`STATE_CONFLICT`。
- [ ] 运行 contracts build、生成代码检查和依赖校验，确认全部通过。
- [ ] 提交：`feat(plan_20/20.1): add access contracts and boundaries`。

## 20.2 建立 Access Schema、DAO、ID 与安全基线

**目标**：建立所有 Access 本地数据结构、Snowflake ID、数据库访问边界和受控密码/密钥配置。  
**前置任务**：20.1  
**范围**：新增 ID type code 3–9；Access 接入 `crag-id`、Redis、JPA、Validation、Security Crypto/OAuth2 JOSE、`crag-event`；移动 Application 根包；新增幂等 Schema、Entity、Repository、DAO、配置属性、Secret Generator/HMAC/Password Hasher。  
**非目标**：不实现注册、权限、Session 或 API Key 用例。  
**验收标准**：七类本地 ID 保持固定编码；七张业务表和 Outbox 可在 H2/PostgreSQL 初始化；所有表含审计字段与 version；Repository 仅 DAO 调用；正式配置缺失秘密时 readiness 为 DOWN。  
**验证方式**：`./gradlew :crag-id:test :crag-access-service:test --tests '*AccessDaoComponentTest' --tests '*AccessSecurityConfigurationTest' --tests '*AccessArchitectureTest'`。  
**涉及文件**：`gradle/libs.versions.toml`、`crag-id/src/main/java/ai/cerbur/crag/id/api/IdEntityType.java`、`crag-id/src/test/**`、`crag-access-service/build.gradle.kts`、`crag-access-service/src/main/java/ai/cerbur/crag/access/AccessServiceApplication.java`、`crag-access-service/src/main/java/ai/cerbur/crag/access/dao/**`、`crag-access-service/src/main/java/ai/cerbur/crag/access/security/**`、`crag-access-service/src/main/resources/schema-access.sql`、`crag-access-service/src/main/resources/application.yml`、`crag-access-service/src/test/**`

**Interfaces**：

```java
public enum IdEntityType {
  LEGACY_DOCUMENT(1), CHUNK(2), USER(3), LOGIN_ACCOUNT(4), TENANT(5),
  TENANT_MEMBERSHIP(6), REFRESH_SESSION(7), API_KEY(8), ACCESS_EVENT(9)
}

public interface PasswordHasher {
  String hash(char[] password);
  boolean matches(char[] password, String encodedHash);
}

public interface SecretHmac {
  byte[] digest(String secret);
  boolean matches(String secret, byte[] expectedDigest);
}

public interface SecretGenerator {
  String randomBase64Url(int byteCount);
}
```

DAO 必须提供：按规范化 Username 查询 Account；按 Tenant/User 查询并锁定 Membership；按 HMAC 查询并锁定 Refresh Session；按 Prefix 查询 API Key；按 KnowledgeBase 查询 Scope；按 version 更新状态并在 affected rows 为零时抛语义冲突。

- [ ] 写 `IdEntityTypeTest`、Schema/DAO 组件测试和 Repository 可见性架构测试，运行并确认缺少类型、表和 DAO 时失败。
- [ ] 添加 code 3–9，禁止修改 1–2；创建 `schema-access.sql` 的 `platform_user`、`login_account`、`tenant`、`tenant_membership`、`refresh_session`、`api_key_scope`、`api_key`、`outbox_event` 与必需唯一/查询索引。
- [ ] 实现 Entity/Repository/DAO、安全适配器与配置；密码 char[] 使用后立即清零；正式 Profile 禁止秘密默认值。
- [ ] 运行目标测试、`spotlessCheck` 和 schema 重复初始化测试，确认幂等通过。
- [ ] 提交：`feat(plan_20/20.2): add access persistence and security baseline`。

## 20.3 实现 User/Account 注册、密码认证与默认 Tenant

**目标**：实现 UID/Nickname 与 USERNAME Account 分离的身份注册和凭据认证。  
**前置任务**：20.2  
**范围**：Username/Nickname/密码 Policy、注册事务、Username 冲突、Argon2id 校验、默认 Tenant 与 OWNER Membership、User/Account 状态守卫、安全失败语义。  
**非目标**：不签发 JWT/Refresh Token，不提供正式 gRPC/HTTP，不管理禁用状态。  
**验收标准**：注册原子创建 User、Account、Tenant、OWNER Membership；失败整体回滚；登录只在 User/Account ACTIVE 且密码正确时返回身份；所有失败使用统一 `InvalidCredentialsException`。  
**验证方式**：`./gradlew :crag-access-service:test --tests '*IdentityPolicyTest' --tests '*RegistrationComponentTest' --tests '*CredentialAuthenticationComponentTest'`。  
**涉及文件**：`crag-access-service/src/main/java/ai/cerbur/crag/access/core/identity/**`、`crag-access-service/src/main/java/ai/cerbur/crag/access/core/membership/TenantRegistrationResult.java`、`crag-access-service/src/test/java/ai/cerbur/crag/access/core/identity/**`

**Interfaces**：

```java
public record RegisterIdentityCommand(String nickname, String username, char[] password) {}
public record RegisteredIdentity(long userId, long accountId, long tenantId, long membershipId) {}
public record AuthenticatedIdentity(long userId, long accountId, String nickname) {}

public final class IdentityService {
  @Transactional
  public RegisteredIdentity register(RegisterIdentityCommand command);

  @Transactional(readOnly = true)
  public AuthenticatedIdentity authenticate(String username, char[] password);
}
```

- [ ] 先写 Policy 单元测试和注册/认证组件测试，覆盖边界长度、非法字符、规范化冲突、错误密码、禁用状态和中途失败回滚；运行并确认失败。
- [ ] 实现最小 Policy、异常、Command/Result 与 `IdentityService`；默认 Tenant 名称使用 `<nickname> 的空间`，不依赖 Email。
- [ ] 运行目标测试，确认数据库只保存 Argon2id 字符串且错误不泄漏 Username 是否存在。
- [ ] 提交：`feat(plan_20/20.3): implement identity registration and login`。

## 20.4 实现 Membership 生命周期与权限矩阵

**目标**：实现 OWNER/MEMBER 管理、固定权限动作、重新加入语义和最后 OWNER 并发保护。  
**前置任务**：20.3  
**范围**：`TenantAction`、Authorization Decision、按 Username 添加成员、REMOVED 重新激活为 MEMBER、角色切换、移除、查询/列表、Tenant/OWNER 锁。  
**非目标**：不实现邀请 Token、邮件、用户搜索或资源数据查询。  
**验收标准**：权限矩阵逐项准确；Member 不能管理或查看成员列表、管理 API Key、删除 KB；Member 只能删除自己的 Document；添加不存在或禁用 Username 使用不泄漏账号状态的统一错误；任何并发提交后至少一名 OWNER；跨 Tenant 查询不泄漏 Membership。  
**验证方式**：`./gradlew :crag-access-service:test --tests '*TenantPermissionPolicyTest' --tests '*MembershipComponentTest'`，最后 OWNER 真实锁语义由 20.8 Docker 回归证明。  
**涉及文件**：`crag-access-service/src/main/java/ai/cerbur/crag/access/core/membership/**`、`crag-access-service/src/test/java/ai/cerbur/crag/access/core/membership/**`

**Interfaces**：

```java
public enum TenantAction {
  MANAGE_MEMBERS, CREATE_KNOWLEDGE_BASE, VIEW_KNOWLEDGE_BASE,
  UPLOAD_DOCUMENT, DELETE_OWN_DOCUMENT, DELETE_ANY_DOCUMENT,
  DELETE_KNOWLEDGE_BASE, MANAGE_API_KEY
}

public record AuthorizationRequest(
    long actorUserId, long tenantId, TenantAction action, Long resourceOwnerUserId) {}

public final class MembershipService {
  public AuthorizationDecision authorize(AuthorizationRequest request);
  public MembershipResult addByUsername(long actorUserId, long tenantId, String username);
  public MembershipResult changeRole(long actorUserId, long tenantId, long memberUserId, MembershipRole role);
  public MembershipResult remove(long actorUserId, long tenantId, long memberUserId);
  public MembershipResult get(long actorUserId, long tenantId, long memberUserId);
  public List<MembershipResult> list(long actorUserId, long tenantId, int pageSize, String pageToken);
}
```

- [ ] 写完整角色矩阵参数化测试、重新加入测试、跨 Tenant 测试和最后 OWNER 组件测试，运行并确认失败。
- [ ] 实现 Policy 与 Membership Service；管理命令先实时授权 actor，再锁定 Tenant/OWNER 集合并执行状态迁移。
- [ ] 运行目标测试，确认 REMOVED 行被复用、version 递增且无第二行 Membership。
- [ ] 提交：`feat(plan_20/20.4): implement tenant membership authorization`。

## 20.5 实现 JWT 与 Refresh Session

**目标**：实现身份型 RS256 JWT、Refresh Token 单次轮换、Session Family 复用检测与认证 Facade。  
**前置任务**：20.3  
**范围**：JWT Properties/Issuer/Public Key Set、Refresh Token HMAC、Session Family、Register/Login/Refresh/Logout 编排、Clock/Random 可控、并发锁与 Metrics hook。  
**非目标**：不处理 Cookie/CSRF，不实现改密、退出全部设备或在线密钥管理 RPC。  
**验收标准**：JWT claims 精确且无 tenant/role；Access 15 分钟、Refresh 30 天；刷新使旧行 ROTATED 并创建新行；ROTATED Token 复用撤销 Family；并发只一次成功；缺少正式密钥 readiness DOWN。  
**验证方式**：`./gradlew :crag-access-service:test --tests '*JwtIssuerTest' --tests '*RefreshSessionServiceTest' --tests '*AuthenticationComponentTest'`，真实 PostgreSQL 并发由 20.8 验证。  
**涉及文件**：`crag-access-service/src/main/java/ai/cerbur/crag/access/core/session/**`、`crag-access-service/src/main/java/ai/cerbur/crag/access/security/jwt/**`、`crag-access-service/src/test/java/ai/cerbur/crag/access/core/session/**`、`crag-access-service/src/test/java/ai/cerbur/crag/access/security/jwt/**`

**Interfaces**：

```java
public record TokenPair(
    String accessToken, Instant accessExpiresAt, String refreshToken,
    Instant refreshExpiresAt, long sessionFamilyId) {}

public interface JwtIssuer {
  IssuedJwt issue(long userId, long sessionFamilyId, Instant issuedAt);
  JwtVerificationKeySet verificationKeys();
}

public final class AuthenticationService {
  public AuthenticationResult register(RegisterIdentityCommand command);
  public AuthenticationResult login(String username, char[] password);
  public AuthenticationResult refresh(String refreshToken);
  public void logout(long userId, long sessionFamilyId);
}
```

- [ ] 写固定 Clock/RSA Key 的 JWT 测试，断言 `sub/sid/jti/iss/aud/iat/nbf/exp/kid` 和缺失 tenant/role；先运行并确认失败。
- [ ] 写 Refresh 状态机与组件测试，覆盖 ACTIVE、ROTATED 复用、REVOKED、EXPIRED、禁用 User/Account 和事务回滚；先运行并确认失败。
- [ ] 实现 JWT/Session/Authentication Service；注册外层事务同时包含 Identity 注册和首个 Session 创建。
- [ ] 运行目标测试和日志捕获负向测试，确认 Token/密码不出现在日志或异常。
- [ ] 提交：`feat(plan_20/20.5): implement jwt and refresh sessions`。

## 20.6 实现 Scope 与 API Key 生命周期

**目标**：实现 KnowledgeBase 最小授权投影、单 Scope API Key 和完整状态机。  
**前置任务**：20.4、20.5  
**范围**：Scope 注册/Block、Owner 实时授权、Key 创建/鉴权/停用/启用/轮换/吊销/过期、Prefix 有限冲突重试、HMAC 恒定时间比较、Last Used 更新。  
**非目标**：不发布事件，不实现缓存、Unblock 或删除编排。  
**验收标准**：Scope Tenant 不可改；BLOCKED 终态；Key 只绑定一个 KB；完整 Key 只返回一次；状态迁移合法；鉴权失败统一；默认 90 天且不超过 365 天。  
**验证方式**：`./gradlew :crag-access-service:test --tests '*ApiKeyPolicyTest' --tests '*ApiKeyComponentTest'`。  
**涉及文件**：`crag-access-service/src/main/java/ai/cerbur/crag/access/core/apikey/**`、`crag-access-service/src/test/java/ai/cerbur/crag/access/core/apikey/**`

**Interfaces**：

```java
public record CreatedApiKey(
    long apiKeyId, long tenantId, long knowledgeBaseId, String name,
    String completeKey, Instant expiresAt) {}

public record AuthenticatedApiKey(
    long apiKeyId, long tenantId, long knowledgeBaseId, Instant expiresAt) {}

public final class ApiKeyService {
  public ApiKeyScopeResult registerScope(long actorUserId, long tenantId, long knowledgeBaseId);
  public ApiKeyScopeResult blockScope(long actorUserId, long tenantId, long knowledgeBaseId);
  public CreatedApiKey create(long actorUserId, long tenantId, long knowledgeBaseId, String name, Duration ttl);
  public ApiKeyResult disable(long actorUserId, long tenantId, long apiKeyId);
  public ApiKeyResult enable(long actorUserId, long tenantId, long apiKeyId);
  public CreatedApiKey rotate(long actorUserId, long tenantId, long apiKeyId, Duration ttl);
  public ApiKeyResult revoke(long actorUserId, long tenantId, long apiKeyId);
  public AuthenticatedApiKey authenticate(String completeKey);
}
```

- [ ] 写状态机、TTL、格式、Prefix 冲突、跨 Tenant、Scope Block、轮换和统一鉴权失败测试，运行并确认失败。
- [ ] 实现 Scope/API Key Core；解析格式后先按 Prefix 查询，再 HMAC 恒定时间比较；Prefix 冲突最多重新生成 3 次。
- [ ] 运行目标测试，检查数据库与日志从未出现完整 Key，轮换返回的新 Key仅存在于结果对象。
- [ ] 提交：`feat(plan_20/20.6): implement api key lifecycle`。

## 20.7 接入 API Key 失效 Outbox Producer

**目标**：让 Key 状态变化与 `API_KEY_INVALIDATED` 事件在同一 Access 本地事务可靠提交。  
**前置任务**：20.6  
**范围**：Event constants/payload/writer、Access Event Snowflake ID、API Key Service 接线、Publisher 配置、payload 安全测试。  
**非目标**：不实现 Open API Consumer、缓存或 Reconciler。  
**验收标准**：Disable、Enable、Rotate、Revoke、Scope Block 各写一条 PENDING Outbox；payload 只含 Key/Scope 定位和 action/version，不含完整 Key/HMAC；业务回滚时事件也回滚。  
**验证方式**：`./gradlew :crag-access-service:test --tests '*ApiKeyInvalidationPayloadTest' --tests '*AccessEventProducerComponentTest'`。  
**涉及文件**：`crag-access-service/src/main/java/ai/cerbur/crag/access/producer/**`、`crag-access-service/src/main/java/ai/cerbur/crag/access/core/apikey/**`、`crag-access-service/src/main/resources/application.yml`、`crag-access-service/src/test/java/ai/cerbur/crag/access/producer/**`

**Interfaces**：

```java
public record ApiKeyInvalidatedPayload(
    String resourceType, long resourceId, long tenantId,
    long knowledgeBaseId, String action, long resourceVersion) {}

public final class ApiKeyInvalidationOutboxWriter {
  public long write(ApiKeyInvalidatedPayload payload, String traceId, Instant occurredAt);
}
```

- [ ] 写五类状态变化、payload 安全和事务回滚组件测试，运行并确认失败。
- [ ] 实现 Producer，并在 Task 20.6 的事务方法状态提交后、事务结束前调用 Writer；单 Key 变化使用 `resourceType=API_KEY`，Scope Block 使用 `resourceType=API_KEY_SCOPE`，使 router4 能按 Key 或 KnowledgeBase 批量失效。
- [ ] 运行测试并直接查询 Outbox，断言 event type、resource、operation version、traceId 与 payload version。
- [ ] 提交：`feat(plan_20/20.7): publish api key invalidation events`。

## 20.8 完成 gRPC、Metrics、smoke HTTP 与 Docker 回归

**目标**：打通 Access 外部 Provider 与真实 PostgreSQL/Redis 验收闭环。  
**前置任务**：20.7  
**范围**：gRPC Provider/Mapper/Error、按 RPC 调用方授权、Access Metrics、smoke Controller/DTO、application-smoke、Compose access-service-smoke、七组 HTTP 回归脚本。  
**非目标**：不把 smoke 定义成正式 API，不修改 Console/Open 业务代码。  
**验收标准**：Console/Open 只能调用允许 RPC；安全错误稳定；默认 Profile smoke 404；真实 Docker 完成 identity/membership/session/api key/event/concurrency 回归；重复执行不依赖清表。  
**验证方式**：目标 Provider/Controller/Architecture 测试，加 `docker compose --profile smoke up -d --build db redis access-service-smoke` 与全部 `access_smoke_*.sh`。  
**涉及文件**：`crag-access-service/src/main/java/ai/cerbur/crag/access/grpc/**`、`crag-access-service/src/main/java/ai/cerbur/crag/access/metrics/**`、`crag-access-service/src/main/java/ai/cerbur/crag/access/controller/smoke/**`、`crag-access-service/src/main/resources/application-smoke.yml`、`crag-access-service/src/test/**`、`docker-compose.yml`、`scripts/tests/http/access_smoke_*.sh`

**Interfaces**：

```java
public final class AccessRpcAuthorizer {
  public void requireConsole();
  public void requireOpenApi();
  public void requireConsoleOrOpenApi();
}
```

Smoke HTTP 根路径固定为 `/api/v1/smoke/access`；Host 端口固定为 `8095`，容器内仍使用 `8091`。脚本必须覆盖注册→登录→刷新→旧 Token 复用、成员角色/最后 OWNER、Scope→Key→鉴权→轮换/吊销、失效 Stream、并发刷新和默认端点关闭。

- [ ] 写 gRPC 调用方矩阵测试、错误详情测试、Metrics 测试、smoke Profile 组件测试和默认 Profile 关闭测试，运行并确认失败。
- [ ] 实现 Provider/Mapper/Error/Authorizer/Metrics/Controller；Provider 不复制 Core 规则。
- [ ] 更新正式 access-service 的 Redis 依赖、ID 租约、Event Publisher 与运行时 Secret 配置；新增 access-service-smoke Compose 服务并使用独立测试 RSA Key/Pepper，不给正式 access-service 增加宿主机业务端口。
- [ ] 编写并运行七组 Docker 脚本；并发刷新脚本必须断言仅一个成功且 Family 最终撤销，不能只统计 HTTP 2xx。
- [ ] 重复运行整组 Docker 回归，若无修改重跑结果不同则按 flaky 规则阻断任务完成。
- [ ] 提交：`feat(plan_20/20.8): expose and verify access provider`。

## 20.9 同步约束、README 与全量验证

**目标**：把 plan20 最终实现事实写回项目入口并完成所有静态、Gradle 与 Docker 验证。  
**前置任务**：20.8  
**范围**：更新包结构、Docker、测试工作流、README、约束校验器与测试；执行全量验证并记录证据。  
**非目标**：不改写已完成 Plan 历史，不实现 router4/router5，不新增运行时能力。  
**验收标准**：文档只描述真实实现；校验器可防止 contracts/包/Compose/smoke 漂移；Spotless、全量 test/check、Plan strict verify、全部 Access Docker 回归通过且无跳过。  
**验证方式**：`./gradlew spotlessCheck test check`、四个 Python 校验器、七组 Access Docker 脚本、`git diff --check`。  
**涉及文件**：`constraints/package-structure.md`、`constraints/docker-structure.md`、`constraints/test-workflow.md`、`README.md`、`scripts/validate_constraints.py`、`scripts/tests/test_validate_constraints.py`、`plan/plan_20/plan_20.md`、`plan/index/README.md`

- [ ] 先更新约束校验器测试，要求 Access contracts、领域包、Access smoke 服务与脚本登记；运行并确认旧文档/校验器状态失败。
- [ ] 按最终代码同步三份约束和 README，不复制 Plan 状态或实现细节到 AGENTS/CLAUDE。
- [ ] 依次运行 `spotlessCheck`、目标模块测试、根 `test/check`、Python 校验器和 Docker 回归；每项记录日期、环境、命令、结果与摘要。
- [ ] 检查 `git diff --check`、`git status --short` 和提交范围，无 plan20 未提交改动后提交：`docs(plan_20/20.9): sync access constraints and verification`。

## 验收记录

> 以下为执行 session 自测证据；最终独立验收由未参与实现的新 session 复核。

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-28 | macOS / JDK 21 / Gradle 9.4.1 | `./gradlew :crag-id:test` | 通过 | IdEntityType code 1–9 锁定测试 |
| 2026-06-28 | macOS / H2 | `./gradlew :crag-access-service:test`（纯单元 + 轻量组件 + 架构） | 通过 | Policy、注册/认证、Membership 矩阵与最后 OWNER、JWT claims、Refresh 状态机与复用、API Key 生命周期、失效 Outbox、gRPC 错误映射与调用方矩阵、smoke Controller 与默认禁用 |
| 2026-06-28 | macOS / 全模块 | `./gradlew spotlessCheck test check` | 通过 | 全模块编译、Spotless、Plan strict 校验 |
| 2026-06-28 | macOS / Python 3 | `validate_module_dependencies.py`、`validate_framework_dependencies.py`、`validate_constraints.py`、`validate_plans.py --strict` | 通过 | 0 error；24 warning 均为历史 Plan 的 workflow v3 提示（非 plan_20） |
| 2026-06-28 | Docker / PostgreSQL 17 + Redis 7.4 | `access_smoke_default_disabled_test.sh` | 通过 | 默认 profile 不暴露 smoke 入口（404）；prod access-service 就绪 UP |
| 2026-06-28 | Docker / PostgreSQL + Redis | `access_smoke_identity_test.sh` | 通过 | 注册→登录→刷新→旧 Token 复用拒绝 |
| 2026-06-28 | Docker / PostgreSQL + Redis | `access_smoke_membership_test.sh` | 通过 | 成员添加/角色/最后 OWNER 保护 |
| 2026-06-28 | Docker / PostgreSQL + Redis | `access_smoke_session_reuse_test.sh` | 通过 | 轮换成功，旧 Token 复用撤销整个 Family |
| 2026-06-28 | Docker / PostgreSQL + Redis | `access_smoke_api_key_test.sh` | 通过 | Key 创建/鉴权/轮换/Scope 阻塞 |
| 2026-06-28 | Docker / PostgreSQL + Redis | `access_smoke_event_test.sh` | 通过 | API_KEY_INVALIDATED 发布到 Redis Stream crag:event:access |
| 2026-06-28 | Docker / PostgreSQL + Redis | `access_smoke_concurrent_refresh_test.sh` | 通过 | 并发刷新仅一次成功（1/8），其余拒绝，Family 最终撤销 |
| 2026-06-28 | macOS | `git diff --check` | 通过 | 无空白错误 |

### 未执行项与风险

- 并发刷新的最后 OWNER 与 Refresh 锁语义以真实 PostgreSQL（Docker）证明；H2 组件测试不表述锁语义。
- 真实外部 LLM/供应商边界不属于 plan_20 范围，无相关条件验收。
- Argon2id 基线参数在正式与 smoke 配置生效；测试环境沿用基线（单次哈希约 100ms，可接受）。

## 阻塞记录

无。发生阻塞时记录原因、当前进度、解除条件、解除方、下一步与日期。

## 废弃任务记录

无。任务废弃时记录原因、日期及替代任务或决策。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-28 | 创建 plan20 并设为待开始 | router1/plan18 与 router2/plan19 已完成；router3 设计已确认并通过书面规格复核 | Access Provider 进入执行队首，9 个任务待执行 |
| 2026-06-28 | 20.1–20.9 全部实现、自测并回填实现 hash；Plan 转为 verifying | 9 个任务均完成实现提交、Gradle/Spotless/Plan strict/4 个 Python 校验器与 7 组 Access Docker HTTP 回归通过 | 全部 9 项进入待验收，交接独立验收 session |
