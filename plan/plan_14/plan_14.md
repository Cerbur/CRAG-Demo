---
workflow_version: 3
plan_id: plan_14
type: main
status: ready
created: 2026-06-22
updated: 2026-06-22
---

# plan_14 — 多服务骨架、gRPC 契约与数据边界基线

> **For agentic workers:** 执行本计划必须先读取 `skill/execute-crag-plan/SKILL.md`；实现步骤使用测试先行、任务级提交和独立验收交接。

**Goal**：建立五个独立 Spring Boot 进程、按提供方归属的 gRPC 契约基线、协议无关 Runtime、服务身份和 PostgreSQL 数据边界，并保持现有 RAG HTTP 链路兼容。

**Architecture**：Probe 协议由极窄的 `crag-probe-contracts` 定义，传输、认证、deadline、Health 和资源生命周期由完全协议无关的 `crag-grpc-runtime` 提供，具体 Probe 实现、Stub 和下游编排归各 Application 组合根。Access、Knowledge、RAG 使用独立账号与自有 Schema；Console/Open 只通过受认证 gRPC 调用下游，不建立 Application 模块依赖。

**Tech Stack**：Java 21、Spring Boot 4.1.0、Spring Framework 7、Gradle 9.4.1、gRPC Java 1.82.0、Protocol Buffers 35.1、Protobuf Gradle Plugin 0.10.0、PostgreSQL 17、pgvector、Docker Compose。

## 全局实现约束

- 只实现本计划列出的基础骨架；不得顺带加入领域 RPC、业务表、Redis、事件、Snowflake、TLS/mTLS 或 API 业务。
- 新增 Java 代码遵守 `constraints/code-style.md`；测试分类、命名和执行遵守 `constraints/test-workflow.md`。
- Contracts 只包含 Proto、生成代码和生成配置；Runtime 不依赖任何具体 Contracts；业务库模块不依赖 Runtime。
- 所有 gRPC 调用必须显式 deadline；只有标准 Health 匿名；默认不启用 Reflection。
- 五个任务各自形成一个实现提交，提交前先完成该任务列出的局部验证；14.5 再执行完整回归。

## 背景与目标

现有仓库由 `crag-app` 作为唯一 Spring Boot 组合根，将 HTTP API、Ingestion、Retrieval、Query 和 Storage 运行在同一个进程、端口和数据库账号中。多租户知识平台设计已经确定目标形态为 Console API、Open API、Access Service、Knowledge Service 和 RAG Service 五个独立进程；后续 Snowflake、事件基础设施、Knowledge、Access 和双 API 业务能力都依赖稳定的进程、契约、身份和数据边界。

本计划只建设分布式基础骨架：增加只承载 Probe Proto 的 `crag-probe-contracts` 和完全协议无关的 `crag-grpc-runtime`；将现有完整 RAG 运行时迁移到 `crag-rag-service`；建立 Access、Knowledge、Console 和 Open 四个可独立启动的空业务组合根；使用独立 PostgreSQL Schema 与账号隔离 Access、Knowledge、RAG；重构 Docker Compose 为五进程拓扑；通过标准 gRPC Health、受身份保护的 Platform Probe、Actuator readiness 和现有 RAG HTTP 回归证明骨架可运行且旧链路未退化。

本计划完成后仍不提供注册、KnowledgeBase、文件上传或 API Key 业务。现有 AdminRag、UserQuery 和 Smoke 入口暂时继续由 `crag-rag-service` 承载，作为后续业务迁移前的兼容入口。

## 范围

- 新增 `crag-probe-contracts`，只生成 Platform Probe 的 Java Protobuf 消息和 gRPC Stub。
- 未来领域契约按服务提供方分别归入 `crag-access-contracts`、`crag-knowledge-contracts`、`crag-rag-contracts`；本计划不创建空领域契约模块。
- 固定 gRPC Java `1.82.0`、Protocol Buffers `35.1`、Protobuf Gradle Plugin `0.10.0`。
- 定义 `crag.platform.v1.PlatformProbeService`，用于验证服务身份、网络与契约生成，不承载领域业务。
- 新增 `crag-grpc-runtime`，提供 gRPC Server 生命周期、标准 Health Service、客户端 Channel、调用方凭据和服务端身份校验。
- Demo 服务身份使用每调用方独立静态凭据，通过 gRPC Metadata 传递；Health RPC 允许匿名，Platform Probe 必须鉴权。
- 删除唯一组合根 `crag-app`，新增五个 Spring Boot 应用模块：
  - `crag-console-api`
  - `crag-open-api`
  - `crag-access-service`
  - `crag-knowledge-service`
  - `crag-rag-service`
- 将现有 `crag-app` 的 RAG 装配、配置、Schema、测试和 Boot Jar 职责迁入 `crag-rag-service`。
- Console API 通过受保护 Probe 检查 Access、Knowledge、RAG；Open API 检查 Access、RAG。
- PostgreSQL 使用 `access`、`knowledge`、`rag` 三个 Schema 和三个最小权限账号。
- 使用新的 `data/pgdata-platform/` 保存平台数据库，保留旧 `data/pgdata/` 以支持非破坏性回滚。
- Docker Compose 默认启动五个 Java 进程、PostgreSQL、模型初始化和 Sidecar；Smoke 诊断实例继续通过显式 Profile 启动。
- 更新模块依赖校验、ArchUnit、Docker 服务索引、包结构索引、测试脚本和 README。
- 保持现有 AdminRag、UserQuery、Retrieval、LLM Stub、Sidecar 和 Smoke 行为。

## 非目标

- 不实现 Snowflake ID、Redis Worker 租约或任何 UUID 到 `long` 的迁移。
- 不引入 Redis、Outbox、事件信封、Consumer Group、幂等记录或补偿任务。
- 不创建 User、Tenant、Membership、KnowledgeBase、Document、File Object、API Key 或 Refresh Session 表。
- 不定义 Access、Knowledge、Ingestion、Retrieval 或 Query 的领域 gRPC RPC。
- 不实现注册、登录、JWT、文件上传、异步索引、API Key 查询或删除状态机。
- 不把现有 Ingestion、Retrieval、Query 拆成独立部署进程。
- 不移除 `crag-api` 或改变 AdminRag/UserQuery HTTP 契约；该模块在本计划中作为 RAG 兼容 HTTP 边界保留。
- 不引入 Flyway、Liquibase、mTLS、Service Mesh、API Gateway、Kubernetes 或外部 Secret 管理系统。
- 不迁移旧 `data/pgdata/` 数据，不执行 `docker compose down -v`，不删除旧数据库目录。
- 不暴露 Access、Knowledge、RAG 的 gRPC 或管理端口到宿主机。
- 不修改 Embedding、Sparse、Dense、RRF、Rerank、Context、Prompt 或 LLM 业务算法。

## 前置依赖

