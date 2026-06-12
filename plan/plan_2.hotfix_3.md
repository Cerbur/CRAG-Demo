# Plan_2.hotfix_3 — Chunk Split 逻辑收敛到 chunk.split 子包

> 创建时间：2026-06-13
> 归属：plan_2（Core 全链路 + LLM 接入）前置整理

---

## 背景

plan_2.hotfix_2 完成 Core 能力包拆分与 ChunkSplit 命名收敛后，`core.chunk` 包下目前仅剩 4 个 ChunkSplit 相关类。`chunk` 作为领域包名，未来可能容纳更多能力（如 chunk 存储编排、chunk 查询），将纯切分逻辑收敛到 `chunk.split` 子包，边界更清晰。

---

## 目标

将 `ChunkSplitService`、`ChunkSplitData`、`ChunkSplitGroup`、`ChunkSplitResult` 从 `com.crag.demo.core.chunk` 移动到 `com.crag.demo.core.chunk.split` 子包。

调整前：

```text
com.crag.demo.core
├── chunk
│   ├── ChunkSplitService
│   ├── ChunkSplitData
│   ├── ChunkSplitGroup
│   └── ChunkSplitResult
```

调整后：

```text
com.crag.demo.core
├── chunk
│   └── split
│       ├── ChunkSplitService
│       ├── ChunkSplitData
│       ├── ChunkSplitGroup
│       └── ChunkSplitResult
```

---

## 修正范围

1. 新建 `core/chunk/split/` 目录，移动 4 个类并更新 `package` 声明为 `com.crag.demo.core.chunk.split`
2. 更新 `AdminRagService.java` 的 3 行 import（`core.chunk.*` → `core.chunk.split.*`）
3. 移动 `ChunkSplitServiceTest.java` 并更新 `package` 声明
4. 更新 `constraints/package-structure.md` 包结构索引
5. 运行测试确认 move 不改变行为

---

## 非目标

- 不改变任何业务逻辑、算法或测试断言
- 不新增或删除类
- 不动 `chunk` 包下其他可能的内容

---

## 附：AdminRagService.ingest 写入简化

**问题**：原实现分两步写入——先逐个 `save(parent)` 拿 DB 生成的 chunkId，再收集 children `saveAll(children)`。每次入库 N 个 group 需要 N+1 次 DB 操作，还依赖 `@Transactional` 保证原子性。

**修正**：预生成 parent UUID（`UUID.randomUUID()`），打平 parent + children 到同一个 `List<Chunk>`，一次 `saveAll` 写入。

**改动点**：
- 循环内用 `UUID.randomUUID().toString()` 预生成 `parentChunkId`，`parent.setChunkId()` 显式赋值
- parent 和 children 都加入 `allChunks` 列表
- 循环结束后一次 `chunkRepository.saveAll(allChunks)`
- 移除 `@Transactional` 注解和 `import`

**效果**：N 个 group 从 N+1 次 DB 操作降为 1 次，不再依赖事务注解。

---

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| H3.1 | 移动 4 个 ChunkSplit 类到 chunk.split 子包 | ✅ 完成 | — | 2026-06-13 |
| H3.2 | 更新 AdminRagService import | ✅ 完成 | — | 2026-06-13 |
| H3.3 | 移动 ChunkSplitServiceTest 到 chunk.split | ✅ 完成 | — | 2026-06-13 |
| H3.4 | 更新 constraints/package-structure.md | ✅ 完成 | — | 2026-06-13 |
| H3.5 | 运行测试验证 | ✅ 完成 | — | 2026-06-13 |
| H3.6 | AdminRagService.ingest 打平 chunk save，移除 @Transactional | ✅ 完成 | — | 2026-06-13 |

> 状态图例：⏳ 待开始 / 🔄 进行中 / ✅ 完成 / ❌ 阻塞

---

## 验收标准

- `com.crag.demo.core.chunk.split` 包下包含 4 个 ChunkSplit 类
- `AdminRagService.java` import 指向 `core.chunk.split`
- `./gradlew test --tests com.crag.demo.core.chunk.split.ChunkSplitServiceTest` 通过
- `./gradlew test` 通过
- `constraints/package-structure.md` 与实际包结构一致

---

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-06-13 | 创建 hotfix 计划，执行 ChunkSplit 逻辑到 chunk.split 子包收敛 |
| 2026-06-13 | AdminRagService.ingest 打平 chunk save 为单次 saveAll，移除 @Transactional |
