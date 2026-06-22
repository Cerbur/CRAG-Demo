---
workflow_version: 3
plan_id: plan_14
type: main
status: ready
created: 2026-06-22
updated: 2026-06-22
---

# plan_14 — 多服务骨架、gRPC 契约与数据边界基线

## 背景与目标

现有仓库由 `crag-app` 作为唯一 Spring Boot 组合根，将 HTTP API、Ingestion、Retrieval、Query 和 Storage 运行在同一个进程、端口和数据库账号中。多租户知识平台设计已经确定目标形态为 Console API、Open API、Access Service、Knowledge Service 和 RAG Service 五个独立进程；后续 Snowflake、事件基础设施、Knowledge、Access 和双 API 业务能力都依赖稳定的进程、契约、身份和数据边界。

本计划只建设分布式基础骨架：增加 Protobuf/gRPC 契约模块和内部调用身份机制；将现有完整 RAG 运行时迁移到 `crag-rag-service`；建立 Access、Knowledge、Console 和 Open 四个可独立启动的空业务组合根；使用独立 PostgreSQL Schema 与账号隔离 Access、Knowledge、RAG；重构 Docker Compose 为五进程拓扑；通过标准 gRPC Health、受身份保护的 Platform Probe、Actuator readiness 和现有 RAG HTTP 回归证明骨架可运行且旧链路未退化。

本计划完成后仍不提供注册、KnowledgeBase、文件上传或 API Key 业务。现有 AdminRag、UserQuery 和 Smoke 入口暂时继续由 `crag-rag-service` 承载，作为后续业务迁移前的兼容入口。

## 范围

- 新增 `crag-contracts`，统一生成 Java Protobuf 消息和 gRPC Stub。
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
- 使用新的 `data/platform-pgdata/` 保存平台数据库，保留旧 `data/pgdata/` 以支持非破坏性回滚。
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
- `crag-contracts/**`
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
- `docker/postgres/init/001-platform.sql`
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

## 关键决策

### 模块与进程

- 五个进程与 Gradle Application 模块一一对应，不再保留通用 `crag-app`。
- `crag-rag-service` 是现有 RAG 业务模块的组合根，直接装配 `crag-storage`、`crag-ingestion`、`crag-retrieval`、`crag-query`、兼容 `crag-api` 和运行时 `crag-smoke`。
- `crag-access-service` 与 `crag-knowledge-service` 在本计划只拥有组合根、独立 DataSource、Actuator、gRPC Server 和 Platform Probe，不包含未来领域代码。
- `crag-console-api` 与 `crag-open-api` 在本计划只拥有 Web/Actuator 组合根和下游 Probe HealthIndicator，不提供占位业务 Controller。
- 业务库模块不得依赖任何 Application 模块；Application 模块之间不得建立 Gradle project 依赖，只通过 `crag-contracts` 与 `crag-grpc-runtime` 通信。
- `crag-api` 的名称暂时保留，但其 Controller 只在 `crag-rag-service` 兼容运行时装配；后续双 API Plan 负责迁移并删除该旧边界。

### gRPC 契约

- Protobuf package 为 `crag.platform.v1`，Java package 为 `ai.cerbur.crag.contracts.platform.v1`。
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
- Proto 字段一经进入已完成 Plan 不重编号、不复用；删除字段必须 `reserved`。
- `crag-contracts` 不依赖 Spring、数据库或业务模块，只依赖 Protobuf/gRPC 生成运行时。

### gRPC 运行时接口

- `crag-grpc-runtime` 对 Application 模块提供以下稳定类型：

```java
public interface PlatformProbeClient {
  PlatformProbeResult check(String targetName, Duration deadline);
}

public record PlatformProbeResult(String serviceName, String callerService) {}

public interface GrpcCallerContext {
  String requireCallerService();
}
```

- Server 生命周期由 Spring 管理，使用 `SmartLifecycle` 启动和优雅关闭 `io.grpc.Server`；禁止生产代码直接创建线程池。
- Server 启动后将标准 Health Service 的整体状态设为 `SERVING`，关闭前设为 `NOT_SERVING`。
- 客户端 Channel 在 Bean 销毁时调用 `shutdown()` 并在受控超时后 `shutdownNow()`，不得泄漏 Netty 线程。
- 所有业务/Probe 调用必须设置 deadline；Platform Probe 默认 `2s`。