- **执行前置 Plan**：无
- `plan_13` 已完成，当前框架基线为 Java 21、Spring Boot 4.1.0、Spring Framework 7 和 Gradle 9.4.1。
- `docs/superpowers/specs/2026-06-22-multi-tenant-knowledge-platform-design.md` 已由用户确认。
- gRPC Java `1.82.0` 于 2026-06-11 发布，Protobuf Gradle Plugin `0.10.0` 于 2026-04-20 发布，Protocol Buffers `35.1` 于 2026-06-11 发布；本计划固定这些版本，不在执行期自动漂移。
- 本计划进入实现前必须先提交 `plan_14`、`plan_main`、方向归档和索引；未提交时不得开始 14.1。

## 文件边界

- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle/libs.versions.toml`
- `crag-probe-contracts/**`
- `crag-grpc-runtime/**`
- `crag-console-api/**`
- `crag-open-api/**`
- `crag-access-service/**`
- `crag-knowledge-service/**`
- `crag-rag-service/**`
- `crag-app/**`（迁移完成后删除）
- `crag-api/**`（仅兼容入口装配和测试路径变化）
- `crag-smoke/**`（仅 RAG Smoke 装配路径变化）
- `crag-common/**`（仅共享注解或测试架构位置必须迁移时）
- `docker/java-service.Dockerfile`
- `docker/postgres/init/001-platform.sh`
- `Dockerfile`（由通用 Java Service Dockerfile 替代后删除）
- `docker-compose.yml`
- `.dockerignore`
- `.env.example`
- `scripts/validate_module_dependencies.py`
- `scripts/tests/test_validate_module_dependencies.py`
- `scripts/validate_framework_dependencies.py`
- `scripts/tests/test_validate_framework_dependencies.py`
- `scripts/validate_constraints.py`
- `scripts/tests/test_validate_constraints.py`
- `scripts/tests/http/**`
- `constraints/package-structure.md`
- `constraints/docker-structure.md`
- `constraints/api-style.md`（仅当前 HTTP 所有者事实变化）
- `constraints/persistence-style.md`（仅 Schema 与账号硬边界）
- `constraints/test-workflow.md`（仅服务名或回归入口事实变化）
- `README.md`
- `plan/plan_main.md`
- `plan/plan_archive/2026-06-22-multi-tenant-knowledge-platform-direction.md`
- `plan/plan_14/plan_14.md`
- `plan/index/README.md`

## 实现文件地图

### 基础契约与 Runtime

- `crag-probe-contracts/src/main/proto/crag/platform/v1/platform_probe.proto`：唯一 Probe 协议事实来源。
- `crag-probe-contracts/build.gradle.kts`：Protobuf/grpc-java 生成配置，不应用 Spring 插件。
- `crag-grpc-runtime/src/main/java/ai/cerbur/crag/grpc/runtime/config/GrpcServerConfiguration.java`：显式 Server 启用入口。
- `crag-grpc-runtime/src/main/java/ai/cerbur/crag/grpc/runtime/config/GrpcClientConfiguration.java`：显式 Client 启用入口。
- `crag-grpc-runtime/src/main/java/ai/cerbur/crag/grpc/runtime/server/GrpcServerLifecycle.java`：Server 构建、启动、Health 状态切换与关闭。
- `crag-grpc-runtime/src/main/java/ai/cerbur/crag/grpc/runtime/server/GrpcServerProperties.java`：端口、allowed callers 与关闭超时绑定。
- `crag-grpc-runtime/src/main/java/ai/cerbur/crag/grpc/runtime/server/GrpcServiceAuthenticationInterceptor.java`：Metadata 认证并写入 gRPC Context。
- `crag-grpc-runtime/src/main/java/ai/cerbur/crag/grpc/runtime/identity/GrpcCallerIdentity.java`：认证后不可变身份。
- `crag-grpc-runtime/src/main/java/ai/cerbur/crag/grpc/runtime/identity/GrpcCallerContext.java`：Application 读取当前 caller 的唯一入口。
- `crag-grpc-runtime/src/main/java/ai/cerbur/crag/grpc/runtime/client/GrpcChannelFactory.java`：Application 显式创建命名 Channel 的接口。
- `crag-grpc-runtime/src/main/java/ai/cerbur/crag/grpc/runtime/client/DefaultGrpcChannelFactory.java`：Metadata、deadline 守卫和 Channel 关闭实现。
- `crag-grpc-runtime/src/main/java/ai/cerbur/crag/grpc/runtime/client/GrpcClientProperties.java`：单一 caller 身份、最大 deadline 和关闭超时绑定。

### Application 组合根

- `crag-access-service/src/main/java/ai/cerbur/crag/access/app/AccessServiceApplication.java`：Access 组合根，只导入 gRPC Server 能力。
- `crag-knowledge-service/src/main/java/ai/cerbur/crag/knowledge/app/KnowledgeServiceApplication.java`：Knowledge 组合根，只导入 gRPC Server 能力。
- `crag-rag-service/src/main/java/ai/cerbur/crag/rag/app/RagServiceApplication.java`：现有 RAG 业务组合根与兼容 HTTP 所有者。
- 三个服务各自的 `probe/PlatformProbeGrpcService.java`：实现生成的 Probe 基类，读取 `GrpcCallerContext` 并返回规范服务名。
- 三个服务各自的 `health/ExpectedSchemaHealthIndicator.java`：查询 `current_schema()` 并纳入 readiness。
- `crag-console-api/src/main/java/ai/cerbur/crag/console/app/ConsoleApiApplication.java` 与 `crag-open-api/src/main/java/ai/cerbur/crag/open/app/OpenApiApplication.java`：只导入 gRPC Client 能力。
- 两个 API 各自的 `probe/ProbeTargetProperties.java`：绑定真实下游名称与地址。
- 两个 API 各自的 `probe/DownstreamConnectivityHealthIndicator.java`：并行执行生成 Stub 的受认证 Probe 并聚合 readiness。
- 两个 API 各自的 `config/ProbeExecutorConfiguration.java`：有界 Spring 执行器和命名 Channel/Stub Bean。

### PostgreSQL、Docker 与回归

- `docker/postgres/init/001-platform.sh`：在镜像通过 `POSTGRES_DB=crag_platform` 创建的数据库内建立 `extensions`、三个业务角色及其自有 Schema，随后撤销临时建 Schema 权限；脚本自身不执行 `CREATE DATABASE`。
- `crag-rag-service/src/main/resources/schema.sql`：只创建 RAG 三张表与索引，不创建扩展。
- `docker/java-service.Dockerfile`：以单一 `SERVICE_MODULE` 参数构建和运行五种 Boot Jar。
- `docker-compose.yml`：五个 Java 服务、数据库、模型初始化、Sidecar 和 Smoke Profile 的唯一编排。
- `scripts/tests/http/platform_topology_test.sh`：验证进程、身份、deadline、readiness、Schema owner、跨 Schema拒绝、容器用户和敏感信息。
- `scripts/tests/http/docker_readiness_test.sh`：适配新服务名、端口和故障恢复。

## 关键决策

### 模块与进程

- 五个进程与 Gradle Application 模块一一对应，不再保留通用 `crag-app`。
- `crag-rag-service` 是现有 RAG 业务模块的组合根，直接装配 `crag-storage`、`crag-ingestion`、`crag-retrieval`、`crag-query`、兼容 `crag-api` 和运行时 `crag-smoke`。
- `crag-access-service` 与 `crag-knowledge-service` 在本计划只拥有组合根、独立 DataSource、Actuator、gRPC Server 和 Platform Probe，不包含未来领域代码。
- `crag-console-api` 与 `crag-open-api` 在本计划只拥有 Web/Actuator 组合根和下游 Probe HealthIndicator，不提供占位业务 Controller。
- 业务库模块不得依赖任何 Application 模块或 `crag-grpc-runtime`；只有五个 Application 组合根负责把领域 Java API 适配为 gRPC。
- Application 模块之间不得建立 Gradle project 依赖；跨进程依赖只能消费对应服务提供方拥有的 Contracts 模块，并使用 `crag-grpc-runtime` 建立传输。
- Server 与 Client 能力独立显式启用：Access、Knowledge、RAG 在本计划只启用 Server；Console、Open 只启用 Client；未来业务服务出现真实出站 RPC 时才启用 Client。
- `crag-api` 的名称暂时保留，但其 Controller 只在 `crag-rag-service` 兼容运行时装配；后续双 API Plan 负责迁移并删除该旧边界。

### gRPC 契约

- `crag-probe-contracts` 是唯一的跨服务基础设施契约例外，只包含 `.proto`、生成代码和生成所需 Gradle 配置；禁止手写业务逻辑、Spring 配置、Client 包装、Server/Channel 生命周期、认证或授权逻辑。
- 领域契约严格按提供 RPC 的 Server 归属。未来分别创建 `crag-access-contracts`、`crag-knowledge-contracts`、`crag-rag-contracts`；不创建 `crag-common-contracts`，也不在本计划预建空模块。
- 根 Gradle 与 version catalog 统一管理 Protobuf 插件、protoc 和 grpc-java 版本；Contracts 子模块只声明自身 Proto 与生成运行时依赖，本计划不额外引入 convention plugin。
- Protobuf package 为 `crag.platform.v1`，Java package 为 `ai.cerbur.crag.contracts.platform.v1`。
- Proto 文件固定为 `crag-probe-contracts/src/main/proto/crag/platform/v1/platform_probe.proto`。
- `PlatformProbeService` 只包含一元方法：

```proto
service PlatformProbeService {
  rpc Check(PlatformProbeRequest) returns (PlatformProbeResponse);
}

message PlatformProbeRequest {}

message PlatformProbeResponse {
  string service_name = 1;
  string caller_service = 2;
}
```

- `service_name` 返回服务端 `spring.application.name`；`caller_service` 返回服务端认证后的调用方名称。
- 标准 `grpc.health.v1.Health` 用于进程健康，不重复定义自有 Health RPC。
- `v1` 只允许向后兼容演进；字段一经进入已完成 Plan 不重编号、不复用，删除字段必须 `reserved`；破坏性变化新增 `v2`，Gradle 模块名不携带版本。
- Platform Probe 只证明 gRPC 可响应、身份认证成功和服务名称正确，不递归检查数据库、Sidecar 或下游业务状态。

### gRPC Runtime 边界与接口

- `crag-grpc-runtime` 完全协议无关，不依赖 `crag-probe-contracts` 或任何领域 Contracts；它只依赖 grpc-java、Spring Context 和配置绑定所需最小库。
- 上述协议无关边界同样适用于测试依赖：Runtime 测试使用测试源码内的专属 `BindableService` 验证认证、deadline、Health 和生命周期，不依赖 Probe Contracts；生成 Probe 契约的组合验证放在 Contracts 或 Application 模块。
- Runtime 提供两个独立显式导入入口：
  - `GrpcServerConfiguration`：创建 Server、注册标准 Health、收集 Application 显式声明的 `BindableService` Bean并统一附加认证拦截器。
  - `GrpcClientConfiguration`：提供 `GrpcChannelFactory`、单一调用方身份和 deadline 守卫；不自动读取 target 列表或创建 Channel。
- 不提供自动启用全部能力的总配置，不基于包扫描发现服务，不让客户端应用意外监听 gRPC 端口。
- Runtime 对 Application 提供以下稳定接口：

```java
public record GrpcCallerIdentity(String serviceName) {}

public interface GrpcCallerContext {
  GrpcCallerIdentity requireIdentity();
}

public interface GrpcChannelFactory {
  ManagedChannel create(String targetName, String target, boolean plaintext);
}
```

- `PlatformProbeClient`、`PlatformProbeResult`、Probe Server 实现和 Probe HealthIndicator 不属于 Runtime；各 Application 直接使用生成 Stub 实现或调用 Probe。
- Application 显式提供 `BindableService` Bean；Runtime 注册除标准 Health 外的全部服务并默认认证。仅精确匹配 `grpc.health.v1.Health/*` 允许匿名，不提供通用匿名白名单。
- plan_14 默认不启用 Server Reflection；未来调试能力必须由独立 Profile 和 Plan 决定。
- Server 生命周期由 Spring 管理，使用 `SmartLifecycle` 启动和优雅关闭 `io.grpc.Server`；禁止生产代码直接创建线程池。
- Server 启动后将标准 Health Service 的整体状态设为 `SERVING`，关闭前设为 `NOT_SERVING`。
- 标准 Health 只表达 gRPC Server 生命周期，不与 Actuator readiness、数据库或 Sidecar 状态联动。
- `GrpcChannelFactory` 追踪自己创建的 Channel；Bean 销毁时依次调用 `shutdown()`、等待受控超时、必要时调用 `shutdownNow()`，不得泄漏 Netty 线程。
- Application 根据真实下游显式创建命名 Channel Bean和生成 Stub；Runtime 不维护全局 target Map。
- deadline 守卫在调用开始时检查 `CallOptions`：缺少 deadline 或超过 `crag.grpc.client.max-deadline=10s` 时立即拒绝；Runtime 不静默补默认值。Platform Probe Stub 显式使用 `2s` deadline。
- 身份拦截器把认证结果写入 `io.grpc.Context`；`GrpcCallerContext` 从该 Context 读取，不使用 `ThreadLocal` 或 Spring Request Scope。

### 服务身份

- 线上服务身份、`spring.application.name`、Compose 服务名和 Probe 返回值统一使用 `console-api`、`open-api`、`access-service`、`knowledge-service`、`rag-service`；模块名和 Java 包名不得作为身份别名。
- Metadata Key 固定为：
  - `x-crag-caller-service`
  - `x-crag-service-token`
- 每个应用实例只有一套出站身份，由 `crag.grpc.client.caller-service` 和 `crag.grpc.client.token` 配置，所有出站 Channel 共用；禁止同一进程冒充多个调用方。
- 服务端允许调用方绑定为结构化 `Map<String, String>`：`crag.grpc.server.allowed-callers.<caller>=<token>`。Compose 使用各服务独立 `SPRING_APPLICATION_JSON` 注入，不使用逗号字符串或依赖带连字符 Map Key 的环境变量自动映射。
- Runtime 启动时拒绝空 caller、空 token、非法名称和空 allowed-callers；配置对象、异常和日志不得输出 token Map。
- 缺少身份、未知调用方或 token 不匹配统一返回 gRPC `UNAUTHENTICATED`，不在错误详情中返回 token 或允许调用方列表。
- 比较 token 使用固定时长比较，日志最多记录 caller 名称，不记录完整 token。
- 标准 Health Service 允许匿名调用；Platform Probe 和后续领域 Service 默认全部受身份拦截器保护。
- Runtime 只负责认证并建立 `GrpcCallerIdentity`；具体 RPC 授权由提供服务的 Application 负责，禁止把权限矩阵硬编码进 Runtime 或 Contracts。
- plan_14 的最小允许调用链固定为：

| Server | allowed callers |
| --- | --- |
| `access-service` | `console-api`、`open-api` |
| `knowledge-service` | `console-api` |
| `rag-service` | `console-api`、`open-api` |

- 不提前允许未来的 `rag-service → knowledge-service`；文件流式读取 RPC 落地时再由对应 Plan 扩展 Knowledge 白名单。
- Demo Compose 为 Console API 与 Open API 配置不同静态凭据；私有网络内使用显式 `plaintext: true`，不得自动降级。生产目标 TLS/mTLS 与外部 Secret 管理由后续独立 Plan负责，静态 token + plaintext 不得描述为生产安全方案。

### 数据隔离

- 单个 PostgreSQL 实例内建立数据库 `crag_platform`。
- `crag_platform` 由 PostgreSQL 镜像入口通过 `POSTGRES_DB=crag_platform` 创建；初始化脚本只在该数据库内管理角色、扩展、Schema 与权限，不自行创建数据库或切换连接。
- Schema 与账号固定为：

| 服务 | Schema | 账号 |
| --- | --- | --- |
| Access | `access` | `crag_access` |
| Knowledge | `knowledge` | `crag_knowledge` |
| RAG | `rag` | `crag_rag` |

- 三个 Schema 分别由对应业务账号独立拥有；初始化脚本临时授予数据库 `CREATE`，以对应账号身份创建自身 Schema 后立即撤销，运行期账号不能创建额外 Schema。
- 三个账号使用不同 Demo 密码，禁止 `SUPERUSER`、`CREATEDB`、`CREATEROLE`，不得访问其他业务 Schema；撤销对 `public` Schema 的 `CREATE`。
- 管理员账号只供 PostgreSQL 首次初始化、健康检查和验收命令使用，不注入任何 Java 容器。
- 初始化管理员创建 `extensions` Schema，并把 `vector`、`pg_trgm` 安装到其中；只向 `crag_rag` 授予 `USAGE`，业务账号均无扩展创建权限。
- 角色默认 `search_path` 与 JDBC URL 双重固定：Access 为 `access,pg_catalog`，Knowledge 为 `knowledge,pg_catalog`，RAG 为 `rag,extensions,pg_catalog`；JDBC `currentSchema` 分别为 `access`、`knowledge`、`rag,extensions`，确保 RAG DDL 能解析管理员安装的 `vector` 类型且 `current_schema()` 仍为 `rag`。
- 应用 Schema 脚本不得使用跨 Schema 限定名。Access、Knowledge 不创建空 `schema.sql` 或占位 SQL；RAG 继续使用自身幂等 `schema.sql` 创建三张业务表，并删除扩展创建语句。
- RAG 将现有三张表迁入 `rag` Schema，表结构和 UUID 字段在本计划保持不变。
- Access、Knowledge、RAG 的 readiness 分别查询 `current_schema()` 并断言为自身 Schema；连接失败或 Schema 不符时 DOWN，不尝试提权或修复权限。
- Schema owner、跨 Schema `SELECT/CREATE TABLE` 拒绝和管理员凭据隔离由 `platform_topology_test.sh` 在验收期使用管理员身份检查，不由业务应用检查全局权限。
- 初始化使用 `docker/postgres/init/001-platform.sh` 从环境变量读取三个业务密码；密码不进入 SQL 文件、Git 或日志。已有数据目录不会重新执行初始化脚本，修改环境变量不等于轮换已有角色密码。
- 新平台使用 `data/pgdata-platform/`；旧 `data/pgdata/` 不读取、不修改、不删除。若新目录内权限或结构漂移，应用/验收失败并给出诊断，不做隐式修补。
- `rag-service-smoke` 与正式 `rag-service` 共用 `crag_rag` 账号和 `rag` Schema，以唯一 `runId` 隔离测试数据，不创建第四个账号或 Schema。

### 端口与 Docker 拓扑

| Compose 服务 | HTTP/管理端口 | gRPC 端口 | 宿主机暴露 |
| --- | --- | --- | --- |
| `console-api` | 8080 | 无 Server | `8080:8080` |
| `open-api` | 8081 | 无 Server | `8081:8081` |
| `access-service` | 8091 | 9091 | 不暴露 |
| `knowledge-service` | 8092 | 9092 | 不暴露 |
| `rag-service` | 8082 | 9093 | `8082:8082`，仅兼容 AdminRag/UserQuery |
| `rag-service-smoke` | 8083 | 9094 | `8083:8083`，仅 `smoke` Profile |

- `console-api` readiness 依赖 Access、Knowledge、RAG Platform Probe。
- `open-api` readiness 依赖 Access、RAG Platform Probe。
- Console/Open 的 Probe 只表达下游 gRPC 通信、契约和身份可用性，不递归聚合下游数据库或 Sidecar 状态；HealthIndicator 名称固定为 `downstreamConnectivity`。
- Console/Open 并行执行 Probe，每个目标独立 `2s` deadline，整次检查总预算不超过 `3s`；任一必需目标失败则 readiness DOWN，对外只暴露失败目标名称和安全状态。
- Console/Open 各自声明有界、Spring 管理的 `ThreadPoolTaskExecutor`：Console 核心/最大线程数 3，Open 为 2，队列容量 0，使用明确线程名前缀，Context 关闭时终止；禁止公共 ForkJoinPool、`parallelStream()` 或 Runtime 内置共享执行器。
- Access/Knowledge readiness 包含 DataSource 与 `current_schema()`；RAG readiness 包含这两项并保留现有 Sidecar 可达性约束。
- Access、Knowledge、RAG 的 HTTP/管理和 gRPC 端口不映射宿主机；数据库不配置 `ports`。Sidecar 保留 `8001:8001` 作为本地诊断和现有回归入口。
- 通用 `docker/java-service.Dockerfile` 只接收 `SERVICE_MODULE`；构建阶段执行 `:<module>:bootJar`，运行阶段复制该模块 `build/libs/*.jar`。每个模块禁用 plain Jar并保证目录只有一个 Jar，避免模块名与 Jar 名双参数漂移。
- 运行镜像继续使用 JRE 21、非 root 用户和 `curl`。
- 每个 Boot Application 禁用 plain Jar并固定唯一产物名：
  - `crag-console-api.jar`
  - `crag-open-api.jar`
  - `crag-access-service.jar`
  - `crag-knowledge-service.jar`
  - `crag-rag-service.jar`

### 兼容与提交

- 现有 HTTP 回归改为显式使用 `CRAG_RAG_BASE_URL=http://localhost:8082`；不得误打到尚无业务端点的 Console API。
- `docker compose up -d --build` 是默认完整平台启动入口，不使用 Compose Profile 隐藏五个目标进程。
- Smoke 继续通过 `docker compose --profile smoke up -d --build rag-service-smoke` 启动。
- 五个任务分别创建实现提交，不共享提交；执行失败时按 `14.5 → 14.1` 逆序撤销。

## 未决问题

无。后续领域 RPC、Snowflake、Redis、业务表和最终 HTTP 契约由各自 Plan 决定，不阻塞本计划骨架执行。

## 风险与回滚

- 五个 Application 模块可能被宽泛 `@ComponentScan` 互相污染：每个组合根必须列出精确扫描包，组件测试断言 Access/Knowledge/API Context 不出现 RAG Controller、Repository 或定时任务。
- 直接使用 grpc-java 生命周期可能发生端口占用或 Netty 线程泄漏：身份、Metadata、deadline 使用 in-process transport；Server 随机端口绑定与 Channel 关闭使用 loopback 真实传输，关闭 Context 后在限定时间内断言资源终止。
- 静态 token 配置若被日志输出会泄密：配置对象禁止 `toString()` 输出 token，异常和日志测试检索完整测试 token 不得出现。
- 数据库初始化权限错误可能导致某服务访问其他 Schema：Docker 回归分别使用三个账号执行允许和拒绝 SQL，任何跨 Schema SELECT/CREATE 成功都阻断验收。
- 新 `pgdata-platform` 会形成第二份本地数据库目录：`.gitignore` 与 `.dockerignore` 必须覆盖整个 `data/`；普通 Compose down 不删除数据。
- RAG 组合根迁移可能造成 Bean 扫描、Schema、端口或脚本退化：迁移提交必须先通过全部 Gradle 测试，再由 AdminRag、UserQuery、Smoke、Retrieval HTTP 回归证明兼容。
- 通用 Dockerfile 可能因模块参数或缓存复制遗漏导致某个 Jar 不可构建：Docker 回归必须构建五个镜像并核对每个容器的实际 Jar 与非 root 用户。
- 整体回滚按任务逆序撤销，恢复 `crag-app`、旧 Dockerfile、旧 Compose 和旧 `data/pgdata` 配置。新 `data/pgdata-platform` 不自动删除；确认不再需要后由用户手工归档或删除。
- 本计划不迁移业务数据，因此回滚不需要数据反向转换；测试产生的新平台数据只保留在 `data/pgdata-platform`。

## 测试与验证计划

- 纯单元测试：
  - `crag-grpc-runtime` 覆盖合法身份、缺失 Metadata、未知 caller、错误 token、固定时长比较入口、deadline 和关闭行为。
  - Runtime 测试使用测试专属 `BindableService` 覆盖认证、deadline、匿名 Health 和生命周期；生成 Probe 契约的组合验证由 Contracts 或 Application 模块承担。
  - 数据库初始化脚本解析测试覆盖 `extensions`、三个 Schema owner、三个账号、临时 `CREATE` 授予/撤销、默认 `search_path` 和密码变量引用。
- 轻量组件测试：
  - 五个 Application Context 分别启动；API Context 无 DataSource，Access/Knowledge 无 RAG Bean，RAG 保持现有 JPA/Scheduling/API Bean。
  - Console/Open Probe HealthIndicator 使用生成 Stub 的测试替身验证并行成功、单目标失败、超时、`UNAUTHENTICATED`、总预算和安全详情。
  - gRPC Runtime 使用 in-process Server/Channel 验证受保护 Probe、匿名 Health 和 deadline；使用 loopback 随机端口验证真实 Server/Channel 生命周期。
- 架构测试：
  - Application 模块不被业务模块依赖。
  - Console/Open 不依赖现有 RAG 业务模块。
  - Access/Knowledge 不依赖 Storage/Ingestion/Retrieval/Query/API。
  - RAG Service 仅作为组合根装配现有 RAG 模块。
  - `crag-probe-contracts` 无 Spring、Runtime 或业务依赖；`crag-grpc-runtime` 无 Contracts 或业务依赖；业务模块不得依赖 Runtime/Application。
- Gradle 与静态验证：
  - `./gradlew :crag-probe-contracts:generateProto`
  - `./gradlew test`
  - `./gradlew check`
  - `python3 scripts/validate_module_dependencies.py`
  - `python3 scripts/validate_constraints.py`
  - `python3 scripts/validate_plans.py --strict --verify-git`
  - `git diff --check`
- Docker HTTP 与拓扑回归：
  1. `docker compose config`，确认默认包含五个 Java 服务且内部端口没有宿主机映射。
  2. `docker compose up -d --build`，等待 `db`、`sidecar`、`access-service`、`knowledge-service`、`rag-service`、`console-api`、`open-api` 全部 healthy。
  3. 执行 `scripts/tests/http/platform_topology_test.sh`，验证五进程、readiness、gRPC 身份成功/失败和数据库跨 Schema 拒绝。
  4. 设置 `CRAG_RAG_BASE_URL=http://localhost:8082`，执行 AdminRag、UserQuery Stub 和默认 Smoke 回归。
  5. `docker compose --profile smoke up -d --build rag-service-smoke`，以 `CRAG_RAG_BASE_URL=http://localhost:8083` 执行 Smoke 与 Retrieval Evidence 回归。
  6. 检查五个 Java 容器以非 root 用户运行，日志不含测试 token、数据库密码、Prompt 或完整文档。
  7. 仅执行普通 `docker compose --profile smoke down`，不删除 Volume 或 `data/pgdata-platform`。
- 每次写入回归继续使用唯一 runId；本计划没有安全精确删除入口，不清空共享表。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 14.1 | 建立 Protobuf 契约与 gRPC 身份运行时 | ⏳ 待开始 | — | — |
| 14.2 | 迁移 RAG 组合根并建立 Access/Knowledge 服务 | ⏳ 待开始 | — | — |
| 14.3 | 建立 Console/Open API 与下游 Probe readiness | ⏳ 待开始 | — | — |
| 14.4 | 建立独立 Schema、通用镜像与五进程 Compose | ⏳ 待开始 | — | — |
| 14.5 | 收口回归、架构约束与项目文档 | ⏳ 待开始 | — | — |

整体进度：0 / 5（0%）

## 14.1 建立 Protobuf 契约与 gRPC 身份运行时

**目标**：提供可被五个 Application 复用的 gRPC 契约生成、标准健康检查、受保护 Platform Probe、调用方身份和资源关闭基线。  
**前置任务**：无  
**范围**：在 version catalog 固定 gRPC Java 1.82.0、Protobuf 35.1 与 Protobuf Gradle Plugin 0.10.0；创建 `crag-probe-contracts` 并生成 `PlatformProbeService` Java/Stub；创建协议无关 `crag-grpc-runtime`，实现显式 Server/Client 配置、`GrpcServerLifecycle`、`GrpcChannelFactory`、`GrpcCallerIdentity`、`GrpcCallerContext`、客户端 Metadata/deadline 拦截器、服务端身份拦截器、标准 Health、固定时长 token 比较和资源关闭；更新模块与框架依赖校验器及其单测。
**非目标**：不定义领域 RPC、事件信封、业务错误详情、重试、负载均衡、TLS/mTLS、Probe Client 包装或 Probe 服务实现；Contracts 不依赖 Spring/Runtime，Runtime 不依赖任何 Contracts。
**验收标准**：Proto 生成类位于约定 Java package；标准 Health 匿名返回 `SERVING`；合法 caller 调用 Probe 得到正确 `serviceName/callerService`；缺失、未知或错误身份返回 `UNAUTHENTICATED` 且响应和日志不含 token；所有调用有 deadline；关闭 Spring Context 后 Server 与 Channel 终止；模块依赖无环且基础模块不依赖任何业务/Application 模块。  
**验证方式**：运行 `./gradlew :crag-probe-contracts:generateProto`、`:crag-probe-contracts:test`、`:crag-grpc-runtime:test`、`python3 -m unittest scripts.tests.test_validate_module_dependencies scripts.tests.test_validate_framework_dependencies -v`、两个依赖校验器和 `./gradlew check`；检查测试报告无跳过。
**涉及文件**：`gradle/libs.versions.toml`、`settings.gradle.kts`、`build.gradle.kts`、`crag-probe-contracts/**`、`crag-grpc-runtime/**`、`scripts/validate_module_dependencies.py`、`scripts/tests/test_validate_module_dependencies.py`、`scripts/validate_framework_dependencies.py`、`scripts/tests/test_validate_framework_dependencies.py`

**接口产物**：

```java
public record GrpcCallerIdentity(String serviceName) {}

public interface GrpcCallerContext {
  GrpcCallerIdentity requireIdentity();
}

public interface GrpcChannelFactory {
  ManagedChannel create(String targetName, String target, boolean plaintext);
}
```

**实施步骤**：

- [ ] 先扩展两个 Python 校验器单测，断言新模块白名单、Contracts 禁止 Spring/Runtime 依赖、Runtime 禁止 Contracts/业务依赖；运行对应 unittest，预期因模块尚未登记而失败。
- [ ] 在 version catalog 和根构建中加入固定版本与 Protobuf 插件，在 `settings.gradle.kts` 登记两个模块；运行校验器单测，预期通过。
- [ ] 创建 `platform_probe.proto` 和 Contracts 构建文件；运行 `./gradlew :crag-probe-contracts:generateProto`，预期生成 `PlatformProbeServiceGrpc`、请求和响应类型且无 Spring 类路径。
- [ ] 先编写认证拦截器、caller Context、配置绑定和 deadline 守卫的纯单元测试，覆盖合法/缺失/未知/错误凭据、空配置、无 deadline、超过 10 秒和日志脱敏；运行 `:crag-grpc-runtime:test`，预期测试因生产类型不存在而失败。
- [ ] 实现最小 Runtime 类型，使上述单元测试通过；token 使用 UTF-8 字节与固定时长比较，认证结果只写入 `io.grpc.Context`。
- [ ] 增加 in-process 测试 Application，使用 Runtime 测试源码内的专属 `BindableService` 验证匿名 Health 和受认证调用；Runtime 的主代码与测试代码均不得依赖 Probe 生成类。
- [ ] 增加 loopback 端口 `0` 生命周期测试，验证 `SERVING → NOT_SERVING`、Spring Context 关闭后 Server/Channel 在配置超时内终止。
- [ ] 运行本任务全部验证；创建实现提交 `feat(plan_14/14.1): add probe contracts and grpc runtime`。

## 14.2 迁移 RAG 组合根并建立 Access/Knowledge 服务

**目标**：用三个职责隔离的业务服务组合根替代唯一 `crag-app`，并保持现有 RAG 运行时完整可测试。  
**前置任务**：14.1  
**范围**：创建 `crag-access-service`、`crag-knowledge-service`、`crag-rag-service`；将 `CragDemoApplication`、RAG application 配置、Schema、测试资源、健康测试和 ArchUnit 测试迁入 RAG Service；将包名改为 `ai.cerbur.crag.rag.app`；精确配置 RAG 对 Storage、Ingestion、Retrieval、Query、兼容 API 和 Smoke 的扫描与装配；Access/Knowledge 只扫描自身并显式导入 gRPC Server；三个服务各自在组合根实现 Probe，读取 `GrpcCallerContext`；固定三个 Boot Jar；删除 `crag-app`。
**非目标**：不增加 Access/Knowledge 领域类或表；不迁移 `crag-api` Controller；不改变 RAG Schema 字段、算法、业务配置默认值或 HTTP DTO；不让 Access/Knowledge 引用现有业务模块。  
**验收标准**：仓库不再包含 `crag-app`；三个 Boot Application 独立构建；RAG Context 包含既有 Controller、Repository、Cron 和 Query Bean；Access/Knowledge Context 不包含这些 Bean；三个应用报告不同 `spring.application.name`；RAG 组件与架构测试迁移后全绿；每个模块仅生成一个固定名称 Boot Jar。  
**验证方式**：运行三个模块的 `bootJar` 和组件测试；运行 `./gradlew :crag-rag-service:test --tests '*ArchitectureTest'`、`./gradlew test`、模块依赖校验与 `./gradlew check`；使用 `jar tf` 核对 RAG Jar 含既有业务类而 Access/Knowledge Jar 不含。  
**涉及文件**：`settings.gradle.kts`、`crag-access-service/**`、`crag-knowledge-service/**`、`crag-rag-service/**`、`crag-app/**`（删除）、`crag-api/**`（仅测试配置引用迁移）、`crag-smoke/**`（仅装配引用迁移）、`scripts/validate_module_dependencies.py`、`scripts/tests/test_validate_module_dependencies.py`

**接口产物**：

- 三个服务都提供生成的 `PlatformProbeServiceGrpc.PlatformProbeServiceImplBase` Bean。
- Probe 响应中的 `serviceName` 分别严格为 `access-service`、`knowledge-service`、`rag-service`，`callerService` 来自 `GrpcCallerContext.requireIdentity()`。
- Access/Knowledge 无出站 Channel；RAG 在本计划也不创建虚假的 Knowledge Client。

**实施步骤**：

- [ ] 先新增三个 Application Context 组件测试和模块依赖校验用例，断言规范服务名、精确 Bean 边界、仅 Server 配置启用和单一 Boot Jar；运行测试，预期因模块不存在而失败。
- [ ] 创建 Access/Knowledge 最小组合根、build 与 application 配置，显式导入 `GrpcServerConfiguration`，实现各自 Probe；不添加 Controller、Repository、空 schema 或出站 Channel。
- [ ] 复制后再迁移 `crag-app` 的组合根、资源和测试到 `crag-rag-service`，先保持业务模块依赖与配置值不变，再将规范服务名、端口和包名改为 RAG 所有。
- [ ] 更新 ArchUnit 和模块校验规则，断言业务模块不依赖 Runtime/Application，Access/Knowledge 不依赖现有 RAG 业务模块。
- [ ] 删除 `crag-app` 并从 settings、Docker 构建引用和测试路径中移除；检索 `crag-app` 只允许出现在历史 Plan/归档文字中。
- [ ] 运行三个模块组件测试、`bootJar`、全量 `./gradlew test` 和架构测试；用 `jar tf` 核对内容边界。
- [ ] 创建实现提交 `refactor(plan_14/14.2): split rag access and knowledge applications`。

## 14.3 建立 Console/Open API 与下游 Probe readiness

**目标**：建立两个独立 HTTP 入口组合根，并用真实受身份保护的 gRPC Probe 表达其下游就绪依赖。  
**前置任务**：14.2  
**范围**：创建 `crag-console-api` 与 `crag-open-api` Boot Application、配置和组件测试；两者只依赖 WebMVC、Actuator、Probe Contracts 和 gRPC Runtime；Console 配置 Access/Knowledge/RAG 三个 Probe Target，Open 配置 Access/RAG 两个 Target；显式创建命名 Channel/Stub；使用独立有界执行器并行执行 `2s` Probe，在 `3s` 总预算内形成 `downstreamConnectivity` HealthIndicator；固定两个 Boot Jar。
**非目标**：不创建注册、登录、KnowledgeBase、上传、API Key 或 Query Controller；不代理现有 RAG HTTP 接口；不连接数据库；不通过 Gradle project 依赖调用任何业务服务 Application。  
**验收标准**：两个 Context 无 DataSource/JPA/Repository/RAG 业务 Bean；下游全部通过时 readiness 为 UP；任一必需目标失败、超时或身份错误时 readiness 为 DOWN 且不泄漏 token；三个 Console Probe 并行而非串行，最慢失败不把检查拖到 6 秒；Console/Open 使用不同 caller 名称和 token；两个 Boot Jar 可独立构建。
**验证方式**：运行 `:crag-console-api:test`、`:crag-open-api:test`、两个模块 `bootJar`、架构测试、模块依赖校验、`./gradlew test` 和 `./gradlew check`；核对测试报告覆盖成功、超时和鉴权失败。  
**涉及文件**：`settings.gradle.kts`、`crag-console-api/**`、`crag-open-api/**`、`crag-rag-service/src/test/**`（架构规则）、`scripts/validate_module_dependencies.py`、`scripts/tests/test_validate_module_dependencies.py`

**接口产物**：

```yaml
crag:
  grpc:
    client:
      caller-service: console-api
      token: ${CONSOLE_API_SERVICE_TOKEN}
      max-deadline: 10s
    probe:
      targets:
        access-service: access-service:9091
        knowledge-service: knowledge-service:9092
        rag-service: rag-service:9093
```

Open API 使用同一结构但只包含 Access/RAG，并把 caller 固定为 `open-api`。

**实施步骤**：

- [ ] 先编写两个 Context 组件测试和 Probe HealthIndicator 单元测试，覆盖全成功、单目标失败、`UNAUTHENTICATED`、2 秒 deadline、3 秒总预算、并行执行和安全 details；运行模块测试，预期因模块/类型不存在而失败。
- [ ] 创建两个最小 Web/Actuator 组合根，只显式导入 `GrpcClientConfiguration`，不声明 DataSource 或业务 Controller。
- [ ] 为每个真实 target 显式创建 Channel 和 Blocking Stub；Stub 调用必须使用 `withDeadlineAfter(2, SECONDS)`，不得在 Runtime 中补默认 deadline。
- [ ] 创建 Console 3 线程、Open 2 线程的 `ThreadPoolTaskExecutor`，队列容量 0并设置线程名前缀；HealthIndicator 并行提交目标检查，在总预算到期时取消未完成任务并返回 DOWN。
- [ ] 把 `downstreamConnectivity` 加入两个 API 的 readiness group；Health details 只含 target 名称与 `UP/DOWN/TIMEOUT/UNAUTHENTICATED` 安全分类。
- [ ] 运行两个模块测试、Boot Jar、模块依赖与全量 Gradle 验证；创建实现提交 `feat(plan_14/14.3): add console and open api probe readiness`。

## 14.4 建立独立 Schema、通用镜像与五进程 Compose

**目标**：在真实 PostgreSQL、Sidecar 和 Docker 网络中启动五个 Java 进程，并证明服务账号、Schema、身份和启动依赖隔离。  
**前置任务**：14.3  
**范围**：通过 PostgreSQL 镜像的 `POSTGRES_DB=crag_platform` 创建数据库；创建受控 Shell 初始化脚本，在该数据库内建立 `extensions`、三个独立密码账号、自有 Schema、默认 search_path 和最小权限；为 Access/Knowledge/RAG 配置独立 DataSource，Access/Knowledge 不启用 SQL 初始化，RAG Schema 删除扩展创建语句并保持三张业务表不变；为三个服务增加 `current_schema()` readiness；创建单参数 `docker/java-service.Dockerfile`，删除旧 Dockerfile；重写 Compose 服务、端口、健康检查、调用方凭据、依赖顺序和 `pgdata-platform` 挂载；保留 model-init/sidecar 与 Sidecar 8001；将 app-smoke 改为 `rag-service-smoke`。
**非目标**：不增加 Redis；不把内部 gRPC/Actuator 端口暴露到宿主机；不创建业务表；不迁移旧数据库；不改变 Sidecar 模型或协议；不把 Demo token 描述为生产 Secret 方案。  
**验收标准**：默认 Compose 包含五个目标 Java 服务且全部 healthy；Console/Open readiness 通过受保护 Probe；错误 token 使目标 Probe 返回 `UNAUTHENTICATED`；三个数据库账号分别拥有自身 Schema且只能在自身 Schema 建表/查询；管理员密码不出现在 Java 容器；RAG 旧 HTTP 入口在 8082 可用；所有 Java 容器使用非 root 用户和正确 Jar；普通 down 后 `pgdata-platform` 保留；旧 `data/pgdata` 未修改。
**验证方式**：运行五个 `bootJar`、`docker compose config`、`docker compose up -d --build`；检查 `docker compose ps`、容器用户、内部端口和健康状态；通过 `psql` 分别执行同 Schema 成功与跨 Schema 失败断言；调用 Console/Open/RAG readiness；传入错误 token 执行 Probe 失败断言；最后普通 `docker compose down`。  
**涉及文件**：`docker/postgres/init/001-platform.sh`、`docker/java-service.Dockerfile`、`Dockerfile`（删除）、`docker-compose.yml`、`.dockerignore`、`.env.example`、`crag-access-service/src/main/resources/**`、`crag-knowledge-service/src/main/resources/**`、`crag-rag-service/src/main/resources/**`、五个 Application `build.gradle.kts`

**配置产物**：

```text
access-service    → jdbc:postgresql://db:5432/crag_platform?currentSchema=access
knowledge-service → jdbc:postgresql://db:5432/crag_platform?currentSchema=knowledge
rag-service       → jdbc:postgresql://db:5432/crag_platform?currentSchema=rag,extensions
```

**实施步骤**：

- [ ] 先扩展约束/脚本单测，断言初始化脚本引用三个独立密码变量、创建 `extensions`、设置 owner/search_path、撤销临时权限，Compose 不暴露数据库和内部服务端口且使用 `data/pgdata-platform/`；运行 unittest，预期失败。
- [ ] 在 Compose 中设置 `POSTGRES_DB=crag_platform`；实现 `001-platform.sh`，只在当前数据库内由管理员创建扩展 Schema和扩展、创建三角色、临时授予数据库 `CREATE`、分别 `SET ROLE` 创建自有 Schema、撤销数据库 `CREATE` 与 public `CREATE`，并设置默认 search_path和最小权限；脚本不得执行 `CREATE DATABASE`。
- [ ] 配置三个独立 DataSource；Access/Knowledge 禁用 SQL init，RAG 的 `schema.sql` 删除 `CREATE EXTENSION` 并保持现有表、索引和 UUID 字段不变。
- [ ] 为三个服务实现 `ExpectedSchemaHealthIndicator`，查询 `SELECT current_schema()`；加入 readiness，Schema 不符时 DOWN且不尝试修复。
- [ ] 创建只接受 `SERVICE_MODULE` 的通用 Dockerfile并删除旧 Dockerfile；逐个运行五个 `bootJar`，断言各模块 `build/libs` 只有目标 Boot Jar。
- [ ] 重写 Compose：默认五个 Java 服务；数据库无宿主机端口；内部服务无 `ports`；Sidecar 保留 8001；RAG 兼容入口 8082；Smoke 8083仅 Profile；通过各服务 `SPRING_APPLICATION_JSON` 注入结构化 allowed-callers。
- [ ] 运行 `docker compose config` 和五镜像构建；启动平台，检查全部 healthy、Java 容器非 root、管理员凭据未注入 Java 容器、普通 down 保留新目录且旧目录无变更。
- [ ] 创建实现提交 `build(plan_14/14.4): add isolated schemas and five-service compose`。

## 14.5 收口回归、架构约束与项目文档

**目标**：用自动化回归和项目级事实文档固定多服务基线，为 plan_15 及后续领域 Plan 提供可信起点。  
**前置任务**：14.4  
**范围**：新增 `platform_topology_test.sh`；将既有 HTTP 脚本的业务入口统一改为 `CRAG_RAG_BASE_URL` 且默认 8082，Smoke Profile 使用 8083；更新 readiness 脚本的服务名、端口、故障恢复和日志收集；执行 AdminRag、UserQuery Stub、Smoke、Retrieval Evidence 与平台拓扑全套回归；更新包结构、Docker、API、持久化、测试约束、README、方向归档和索引；增强约束校验器以核对五个默认 Java 服务和内部端口不对宿主机暴露；完成全量静态检查。  
**非目标**：不修改业务行为来迎合脚本；不复制 Plan 任务到索引；不把未实现的领域模块写入当前实现索引；不创建 plan_15；不执行真实 DeepSeek 条件验收，因为本计划不修改供应商边界。  
**验收标准**：平台拓扑脚本可重复运行并以非零退出表达失败；现有稳定 RAG 回归全部通过且数据含唯一 runId；约束当前实现索引与源码、Compose 一致；README 明确五进程启动方式和 8082 兼容入口；所有校验、Gradle 测试和 Docker 回归无跳过；Plan/index 状态与真实进度一致。  
**验证方式**：运行 `platform_topology_test.sh`、AdminRag 契约、Query Stub 成功/失败、默认 Smoke、Smoke Profile、Retrieval Evidence、`./gradlew check`、三个 Python 校验器及其单测、`python3 scripts/validate_plans.py --strict --verify-git`、`git diff --check`；检索 `crag-app`、旧 Compose 服务名、旧端口和未登记模块残留。  
**涉及文件**：`scripts/tests/http/**`、`scripts/validate_constraints.py`、`scripts/tests/test_validate_constraints.py`、`constraints/package-structure.md`、`constraints/docker-structure.md`、`constraints/api-style.md`、`constraints/persistence-style.md`、`constraints/test-workflow.md`、`README.md`、`plan/plan_main.md`、`plan/plan_archive/2026-06-22-multi-tenant-knowledge-platform-direction.md`、`plan/plan_14/plan_14.md`、`plan/index/README.md`

**实施步骤**：

- [ ] 先扩展 `test_validate_constraints.py`，断言五个默认 Java 服务、Sidecar 8001、数据库/内部端口不暴露、模块与约束索引一致；运行 unittest，预期在文档和校验器更新前失败。
- [ ] 新增 `platform_topology_test.sh`，分阶段断言七个长期服务健康、Console/Open readiness、规范服务名、合法/错误身份、Schema owner、同 Schema 成功、跨 Schema SELECT/CREATE 失败、管理员凭据隔离、非 root 和日志脱敏；所有失败返回非零退出码。
- [ ] 将现有 HTTP 脚本统一改用 `CRAG_RAG_BASE_URL`，默认 8082；Smoke Profile调用者显式传 8083；每个写入脚本生成并传播唯一 `runId`。
- [ ] 更新 readiness 脚本，验证停止单个下游时对应 API readiness DOWN、恢复服务后重新 UP，并收集失败目标日志但不输出 token或密码。
- [ ] 依次执行平台拓扑、AdminRag、Query Stub 成功/失败、默认 Smoke、Smoke Profile 和 Retrieval Evidence；任何失败先保留证据并修复，不以无修改重跑掩盖 flaky。
- [ ] 更新 package、Docker、API、persistence、test-workflow、README、方向归档、plan_main 和索引，使当前实现事实与源码/Compose一致；不得写入尚未实现的领域模块。
- [ ] 运行 `./gradlew check`、全部 Python 校验器与单测、严格 Plan 校验、`git diff --check` 和残留检索；确认无跳过、无占位、无旧运行入口。
- [ ] 创建实现提交 `test(plan_14/14.5): verify distributed platform baseline`；随后按执行 Skill 创建独立交接提交，回填五个真实实现 hash 并转入待验收。

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |

## 阻塞记录

无。发生阻塞时记录原因、当前进度、解除条件、解除方、下一步与日期。

## 废弃任务记录

无。任务废弃时记录原因、日期及替代任务或决策。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-22 | 创建计划并设为待开始 | 多租户平台总体设计已确认，需要先建立后续所有领域 Plan 依赖的进程、契约、身份和数据边界 | 执行队列新增 plan_14；实现前必须提交本计划与索引 |
| 2026-06-22 | 细化契约归属、Runtime 边界、身份、Probe、Schema、Docker 与逐任务实施步骤 | Grilling 逐项确认了 Contracts 按 Server 归属、`crag-probe-contracts` 窄例外、协议无关 Runtime、最小权限数据库和五进程验证策略 | 不改变五任务范围与状态；消除执行期架构决策，更新文件地图、接口、测试先行步骤和精确验收 |
