# Plan_2.hotfix_4 — AdminRagService 单元测试补充

> 创建时间：2026-06-13
> 归属：plan_2（Core 全链路 + LLM 接入）

---

## 背景

`AdminRagService` 是知识库入库链路的核心编排服务，负责接收纯文本 → 调 ChunkSplitService 分块 → 构造 Chunk 实体 → 批量写入 chunk 表。该服务此前没有任何单元测试覆盖，遗漏了以下关键验证点：

- 入库流程编排正确性（docId 生成、分块委托、实体构造、批量写入、返回结果）
- Parent/Child chunk 的状态设置（SKIPPED vs INIT）
- ParentChunkId 关联链正确性
- Metadata JSON 构建（title 合并、null 安全、JSON 转义）
- 边界情况（空 groups、多 group 计数、唯一 docId）

---

## 目标

为 `AdminRagService` 补充 18 个单元测试，覆盖 4 个测试维度。

---

## 测试范围

### 基础入库流程（4 tests）
| 测试 | 验证点 |
|------|--------|
| 正常文本 + 元数据 | 返回 PENDING 结果，docId 非空，chunks 数正确 |
| 元数据为 null | 不抛异常，metadata JSON 仍含 title |
| 空 metadata Map | JSON 仅含 title，无多余字段 |
| metadata 含 tags | JSON 合并 title + tags |

### Chunk 实体结构与状态（7 tests）
| 测试 | 验证点 |
|------|--------|
| Parent dense/sparse 状态 | 均为 SKIPPED |
| Child dense/sparse 状态 | 均为 INIT |
| Child→Parent 关联 | parentChunkId 正确指向同组 parent |
| DocId 一致性 | 所有 chunk 共享同一 docId |
| ChunkIndex 正确性 | Parent 文档级序号 + Child parent 内序号 |
| Parent chunkId 预生成 | Parent 设置 chunkId 后子项才能引用 |
| saveAll 批量写入 | 只调用一次，包含所有 chunk |

### 边界情况（3 tests）
| 测试 | 验证点 |
|------|--------|
| 空 groups | 返回 0 chunks，不调用 saveAll |
| 多 group 计数 | child 总数跨 group 正确累加 |
| 唯一 docId | 每次调用生成不同 docId |

### Metadata JSON 构建（3 tests）
| 测试 | 验证点 |
|------|--------|
| 仅含 title | JSON 结构正确 |
| title 含特殊字符 | JSON 正确转义 |
| 字段顺序 | title 在最前（LinkedHashMap 保序） |

---

## 技术决策

1. **Mockito subclass mock maker**：JDK 25 与 Mockito 5.14.2 的 inline mock maker 不兼容（`OpenedClassReader` ASM 错误）。在 `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker` 配置 `mock-maker-subclass` 解决。
2. **@Spy ObjectMapper**：保留真实序列化行为，通过 subclass mock maker 代理。
3. **lenient() on saveAll**：避免空 groups 场景下 `UnnecessaryStubbingException`。
4. **纯单元测试**：不加载 Spring 上下文，使用 `@ExtendWith(MockitoExtension.class)` + `@Mock` / `@InjectMocks`。

---

## 涉及文件

| 文件 | 操作 |
|------|------|
| `src/test/java/com/crag/demo/service/AdminRagServiceTest.java` | 新增（18 tests） |
| `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker` | 新增（subclass mock maker） |

---

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| H4.1 | 编写 AdminRagService 单元测试（4 个 Nested 维度，18 tests） | ✅ 完成 | — | 2026-06-13 |
| H4.2 | 配置 subclass mock maker 解决 JDK 25 兼容性问题 | ✅ 完成 | — | 2026-06-13 |
| H4.3 | 运行 `./gradlew test` 确认全部通过 | ✅ 完成 | — | 2026-06-13 |

> 状态图例：⏳ 待开始 / 🔄 进行中 / ✅ 完成 / ❌ 阻塞

---

## 验收标准

- [x] `./gradlew test --tests com.crag.demo.service.AdminRagServiceTest` 通过（18/18）
- [x] `./gradlew test` 全部通过（不影响已有测试）
- [x] 测试覆盖：正常流程 + 状态验证 + 边界情况 + metadata 构建

---

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-06-13 | 创建 hotfix 计划，补充 AdminRagService 18 个单元测试 |