### 服务身份

- Metadata Key 固定为：
  - `x-crag-caller-service`
  - `x-crag-service-token`
- 客户端身份由 `crag.grpc.client.caller-service` 和 `crag.grpc.client.token` 配置。
- 服务端允许调用方由 `crag.grpc.server.allowed-callers` 配置，格式为逗号分隔的 `caller=token`。
- 缺少身份、未知调用方或 token 不匹配统一返回 gRPC `UNAUTHENTICATED`，不在错误详情中返回 token 或允许调用方列表。
- 比较 token 使用固定时长比较，日志最多记录 caller 名称，不记录完整 token。
- 标准 Health Service 允许匿名调用；Platform Probe 和后续领域 Service 默认全部受身份拦截器保护。
- Demo Compose 为 Console API 与 Open API 配置不同凭据。生产目标 mTLS 留给后续独立 Plan。

### 数据隔离

- 单个 PostgreSQL 实例内建立数据库 `crag_platform`。
- Schema 与账号固定为：

| 服务 | Schema | 账号 |
| --- | --- | --- |
| Access | `access` | `crag_access` |
| Knowledge | `knowledge` | `crag_knowledge` |
| RAG | `rag` | `crag_rag` |

- 每个账号只能 `USAGE/CREATE` 自己的 Schema，不拥有其他业务 Schema 权限；公共 Schema 不授予业务表创建权限。
- `vector` 与 `pg_trgm` 由数据库初始化管理员创建，RAG 账号不负责创建扩展。
- JDBC URL 必须显式携带 `currentSchema`；应用 Schema 脚本不得使用跨 Schema 限定名。
- Access、Knowledge 在本计划的 `schema.sql` 使用无副作用的 `SELECT 1 WHERE FALSE`，只证明独立初始化入口；后续领域 Plan 负责增加表。
- RAG 将现有三张表迁入 `rag` Schema，表结构和 UUID 字段在本计划保持不变。
- 新平台使用 `data/platform-pgdata/`；旧 `data/pgdata/` 不读取、不修改、不删除。

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
- Access/Knowledge readiness 包含自身 DataSource；RAG readiness 包含 DataSource 与 Sidecar 可达性沿用现有业务启动约束。
- Access、Knowledge、RAG 和 Sidecar 只在 Compose 私有网络被 API 进程访问；内部端口不得配置 `ports`。
- 通用 `docker/java-service.Dockerfile` 通过 `SERVICE_MODULE` 与 `JAR_NAME` 构建指定 Boot Jar，运行镜像继续使用 JRE 21、非 root 用户和 `curl`。
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
- 直接使用 grpc-java 生命周期可能发生端口占用或 Netty 线程泄漏：组件测试使用随机端口或 in-process transport，关闭 Context 后断言 Server/Channel 终止。
- 静态 token 配置若被日志输出会泄密：配置对象禁止 `toString()` 输出 token，异常和日志测试检索完整测试 token 不得出现。
- 数据库初始化权限错误可能导致某服务访问其他 Schema：Docker 回归分别使用三个账号执行允许和拒绝 SQL，任何跨 Schema SELECT/CREATE 成功都阻断验收。
- 新 `platform-pgdata` 会形成第二份本地数据库目录：`.gitignore` 与 `.dockerignore` 必须覆盖整个 `data/`；普通 Compose down 不删除数据。
- RAG 组合根迁移可能造成 Bean 扫描、Schema、端口或脚本退化：迁移提交必须先通过全部 Gradle 测试，再由 AdminRag、UserQuery、Smoke、Retrieval HTTP 回归证明兼容。
- 通用 Dockerfile 可能因模块参数或缓存复制遗漏导致某个 Jar 不可构建：Docker 回归必须构建五个镜像并核对每个容器的实际 Jar 与非 root 用户。
- 整体回滚按任务逆序撤销，恢复 `crag-app`、旧 Dockerfile、旧 Compose 和旧 `data/pgdata` 配置。新 `data/platform-pgdata` 不自动删除；确认不再需要后由用户手工归档或删除。
- 本计划不迁移业务数据，因此回滚不需要数据反向转换；测试产生的新平台数据只保留在 `data/platform-pgdata`。

