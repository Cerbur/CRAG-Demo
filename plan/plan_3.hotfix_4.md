# Plan_3.hotfix_4 — Plan 工作流约束抽取

> 创建时间：2026-06-13
> 归属：plan_3 文档资产建设后的协作规范整理

---

## 背景

`AGENTS.md` 与 `CLAUDE.md` 中仍直接维护计划优先、计划分层、命名硬约束和进度追踪规范。随着代码风格、包结构、Docker 部署结构都已经抽取到 `constraints/`，Plan 工作流也应收敛到同一目录，避免多入口重复维护。

---

## 范围

1. 新增 `constraints/plan-workflow.md`，作为 Plan 工作流约束唯一维护入口。
2. 更新 `AGENTS.md` 与 `CLAUDE.md`，将计划相关约束路由到 `constraints/plan-workflow.md`。
3. 更新 `plan/plan_main.md`，记录本 hotfix，并将计划命名与进度规范入口指向 `constraints/plan-workflow.md`。

---

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 3.hotfix_4.1 | 新增 constraints/plan-workflow.md | ✅ 完成 | — | 2026-06-13 |
| 3.hotfix_4.2 | AGENTS.md / CLAUDE.md Plan 路由收敛 | ✅ 完成 | — | 2026-06-13 |
| 3.hotfix_4.3 | plan_main 同步 Plan 工作流入口 | ✅ 完成 | — | 2026-06-13 |
| 3.hotfix_4.4 | 检查入口文档不再展开 Plan 细则 | ✅ 完成 | — | 2026-06-13 |

> 状态图例：⏳ 待开始 / 🔄 进行中 / ✅ 完成 / ❌ 阻塞

整体进度：**4 / 4（100%）**

---

## 验收标准

- `constraints/plan-workflow.md` 存在，并包含 Plan 工作流、命名和进度追踪约束。
- `AGENTS.md` 与 `CLAUDE.md` 不再直接展开计划分层、命名和进度追踪细则。
- `AGENTS.md` 与 `CLAUDE.md` 的 Plan 约束路由指向 `constraints/plan-workflow.md`。
- `plan/plan_main.md` 保留计划索引，但计划命名与进度规范细则路由到 `constraints/plan-workflow.md`。

---

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-06-13 | 创建并完成 Plan 工作流约束抽取 hotfix |
