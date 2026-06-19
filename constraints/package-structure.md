# CRAG-Demo Java 模块与包结构约束

> 本文档是 CRAG-Demo Java 模块职责、依赖方向、跨模块公开 API 和包语义的唯一维护入口。`AGENTS.md`、`CLAUDE.md`、计划文档和实现代码不得另行定义冲突规则。

## 一、文档定位

本文档包含三类信息，效力不同：

1. **架构硬约束**：规定目标模块职责、依赖白名单、公开 API 和禁止事项，新增代码必须遵守。
2. **当前实现索引**：只描述仓库中已经存在的模块、包和关键实现，用于导航，不构成未来设计承诺。
3. **已知偏差**：记录当前实现与目标约束之间尚未消除的差异，并关联负责迁移的 Plan。

尚未实现的类、包或模块不得写入“当前实现索引”。未来设计统一写入对应 Plan；只有已经确定为项目级架构规则的内容才进入本文档正文。

## 二、术语

- **模块**：`settings.gradle.kts` 中声明的 Gradle subproject。
- **公开 API**：普通业务模块允许跨模块引用的 Java 类型，统一位于被依赖模块的 `api` 包及其子包。
- **内部实现**：不属于公开 API 的类型。即使 Java 可见性为 `public`，也不得被普通业务模块跨模块引用。
- **组合根**：负责组装 Spring Bean 和生成唯一可启动 jar 的 `crag-app`。
- **诊断例外**：`crag-smoke` 为分阶段冒烟诊断而获得的受控跨层访问权限。
- **普通业务模块**：除 `crag-app` 和 `crag-smoke` 外的模块。

`api` 表示跨模块可见边界，不表示其中的类型必须是 Java `interface`。只有存在替换实现、远程调用或第三方适配边界时才抽象接口；禁止为单一实现机械创建 `XxxService` / `XxxServiceImpl`。

## 三、目标模块职责

Base package 统一为 `ai.cerbur.crag`。

| 模块 | 职责 | 禁止事项 |
| --- | --- | --- |
| `crag-common` | 真正跨多个模块、无明确业务归属的稳定基础类型 | 禁止收纳业务 DTO、Entity、Service、Client、单模块工具或为绕开依赖环而搬入的类型 |
| `crag-storage` | JPA Entity、Repository、DAO 和数据库投影 | Repository 禁止被模块外调用；禁止承载检索、入库或问答业务编排 |
| `crag-ingestion` | AdminRag 写入、ChunkSplit、Sparse/Dense 索引构建和 Cron 编排 | 禁止暴露 HTTP Controller；禁止依赖 Retrieval 内部实现 |
| `crag-retrieval` | Embedding、Sparse/Dense 召回、RRF、Rerank 和检索门面 | 禁止生成最终回答；禁止让普通调用方感知内部检索阶段 |
| `crag-query` | UserQuery、Context、Prompt、LLM 调用和回答编排 | 禁止直接访问 Storage 或 Retrieval 内部阶段 |
| `crag-api` | 正式 HTTP Controller、请求 DTO、校验与统一异常转换 | 禁止承载业务逻辑、直接访问 DAO 或检索内部组件 |
| `crag-smoke` | 仅在 `smoke` Profile 下启用的冒烟与分阶段诊断端点 | 禁止承载正式业务能力、默认启用、被业务模块依赖或生成独立启动 jar |
| `crag-app` | Spring Boot 组合根、运行时装配、配置和健康检查 | 禁止业务 Controller、业务编排、直接调用 DAO 或业务组件 |

`crag-app` 是唯一 Spring Boot 启动模块和唯一可启动 jar。其他模块均为 library module。

## 四、模块依赖白名单

模块依赖采用“默认禁止、显式放行”。每个模块只允许声明下表中的直接项目依赖；未列出的依赖一律禁止，不得借助传递依赖越界访问。

| 调用模块 | 允许直接依赖 |
| --- | --- |
| `crag-common` | 无 |
| `crag-storage` | `crag-common` |
| `crag-retrieval` | `crag-storage`、`crag-common` |
| `crag-ingestion` | `crag-retrieval`、`crag-storage`、`crag-common` |
| `crag-query` | `crag-retrieval`、`crag-common` |
| `crag-api` | `crag-ingestion`、`crag-query`、`crag-common` |
| `crag-smoke` | `crag-api`、`crag-ingestion`、`crag-query`、`crag-retrieval`、`crag-storage`、`crag-common` |
| `crag-app` | 为运行时装配依赖全部应用模块；不得据此在 Java 代码中调用业务类型 |