## 测试与验证计划

- 纯单元测试：
  - `crag-grpc-runtime` 覆盖合法身份、缺失 Metadata、未知 caller、错误 token、固定时长比较入口、deadline 和关闭行为。
  - Platform Probe 覆盖响应中的服务端名称与认证 caller。
  - 数据库初始化脚本解析测试覆盖三个 Schema、三个账号、权限授予和跨 Schema 禁止项。
- 轻量组件测试：
  - 五个 Application Context 分别启动；API Context 无 DataSource，Access/Knowledge 无 RAG Bean，RAG 保持现有 JPA/Scheduling/API Bean。
  - Console/Open Probe HealthIndicator 使用 Stub Client 验证 UP/DOWN 与 deadline。
  - gRPC Runtime 使用 in-process Server/Channel 验证受保护 Probe 和匿名 Health。
- 架构测试：
  - Application 模块不被业务模块依赖。
  - Console/Open 不依赖现有 RAG 业务模块。
  - Access/Knowledge 不依赖 Storage/Ingestion/Retrieval/Query/API。
  - RAG Service 仅作为组合根装配现有 RAG 模块。
  - `crag-contracts` 无 Spring/业务依赖，业务模块不得依赖 Application 模块。
- Gradle 与静态验证：
  - `./gradlew :crag-contracts:generateProto`
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
  7. 仅执行普通 `docker compose --profile smoke down`，不删除 Volume 或 `data/platform-pgdata`。
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
**范围**：在 version catalog 固定 gRPC Java 1.82.0、Protobuf 35.1 与 Protobuf Gradle Plugin 0.10.0；创建 `crag-contracts` 并生成 `PlatformProbeService` Java/Stub；创建 `crag-grpc-runtime`，实现 `GrpcServerLifecycle`、`PlatformProbeClient`、`PlatformProbeResult`、`GrpcCallerContext`、客户端 Metadata Interceptor、服务端身份 Interceptor、标准 Health Service 和 Platform Probe；增加配置绑定、固定时长 token 比较、deadline、优雅关闭和 in-process 测试；更新模块依赖校验器及其单测以登记两个基础模块。  
**非目标**：不定义领域 RPC、事件信封、业务错误详情、重试、负载均衡、TLS/mTLS 或 Spring 业务组件；不让 `crag-contracts` 依赖 Spring。  
**验收标准**：Proto 生成类位于约定 Java package；标准 Health 匿名返回 `SERVING`；合法 caller 调用 Probe 得到正确 `serviceName/callerService`；缺失、未知或错误身份返回 `UNAUTHENTICATED` 且响应和日志不含 token；所有调用有 deadline；关闭 Spring Context 后 Server 与 Channel 终止；模块依赖无环且基础模块不依赖任何业务/Application 模块。  
**验证方式**：运行 `./gradlew :crag-contracts:generateProto`、`:crag-contracts:test`、`:crag-grpc-runtime:test`、`python3 -m unittest scripts.tests.test_validate_module_dependencies -v`、`python3 scripts/validate_module_dependencies.py` 和 `./gradlew check`；检查生成源码和测试报告无跳过。  
**涉及文件**：`gradle/libs.versions.toml`、`settings.gradle.kts`、`build.gradle.kts`、`crag-contracts/**`、`crag-grpc-runtime/**`、`scripts/validate_module_dependencies.py`、`scripts/tests/test_validate_module_dependencies.py`、`scripts/validate_framework_dependencies.py`、`scripts/tests/test_validate_framework_dependencies.py`

## 14.2 迁移 RAG 组合根并建立 Access/Knowledge 服务

