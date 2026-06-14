# Plan_5 Hotfix 1 — Gradle 依赖分层整理 ✅ 完成

> 创建时间：2026-06-14  
> 完成时间：2026-06-14  
> 归属：plan_5 Java Module 拆分后的依赖边界修正

---

## 背景

`plan_5` 已完成 Java multi-module 拆分，但当前各 module 的 `build.gradle.kts` 依赖仍偏宽，存在几个需要收敛的问题：

- 领域 module 之间仍需明确依赖方向：ingestion 可以依赖 retrieval 复用 embedding 能力，但 retrieval 不允许反向依赖 ingestion，避免形成循环依赖。
- 多个 module 使用 `api(project(...))` 暴露下游依赖，可能把 storage、retrieval、query 等依赖泄漏给上层。
- `crag-admin` 作为 API service module 依赖面过宽，需要确认 controller / DTO 实际需要哪些 module。
- `crag-app` 作为唯一启动模块可以负责运行时装配，但不应替代领域 module 声明自身编译所需依赖。
- Spring Boot / Spring AI / JPA / Web / Validation 等外部依赖需要按使用点收敛，避免非必要 starter 进入不需要的 module。

本 hotfix 只处理 Gradle 依赖分层，不调整业务功能。

---

## 目标

1. 梳理 `settings.gradle.kts` 与所有 `crag-*/build.gradle.kts` 的 module 依赖图。
2. 消除任何可能造成循环依赖的 module 关系。
3. 移除非必要的 `api(...)` 和 module 依赖，优先使用 `implementation(...)`。
4. 按真实代码使用点收敛外部 starter 依赖。
5. 修正文档中的 module 依赖方向，使 `constraints/package-structure.md` 与实际 Gradle 结构一致。

---

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 5.hotfix_1.1 | 生成并核对当前 Gradle module 依赖图 | ✅ 完成 | — | 2026-06-14 |
| 5.hotfix_1.2 | 收敛 module 依赖方向，确保无循环依赖 | ✅ 完成 | — | 2026-06-14 |
| 5.hotfix_1.3 | 将非必要 `api(...)` 改为 `implementation(...)` | ✅ 完成 | — | 2026-06-14 |
| 5.hotfix_1.4 | 按代码实际使用点收敛 Spring / JPA / Web / Validation / Spring AI 依赖 | ✅ 完成 | — | 2026-06-14 |
| 5.hotfix_1.5 | 更新包结构约束中的 Gradle 依赖方向说明 | ✅ 完成 | — | 2026-06-14 |
| 5.hotfix_1.6 | 执行 Gradle 编译与测试验证 | ✅ 完成 | — | 2026-06-14 |

整体进度：6 / 6（100%）

---

## 预期依赖原则

### Module 方向

依赖方向必须保持单向、分层、无环：

```text
crag-app
└── crag-admin, crag-ingestion, crag-retrieval, crag-query, crag-storage, crag-common

crag-admin
└── crag-ingestion, crag-query, crag-common

crag-query
└── crag-retrieval, crag-common

crag-ingestion
└── crag-retrieval, crag-storage, crag-common

crag-retrieval
└── crag-storage, crag-common

crag-storage
└── crag-common
```

整理时重点检查：

- `crag-ingestion` 可以依赖 `crag-retrieval`，用于复用 embedding 等检索基础能力。
- `crag-retrieval` 不应依赖 `crag-ingestion`、`crag-query` 或 `crag-admin`。
- `crag-query` 不应依赖 `crag-ingestion` 或 `crag-admin`。
- `crag-storage` 只允许依赖 `crag-common`。
- `crag-common` 不依赖任何业务 module。
- `crag-app` 是启动装配层，可以依赖所有需要被 Spring 扫描和运行时装配的 module，但不放业务逻辑。

### Embedding 能力归属

当前 `crag-ingestion` 的 Dense 写入链路需要 embedding 能力，允许通过依赖 `crag-retrieval` 复用其 embedding client。

本 hotfix 执行时以保持单向依赖为准：

1. `crag-ingestion -> crag-retrieval` 是允许的依赖方向。
2. `crag-retrieval -> crag-ingestion` 禁止出现。
3. 如果后续 LLM / rerank / embedding client 继续膨胀，再单独规划 `crag-model-client`。

在没有新增 module 的前提下，优先复用 `crag-retrieval` 已有 embedding 能力，不因为一次依赖整理引入重复 adapter。

### `api` vs `implementation`

默认使用 `implementation(...)`。只有当某个 module 的公开方法签名暴露了另一个 module 的类型时，才允许使用 `api(...)`。

需要重点收敛：

- `crag-admin` 对业务 module 的依赖默认应为 `implementation`。
- `crag-ingestion`、`crag-retrieval`、`crag-query` 对 `crag-storage` 的依赖如果没有在公开 API 暴露 storage 类型，应为 `implementation`。
- `crag-storage` 对 `crag-common` 只有在 entity / dao 公开签名暴露 common 类型时才使用 `api`，否则使用 `implementation`。

### 外部依赖

按实际使用点保留外部依赖：

- Web controller / exception handler 所在 module 才需要 Spring Web。
- JPA entity / repository / dao 所在 module 才需要 Spring Data JPA。
- 请求 DTO 使用 validation annotation 的 module 才需要 Validation。
- 调用 sidecar embedding 的 module 才需要 WebClient 或相关 HTTP client。
- Spring AI 只在实际使用 TokenTextSplitter 或相关类型的 module 中保留。

---

## 验收标准

- `./gradlew dependencies` 或等价方式能确认 module 依赖图无循环依赖。
- `./gradlew test` 通过。
- 所有 module 的 `build.gradle.kts` 只保留必要依赖。
- `api(...)` 仅用于公开 API 类型确实需要传递暴露的依赖。
- `crag-ingestion -> crag-retrieval` 如存在，必须保持单向且不引入循环依赖。
- `constraints/package-structure.md` 中的依赖方向与实际 Gradle 配置一致。

---

## 变更记录

| 日期 | 变更 |
| --- | --- |
| 2026-06-14 | 创建并完成 Gradle 依赖分层整理 hotfix |
| 2026-06-14 | 纠正依赖方向说明：允许 `crag-ingestion -> crag-retrieval` 复用 embedding client，并将 embedding 包归属保持在 `crag-retrieval` |

---

## 暂不执行事项

- 暂不改变主计划编号，不新增 `plan_5.1`。
- 暂不拆分独立 Spring Boot 服务。
- 暂不引入 MQ / RPC / 服务注册发现。
- 暂不扩大到业务逻辑重构，除非为消除依赖循环或移除非必要依赖所必需。