附加硬约束：

- 禁止任何模块循环依赖。
- `crag-app` 的装配依赖不授予业务调用权限。
- `crag-smoke` 的诊断依赖是唯一跨层例外，不得作为其他模块越界调用的依据。
- 新增或调整项目依赖时，必须先更新对应 Plan 和本文档，再修改 `settings.gradle.kts` 或模块 `build.gradle.kts`。

## 五、跨模块公开 API

### 5.1 通用规则

- 普通业务模块只能引用被依赖模块的 `ai.cerbur.crag.<module>.api` 包及其子包类型。
- 非 `api` 包默认属于模块内部；Java `public` 只表示语言可见性，不等于架构公开。
- 公开 API 必须保持窄边界，只暴露调用方完成业务所需的门面、契约、请求和结果类型。
- 外部协议或供应商 SDK 类型不得穿透公开 API。
- 跨模块结果优先使用所属模块的 API DTO、业务对象或结果类型，不新增 Entity 泄漏。

当前公开入口：

```text
ai.cerbur.crag.ingestion.api
├── AdminRagService
└── AdminRagResult

ai.cerbur.crag.retrieval.api
├── RetrievalService
├── result/
│   └── ChunkSearchResult
└── embedding/
    ├── EmbeddingClient
    └── EmbeddingException

ai.cerbur.crag.query.api
└── UserQueryService
```

`EmbeddingClient` 是 Retrieval 对外提供的能力契约。当前实现可以调用 HTTP Sidecar；未来可迁移为 RPC 或独立 SDK，但 `crag-ingestion` 只能依赖 `retrieval.api.embedding`，不得依赖具体传输实现。

### 5.2 Storage 的暂时例外

`crag-storage` 尚未建立完整 `api` 包，迁移期间允许上层通过根包 DAO 和必要的 `storage.result` / `storage.entity` 类型访问存储能力，但必须满足：

- 例外只覆盖当前架构测试白名单列举的既有调用；禁止新增 Entity 跨模块传播。
- `storage.repository` 永远只允许 Storage 内部访问。
- 上层不得修改 Entity 后自行持久化；状态变化必须通过 DAO 方法完成。
- 新增跨模块返回类型优先使用投影或结果类型，不得扩大 Entity 传播范围。
- 是否收口 Storage API 由实际耦合问题驱动，不为形式统一提前增加映射层；新增需求不得借迁移例外扩大白名单。

## 六、固定包语义

项目只统一有明确架构含义的包名，不要求每个模块机械套用相同目录模板。

| 包名 | 语义 |
| --- | --- |
| `api` | 跨模块公开契约、门面及其输入输出 |
| `controller` | HTTP 入口；仅允许存在于 `crag-api` 和 `crag-smoke` |
| `repository` | Spring Data 数据映射；仅允许存在于 `crag-storage` |
| `entity` | 持久化模型；仅允许由 `crag-storage` 定义 |
| `result` | 某处理阶段已经产生的结果，不得复用尚未产生语义的外层大类型 |
| `internal` | 显式隐藏的实现；普通包即使未命名为 `internal` 也默认模块内部 |

禁止新增语义含混的 `util`、`helper`、`manager`、`misc` 包。通用代码应优先归入拥有该行为的业务模块；无法明确归属时先重新检查抽象是否必要，而不是直接放入 `crag-common`。

## 七、`crag-common` 收纳门槛

新增类型进入 `crag-common` 前必须同时满足：

- 至少有两个实际消费模块，而非假设中的未来调用方。
- 类型没有合理的业务模块归属。
- 类型稳定、无业务流程含义，且不会引入反向依赖。

不满足以上条件的类型留在其业务模块。禁止以“避免循环依赖”为理由把领域类型移动到 `crag-common`；应修正依赖方向或公开 API。

## 八、`crag-smoke` 诊断例外

`crag-smoke` 用于保留现有冒烟流程和内部阶段诊断能力，规则如下：

