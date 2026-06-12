# Plan_3.hotfix_3 — Docker 部署结构抽取

> 创建时间：2026-06-13
> 归属：plan_3 文档资产建设后的协作规范整理

---

## 背景

`AGENTS.md` 与 `CLAUDE.md` 中仍直接维护 Docker 部署结构索引，和已经抽取出的代码风格、包结构约束不一致。为避免入口文档重复维护部署结构，本次继续将 Docker 部分抽取到 `constraints/` 目录。

---

## 范围

1. 新增 `constraints/docker-structure.md`，作为 Docker 部署结构索引唯一维护入口。
2. 更新 `AGENTS.md` 与 `CLAUDE.md`，将 Docker 部署结构路由到 `constraints/docker-structure.md`。
3. 更新 `plan/plan_main.md`，记录本 hotfix 并增加 Docker 结构索引入口。

---

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 3.hotfix_3.1 | 新增 constraints/docker-structure.md | ✅ 完成 | — | 2026-06-13 |
| 3.hotfix_3.2 | AGENTS.md / CLAUDE.md Docker 路由收敛 | ✅ 完成 | — | 2026-06-13 |
| 3.hotfix_3.3 | plan_main 同步 Docker 结构入口 | ✅ 完成 | — | 2026-06-13 |
| 3.hotfix_3.4 | 检查入口文档不再展开 Docker 结构 | ✅ 完成 | — | 2026-06-13 |

> 状态图例：⏳ 待开始 / 🔄 进行中 / ✅ 完成 / ❌ 阻塞

整体进度：**4 / 4（100%）**

---

## 验收标准

- `constraints/docker-structure.md` 存在，并包含 Docker 部署结构索引。
- `AGENTS.md` 与 `CLAUDE.md` 不再直接展开 Docker 部署结构。
- `AGENTS.md` 与 `CLAUDE.md` 的 Docker 部署结构路由指向 `constraints/docker-structure.md`。

---

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-06-13 | 创建并完成 Docker 部署结构抽取 hotfix |