**目标**：用三个职责隔离的业务服务组合根替代唯一 `crag-app`，并保持现有 RAG 运行时完整可测试。  
**前置任务**：14.1  
**范围**：创建 `crag-access-service`、`crag-knowledge-service`、`crag-rag-service`；将 `CragDemoApplication`、RAG application 配置、Schema、测试资源、健康测试和 ArchUnit 测试迁入 RAG Service；将包名改为 `ai.cerbur.crag.rag.app`；精确配置 RAG 对 Storage、Ingestion、Retrieval、Query、兼容 API 和 Smoke 的扫描与装配；Access/Knowledge 只扫描自身与 gRPC Runtime；三个服务均启用 Actuator、标准 gRPC Health 和受保护 Probe；固定三个 Boot Jar；删除 `crag-app`。  
**非目标**：不增加 Access/Knowledge 领域类或表；不迁移 `crag-api` Controller；不改变 RAG Schema 字段、算法、业务配置默认值或 HTTP DTO；不让 Access/Knowledge 引用现有业务模块。  
**验收标准**：仓库不再包含 `crag-app`；三个 Boot Application 独立构建；RAG Context 包含既有 Controller、Repository、Cron 和 Query Bean；Access/Knowledge Context 不包含这些 Bean；三个应用报告不同 `spring.application.name`；RAG 组件与架构测试迁移后全绿；每个模块仅生成一个固定名称 Boot Jar。  
**验证方式**：运行三个模块的 `bootJar` 和组件测试；运行 `./gradlew :crag-rag-service:test --tests '*ArchitectureTest'`、`./gradlew test`、模块依赖校验与 `./gradlew check`；使用 `jar tf` 核对 RAG Jar 含既有业务类而 Access/Knowledge Jar 不含。  
**涉及文件**：`settings.gradle.kts`、`crag-access-service/**`、`crag-knowledge-service/**`、`crag-rag-service/**`、`crag-app/**`（删除）、`crag-api/**`（仅测试配置引用迁移）、`crag-smoke/**`（仅装配引用迁移）、`scripts/validate_module_dependencies.py`、`scripts/tests/test_validate_module_dependencies.py`

## 14.3 建立 Console/Open API 与下游 Probe readiness

**目标**：建立两个独立 HTTP 入口组合根，并用真实受身份保护的 gRPC Probe 表达其下游就绪依赖。  
**前置任务**：14.2  
**范围**：创建 `crag-console-api` 与 `crag-open-api` Boot Application、配置和组件测试；两者只依赖 WebMVC、Actuator、Contracts 和 gRPC Runtime；Console 配置 Access/Knowledge/RAG 三个 Probe Target，Open 配置 Access/RAG 两个 Target；实现独立 HealthIndicator 聚合下游 Probe，设置 2 秒 deadline；固定两个 Boot Jar；测试下游全部成功、超时、`UNAUTHENTICATED` 和单目标失败时的 readiness。  
**非目标**：不创建注册、登录、KnowledgeBase、上传、API Key 或 Query Controller；不代理现有 RAG HTTP 接口；不连接数据库；不通过 Gradle project 依赖调用任何业务服务 Application。  
**验收标准**：两个 Context 无 DataSource/JPA/Repository/RAG 业务 Bean；下游全部通过时 readiness 为 UP；任一必需目标失败或身份错误时 readiness 为 DOWN 且不泄漏 token；Console/Open 使用不同 caller 名称和 token；两个 Boot Jar 可独立构建。  
**验证方式**：运行 `:crag-console-api:test`、`:crag-open-api:test`、两个模块 `bootJar`、架构测试、模块依赖校验、`./gradlew test` 和 `./gradlew check`；核对测试报告覆盖成功、超时和鉴权失败。  
**涉及文件**：`settings.gradle.kts`、`crag-console-api/**`、`crag-open-api/**`、`crag-rag-service/src/test/**`（架构规则）、`scripts/validate_module_dependencies.py`、`scripts/tests/test_validate_module_dependencies.py`

## 14.4 建立独立 Schema、通用镜像与五进程 Compose