- Controller 和相关 Bean 必须统一受 `@Profile("smoke")` 限制。
- 默认应用启动不得暴露 `/api/v1/test/**`。
- 只允许通过显式 smoke Docker Compose 启动方式激活，例如设置 `SPRING_PROFILES_ACTIVE=smoke`。
- 允许直接调用 DAO、Sparse/Dense/RRF/Rerank 等内部组件，但每个端点必须明确标注验证阶段。
- 禁止在冒烟端点中实现正式业务规则，禁止被正式 API 复用。
- 单元测试仍保留在各业务模块；`crag-smoke` 不替代单元测试或正式 API 的端到端测试。

## 九、当前实现索引

本节只反映当前源码事实。完整文件列表以源码为准；这里只列包职责、公开调用点和有架构意义的关键实现。

### `crag-common`

```text
ai.cerbur.crag.common.dto.result
├── Response
└── ResponseCode
```

### `crag-storage`

```text
ai.cerbur.crag.storage
├── ChunkDao / ChunkEmbeddingDao / ChunkFtsDao
├── entity/                            — Chunk、索引实体、状态与 Converter
├── repository/                        — Spring Data Repository
└── result/                            — Dense / Sparse DAO 投影
```

### `crag-ingestion`

```text
ai.cerbur.crag.ingestion
├── api/                               — AdminRagService / AdminRagResult（跨模块公开入口）
├── chunk.split/                       — ChunkSplit 能力与数据类型
├── dense/                             — DenseEmbeddingService
└── cron/                              — Dense / Sparse 定时编排
```

### `crag-retrieval`

```text
ai.cerbur.crag.retrieval
├── api/                               — RetrievalService / result.ChunkSearchResult / embedding.EmbeddingClient / embedding.EmbeddingException（跨模块公开入口）
├── embedding/                         — SidecarEmbeddingClient 等内部实现
├── sparse/ / dense/                   — 双路召回
├── rrf/ / rerank/                     — 融合与重排
├── bo/                                — ChunkBO
└── result/                            — 各检索阶段结果（SparseSearchResult/DenseSearchResult/RrfFusionResult）
```

### `crag-query`

```text
ai.cerbur.crag.query
├── api/                               — UserQueryService（跨模块公开入口）
└── llm/                               — ChatClient 契约骨架
```

### `crag-api`

```text
ai.cerbur.crag.api
├── controller/                        — AdminRagController / UserQueryController
├── controller.advice/                 — GlobalExceptionHandler
└── dto.request/                       — HTTP 请求 DTO
```

### `crag-smoke`

```text
ai.cerbur.crag.smoke
└── controller/                        — TestController（smoke Profile 诊断端点）
```

### `crag-app`

```text
ai.cerbur.crag.app
└── CragDemoApplication
```

模块与包边界由 `ModuleBoundaryArchitectureTest` 和 Gradle 模块依赖校验器共同验证，当前不包含迁移期豁免。

## 十、已知偏差

| 偏差 | 受控边界 | 退出条件 |
| --- | --- | --- |
| `crag-storage` 尚无统一 `api` 包，上层仍通过根包 DAO 和少量 storage 类型访问 | 只允许 5.2 节定义的现有调用；禁止 Repository 外泄和新增 Entity 传播 | 实际跨模块耦合需要独立 Storage API 时，通过对应 Plan 收口并删除例外 |

## 十一、维护与自动校验

- 新增、移动或重命名模块和公开 API 时，必须同步更新本文档。
- 内部实现类的普通增删无需逐项更新；只有包职责或关键架构实现变化时才更新索引。
- 模块边界变化必须同步更新 `settings.gradle.kts`、相关 `build.gradle.kts`、Plan 和必要的架构决策记录。
- `crag-api` / `crag-smoke` 的 Controller 位置、Repository 内聚、跨模块 `api` 访问、代码依赖无环和 smoke Profile 必须由 ArchUnit 验证。
- Gradle project dependency 声明白名单必须由独立轻量校验器验证；不得假设 ArchUnit 能发现未被代码引用的多余 Gradle 依赖。
- 架构测试中的临时例外必须关联未完成任务；禁止无期限保留宽泛豁免。
- 涉及测试运行方式时同时遵守 [`test-workflow.md`](./test-workflow.md)；涉及 Java 代码写法时同时遵守 [`code-style.md`](./code-style.md)。
