# Plan_3.hotfix_5 — Plan 目录整理与索引抽取

> 创建时间：2026-06-14
> 归属：plan_3 文档资产建设后的协作规范整理

---

## 背景

`plan/` 下同时存在 `plan_1.md`、`plan_2.1.md`、`plan_2.hotfix_5.md`、`plan_3.hotfix_4.md` 等散文件，主计划、历史小数计划和 hotfix 混在同一层级，后续查找和执行都容易浪费上下文。

同时，`plan_main.md` 已经混入执行计划索引、具体计划范围和历史 hotfix 明细，不再只是总体方向文档。执行具体子 plan 时如果先读 `plan_main`，会引入大量无关 context。

---

## 范围

1. 将 `plan_1*`、`plan_2*`、`plan_3*` 文件分别整理到 `plan/plan_1/`、`plan/plan_2/`、`plan/plan_3/`。
2. 新增 `plan/index/README.md`，统一说明每个 plan 的主要功能和完成状态。
3. 收敛 `plan/plan_main.md`，保留项目定位、产品边界、技术方向、RAG 主链路、关键设计决策、项目拆分责任架构图、架构边界和阶段路线。
4. 更新 `constraints/plan-workflow.md`，补齐新目录结构、索引维护、上下文读取和历史小数计划处理规则。
5. 更新 `AGENTS.md` 与 `CLAUDE.md` 的当前状态和 plan 入口。

不修改 Java、Docker、Sidecar 或数据库实现。

---

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 3.hotfix_5.1 | 将 plan 散文件按主计划目录归档 | ✅ 完成 | — | 2026-06-14 |
| 3.hotfix_5.2 | 新增 plan/index/README.md 计划索引 | ✅ 完成 | — | 2026-06-14 |
| 3.hotfix_5.3 | 收敛 plan_main 为总体方向文档，并保留项目主链路与责任架构图 | ✅ 完成 | — | 2026-06-14 |
| 3.hotfix_5.4 | 更新 Plan 工作流约束 | ✅ 完成 | — | 2026-06-14 |
| 3.hotfix_5.5 | 更新 AGENTS.md / CLAUDE.md 路由与当前状态 | ✅ 完成 | — | 2026-06-14 |
| 3.hotfix_5.6 | 检查旧路径引用和 plan 文件布局 | ✅ 完成 | — | 2026-06-14 |

整体进度：**6 / 6（100%）**

---

## 验收标准

- `plan/` 根目录只保留 `plan_main.md`、`index/`、`plan_N/` 目录和 `plan_archive/`。
- `plan/index/README.md` 能说明每个主计划、历史小数计划和 hotfix 的主要功能与完成状态。
- `plan_main.md` 不再维护执行计划索引，不展开具体子 plan 的任务详情，但保留项目方向、产品边界、RAG 主链路和项目拆分责任架构图。
- `constraints/plan-workflow.md` 明确新目录结构和上下文读取规则。
- `AGENTS.md` 与 `CLAUDE.md` 的当前状态不再引用旧的 plan_A / plan_B 说法。

---

## 变更记录

| 日期 | 变更 |
| --- | --- |
| 2026-06-14 | 创建并完成 Plan 目录整理、索引抽取、plan_main 收敛和工作流约束更新 |
| 2026-06-14 | 根据复盘补回 `plan_main` 中的项目方向、产品边界、RAG 主链路和关键设计决策，避免方向被索引抽取一并削弱 |
| 2026-06-14 | 补回 `plan_main` 中的项目拆分责任架构图与分层责任边界 |
