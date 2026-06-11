# Plan_1.hotfix_1 — 计划命名约束修正

> 创建时间：2026-06-12
> 触发背景：准备继续执行后续计划前，发现现有约束允许 `plan_2.1` 这类分支编号，容易破坏主计划序列。
> 归属：plan_1 之后、plan_2 执行前的前置修正。

---

## 范围说明

本 hotfix 只修正项目协作约束和计划命名规范：

- 禁止后续新增 `plan_1.1`、`plan_2.1` 这类小数计划文件。
- 主计划文件只能使用连续数字：`plan_1.md`、`plan_2.md`、`plan_3.md`。
- 如果执行 `plan_x` 前需要修正前置内容，使用上一阶段 hotfix：`plan_{x-1}.hotfix_1.md`、`plan_{x-1}.hotfix_2.md`。
- 将同一约束同步到 `AGENTS.md` 与 `CLAUDE.md`，确保 Codex 与 Claude Code 都生效。

不修改 Java 业务代码、Docker、Sidecar、数据库 schema 或 README 业务内容。

---

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 1.hotfix_1.1 | 创建 hotfix 计划文档 | ✅ 完成 | — | 2026-06-12 |
| 1.hotfix_1.2 | 更新 AGENTS.md 计划命名约束 | ✅ 完成 | — | 2026-06-12 |
| 1.hotfix_1.3 | 更新 CLAUDE.md 计划命名约束 | ✅ 完成 | — | 2026-06-12 |
| 1.hotfix_1.4 | 同步 plan_main 计划索引规范 | ✅ 完成 | — | 2026-06-12 |
| 1.hotfix_1.5 | 检查命名约束落地结果 | ✅ 完成 | — | 2026-06-12 |

整体进度：**5 / 5（100%）**

---

## 命名规则

### 主计划

主计划只允许使用连续数字：

```text
plan/plan_1.md
plan/plan_2.md
plan/plan_3.md
```

禁止新增：

```text
plan/plan_1.1.md
plan/plan_2.1.md
plan/plan_2.2.md
```

### Hotfix 计划

当准备执行 `plan_x` 时，如果发现必须先处理前置修正，修正计划归属到上一阶段：

```text
plan/plan_{x-1}.hotfix_1.md
plan/plan_{x-1}.hotfix_2.md
```

示例：

- 准备执行 `plan_2` 前发现 Sidecar、schema、ignore 等前置修正，写入 `plan_1.hotfix_1.md`。
- 同一阶段还有第二个前置修正，写入 `plan_1.hotfix_2.md`。
- 准备执行 `plan_3` 前发现 `plan_2` 遗留问题，写入 `plan_2.hotfix_1.md`。

### 历史文件处理

当前仓库已有 `plan_1.1.md`、`plan_2.1.md`、`plan_2.2.md`、`plan_2.3.md` 属于历史遗留计划文件。本 hotfix 先不重命名历史文件，避免扩大改动范围；后续不得再新增小数计划文件。

---

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-06-12 | 创建计划命名约束 hotfix，准备同步 AGENTS / CLAUDE / plan_main |
| 2026-06-12 | 完成 AGENTS.md、CLAUDE.md、plan_main.md 的计划命名约束同步 |
