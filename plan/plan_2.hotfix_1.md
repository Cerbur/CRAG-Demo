# Plan_2.hotfix_1 — ChunkService 长文覆盖与单测补强

> 创建时间：2026-06-12
> 归属：plan_2 任务 2.1（ChunkService 实现）

---

## 背景

复核 plan_2 任务 2.1 的完成情况时发现：

- `plan/plan_2.1.md` 是历史遗留的 Sidecar 计划，不是 ChunkService 计划；ChunkService 对应 `plan/plan_2.md` 中的任务 2.1。
- 当前 `ChunkService.split()` 使用 `TokenTextSplitter` 切出多个 parent 后只取第一个 parent，长文超过约 1024 token 的部分不会生成 child chunk，存在知识入库截断风险。
- 现有单测主要验证第一个 parent 内部覆盖，没有验证原始长文后半段是否进入分块结果，也没有覆盖多 parent 场景下 child `chunkIndex` 在 parent 内递增的语义。

---

## 修正范围

本 hotfix 只处理 ChunkService 及其单元测试：

1. 保留 TokenTextSplitter + JTokkit 的分块技术路线。
2. 将 `ChunkResult` 扩展为可表达多个 parent group：
   - 每个 group 包含一个 parent chunk 和该 parent 下的 child chunks。
   - `chunkIndex` 在每个 parent 内从 0 开始递增。
   - 保留 `parentChunk()` / `childChunks()` 便利方法，降低后续接线成本。
3. `ChunkService.split()` 遍历全部 parent chunks，而不是只取第一个。
4. 补充单测：
   - 长文应生成多个 parent group。
   - 原始长文尾部内容应出现在最终 child chunks 中。
   - 多 parent 场景下每个 group 内 child index 从 0 递增。
   - 短文本与极短文本仍不丢失。

---

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| H1.1 | 复核 plan_2 / plan_2.1 与 ChunkService 现状 | ✅ 完成 | — | 2026-06-12 |
| H1.2 | 修正 ChunkResult 数据结构以支持多 parent group | ✅ 完成 | — | 2026-06-12 |
| H1.3 | 修正 ChunkService 遍历全部 parent chunks | ✅ 完成 | — | 2026-06-12 |
| H1.4 | 补充 ChunkService 单测覆盖长文与多 parent 语义 | ✅ 完成 | — | 2026-06-12 |
| H1.5 | 修复本地 Java/Gradle 运行环境后运行单测验证 | ✅ 完成 | — | 2026-06-12 |

> 状态图例：⏳ 待开始 / 🔄 进行中 / ✅ 完成 / ❌ 阻塞

---

## 验收标准

- 长文输入不会只保留第一个 parent chunk。
- `ChunkResult.chunkGroups()` 能表达完整 parent-child 层级。
- `ChunkResult.childChunks()` 返回全部 child 的扁平列表，兼容现有简单调用。
- 每个 parent group 内的 child `chunkIndex` 从 0 开始递增。
- ChunkService 单测覆盖多 parent、长文尾部覆盖、短文本、极短文本、overlap 与 token 计数基本约束。

---

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-06-12 | 创建 hotfix 计划，记录 ChunkService 长文截断风险与补测范围 |
| 2026-06-12 | 完成 ChunkResult 多 parent group 扩展、ChunkService 全 parent 遍历、长文不截断与 group 内 chunkIndex 单测补充；执行 `./gradlew test --tests com.crag.demo.core.chunk.ChunkServiceTest` 时当前环境缺少 Java Runtime，验证阻塞 |
| 2026-06-12 | 复核本地环境：Homebrew OpenJDK 25 的 `java` 可执行文件可用，但 `JAVA_HOME` 未设置，且 `/usr/libexec/java_home` 无法发现 JDK；Gradle Wrapper 还会因沙箱无法写 `~/.gradle` lock 文件而失败。修复方向：设置 `JAVA_HOME`、注册 Homebrew JDK 到 macOS 标准 JDK 目录、测试时使用项目内 `GRADLE_USER_HOME`。 |
| 2026-06-12 | 已修复 Codex shell 环境：`JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`，且在 CRAG-Demo 目录下自动使用项目内 `.gradle` 作为 `GRADLE_USER_HOME`；`./gradlew --version` 已通过。重新运行 ChunkService 单测后，Java/Gradle 环境阻塞解除，但仍有 2 个业务断言失败：`ChunkServiceTest.java:216` 与 `ChunkServiceTest.java:228`。 |
| 2026-06-12 | 业务断言失败原因确认：TokenTextSplitter 对长中文输入未稳定按 1024/256 token 上限切分，实际返回了 6000+ token 的单 parent/child；已在 TokenTextSplitter 后增加 JTokkit token 上限兜底切分。`./gradlew test --tests com.crag.demo.core.chunk.ChunkServiceTest` 与 `./gradlew test` 均通过。 |
