# Plan_3.hotfix_2 — 约束文档目录收敛与包结构抽取

> 创建时间：2026-06-12
> 归属：plan_3 文档资产建设后的协作规范整理

---

## 背景

`AGENTS.md` 与 `CLAUDE.md` 中仍直接维护包结构索引，后续包调整时需要重复修改两个 Agent 入口文档。代码风格约束也不应放在 `doc/` 业务文档目录下，应与其他协作约束统一放入独立约束目录。

---

## 范围

1. 新增 `constraints/package-structure.md`，作为包结构索引唯一维护入口。
2. 将 `doc/code-style.md` 移动为 `constraints/code-style.md`。
3. 更新 `AGENTS.md` 与 `CLAUDE.md`，将包结构和代码风格都路由到 `constraints/`。
4. 更新 `plan/plan_main.md` 与上一 hotfix 文档中的约束路径。

---

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 3.hotfix_2.1 | 新增 constraints/package-structure.md | ✅ 完成 | — | 2026-06-12 |
| 3.hotfix_2.2 | 移动 code-style 到 constraints 目录 | ✅ 完成 | — | 2026-06-12 |
| 3.hotfix_2.3 | AGENTS.md / CLAUDE.md 路由收敛 | ✅ 完成 | — | 2026-06-12 |
| 3.hotfix_2.4 | plan_main 与历史 hotfix 路径同步 | ✅ 完成 | — | 2026-06-12 |
| 3.hotfix_2.5 | 检查旧路径与重复包结构 | ✅ 完成 | — | 2026-06-12 |

> 状态图例：⏳ 待开始 / 🔄 进行中 / ✅ 完成 / ❌ 阻塞

整体进度：**5 / 5（100%）**

---

## 验收标准

- `constraints/code-style.md` 存在，且 `doc/code-style.md` 不再存在。
- `constraints/package-structure.md` 存在，并包含 Java 包结构索引。
- `AGENTS.md` 与 `CLAUDE.md` 不再直接展开包结构树。
- `AGENTS.md` 与 `CLAUDE.md` 的代码风格路由指向 `constraints/code-style.md`。

---

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-06-12 | 创建并完成约束目录收敛与包结构抽取 hotfix |
