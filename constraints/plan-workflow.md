# CRAG-Demo Plan 工作流约束

> 本文档是 CRAG-Demo 计划工作流、计划命名和进度追踪约束的唯一维护入口。`AGENTS.md`、`CLAUDE.md` 和其他计划文档只保留到本文档的路由。

---

## 一、规划优先

- 所有代码修改必须先落为 `plan/` 目录下的计划文档，不直接在对话中修改代码。
- 每次对话产出为 plan 文档更新，不直接修改代码。
- 所有 `plan_N` / hotfix 执行完成后，更新 `plan/plan_main.md` 标记进度。

---

## 二、计划分层

- `plan/plan_main.md`：总业务方向，持续迭代。
- `plan/plan_N.md`：具体执行计划，主计划只使用连续数字编号，例如 `plan_1.md`、`plan_2.md`、`plan_3.md`。
- `plan/plan_{N}.hotfix_{M}.md`：前置修正计划。准备执行 `plan_{N+1}` 前发现必须先处理的内容，归入上一阶段 `plan_N.hotfix_M`。
- `plan/plan_archive/`：方向性变更记录，记录 before / after 及时间。

---

## 三、计划命名硬约束

- 主 Plan 文件只允许连续数字：`plan_1.md` -> `plan_2.md` -> `plan_3.md`。
- 禁止后续新增小数计划文件，例如 `plan_1.1.md`、`plan_2.1.md`、`plan_2.2.md`。
- 如果准备执行 `plan_2` 前发现 Sidecar、schema、ignore 等前置修正，写入 `plan_1.hotfix_1.md`；还需要修复则继续 `plan_1.hotfix_2.md`。
- 如果准备执行 `plan_3` 前发现 `plan_2` 遗留问题，写入 `plan_2.hotfix_1.md`，以此类推。
- 历史遗留的 `plan_1.1.md`、`plan_2.1.md`、`plan_2.2.md`、`plan_2.3.md` 暂不强制重命名，但不得作为新计划命名范式继续使用。

---

## 四、任务编号规范

- 每个 plan 内小任务使用 `plan-id.task-id` 编号，如 `1.1`、`1.2`、`2.1`。
- 每完成一个子任务，更新 plan 文档中的 `[ ]` -> `[x]`，记录对应 commit hash。
- 如果未产生 commit，提交字段可暂记为 `—`，但后续提交后应回填。

---

## 五、Plan 进度追踪表规范

每个 `plan_N.md` 必须在任务详情之前放置进度追踪表：

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |

状态图例：

- ⏳ 待开始
- 🔄 进行中
- ✅ 完成
- ❌ 阻塞

表格下方标注整体进度：

```text
整体进度：X / N（Y%）
```

每完成一个子任务，同步更新进度表中的状态、提交和完成时间。

---

## 六、相关约束文档路由

- Java 代码风格：`constraints/code-style.md`
- Java 包结构：`constraints/package-structure.md`
- Docker 部署结构：`constraints/docker-structure.md`