**目标**：在真实 PostgreSQL、Sidecar 和 Docker 网络中启动五个 Java 进程，并证明服务账号、Schema、身份和启动依赖隔离。  
**前置任务**：14.3  
**范围**：创建数据库初始化脚本，建立 `crag_platform` 的扩展、三个 Schema、三个账号和最小权限；为 Access/Knowledge/RAG 配置独立 DataSource 与 SQL 初始化；RAG Schema 删除扩展创建语句并保持三张业务表不变；创建参数化 `docker/java-service.Dockerfile`，删除旧 Dockerfile；重写 Compose 服务、端口、健康检查、调用方凭据、依赖顺序和 `platform-pgdata` 挂载；保留 model-init/sidecar；将 app-smoke 改为 `rag-service-smoke`；同步 `.env.example`、`.dockerignore` 和必要构建缓存复制清单。  
**非目标**：不增加 Redis；不把内部 gRPC/Actuator 端口暴露到宿主机；不创建业务表；不迁移旧数据库；不改变 Sidecar 模型或协议；不把 Demo token 描述为生产 Secret 方案。  
**验收标准**：默认 Compose 包含五个目标 Java 服务且全部 healthy；Console/Open readiness 通过受保护 Probe；错误 token 使目标 Probe 返回 `UNAUTHENTICATED`；三个数据库账号只能在自己 Schema 建表/查询；RAG 旧 HTTP 入口在 8082 可用；所有 Java 容器使用非 root 用户和正确 Jar；普通 down 后 `platform-pgdata` 保留；旧 `data/pgdata` 未修改。  
**验证方式**：运行五个 `bootJar`、`docker compose config`、`docker compose up -d --build`；检查 `docker compose ps`、容器用户、内部端口和健康状态；通过 `psql` 分别执行同 Schema 成功与跨 Schema 失败断言；调用 Console/Open/RAG readiness；传入错误 token 执行 Probe 失败断言；最后普通 `docker compose down`。  
**涉及文件**：`docker/postgres/init/001-platform.sql`、`docker/java-service.Dockerfile`、`Dockerfile`（删除）、`docker-compose.yml`、`.dockerignore`、`.env.example`、`crag-access-service/src/main/resources/**`、`crag-knowledge-service/src/main/resources/**`、`crag-rag-service/src/main/resources/**`、五个 Application `build.gradle.kts`

## 14.5 收口回归、架构约束与项目文档

**目标**：用自动化回归和项目级事实文档固定多服务基线，为 plan_15 及后续领域 Plan 提供可信起点。  
**前置任务**：14.4  
**范围**：新增 `platform_topology_test.sh`；将既有 HTTP 脚本的业务入口统一改为 `CRAG_RAG_BASE_URL` 且默认 8082，Smoke Profile 使用 8083；更新 readiness 脚本的服务名、端口、故障恢复和日志收集；执行 AdminRag、UserQuery Stub、Smoke、Retrieval Evidence 与平台拓扑全套回归；更新包结构、Docker、API、持久化、测试约束、README、方向归档和索引；增强约束校验器以核对五个默认 Java 服务和内部端口不对宿主机暴露；完成全量静态检查。  
**非目标**：不修改业务行为来迎合脚本；不复制 Plan 任务到索引；不把未实现的领域模块写入当前实现索引；不创建 plan_15；不执行真实 DeepSeek 条件验收，因为本计划不修改供应商边界。  
**验收标准**：平台拓扑脚本可重复运行并以非零退出表达失败；现有稳定 RAG 回归全部通过且数据含唯一 runId；约束当前实现索引与源码、Compose 一致；README 明确五进程启动方式和 8082 兼容入口；所有校验、Gradle 测试和 Docker 回归无跳过；Plan/index 状态与真实进度一致。  
**验证方式**：运行 `platform_topology_test.sh`、AdminRag 契约、Query Stub 成功/失败、默认 Smoke、Smoke Profile、Retrieval Evidence、`./gradlew check`、三个 Python 校验器及其单测、`python3 scripts/validate_plans.py --strict --verify-git`、`git diff --check`；检索 `crag-app`、旧 Compose 服务名、旧端口和未登记模块残留。  
**涉及文件**：`scripts/tests/http/**`、`scripts/validate_constraints.py`、`scripts/tests/test_validate_constraints.py`、`constraints/package-structure.md`、`constraints/docker-structure.md`、`constraints/api-style.md`、`constraints/persistence-style.md`、`constraints/test-workflow.md`、`README.md`、`plan/plan_main.md`、`plan/plan_archive/2026-06-22-multi-tenant-knowledge-platform-direction.md`、`plan/plan_14/plan_14.md`、`plan/index/README.md`

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
