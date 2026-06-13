# CRAG-Demo Plan 工作流约束

> 本文档是 CRAG-Demo 计划工作流、计划目录、计划命名和进度追踪约束的唯一维护入口。`AGENTS.md`、`CLAUDE.md` 和其他计划文档只保留到本文档的路由。

---

## 一、规划优先

- 所有代码修改必须先落为 `plan/` 目录下的计划文档，不直接在对话中跳过计划修改代码。
- 每次涉及执行计划、命名、状态或范围调整的对话，优先产出 plan 文档更新。
- 所有 `plan_N` / hotfix 执行完成后，必须更新对应计划文档的进度表。
- 新增、完成、迁移或废弃计划后，必须同步更新 `plan/index/README.md`。

---

## 二、计划目录结构

```text
plan/
├── plan_main.md
├── index/
│   └── README.md
├── plan_1/
│   ├── plan_1.md
│   ├── plan_1.1.md          # 历史遗留，仅保留，不再新增同类文件
│   └── plan_1.hotfix_1.md
├── plan_2/
│   ├── plan_2.md
│   ├── plan_2.1.md          # 历史遗留，仅保留，不再新增同类文件
│   └── plan_2.hotfix_*.md
├── plan_3/
│   ├── plan_3.md
│   └── plan_3.hotfix_*.md
└── plan_archive/
    └── README.md
```

- `plan/plan_main.md`：总业务方向，只维护项目定位、技术方向、核心链路方向、架构边界和阶段路线。
- `plan/index/README.md`：执行计划索引，维护每个 plan 的主要功能、完成状态和入口链接。
- `plan/plan_N/plan_N.md`：具体主执行计划。
- `plan/plan_N/plan_N.hotfix_M.md`：归属于 `plan_N` 的前置修正或后置整理。
- `plan/plan_archive/`：方向性变更记录，记录 before / after 及时间。

---

## 三、上下文读取约束

- 执行具体子计划时，优先读取对应目录下的主计划和相关 hotfix，例如执行 `plan_2` 时读取 `plan/plan_2/plan_2.md` 及必要的 `plan/plan_2/plan_2.hotfix_*.md`。
- 查询计划全局状态时，读取 `plan/index/README.md`。
- 只有需要确认项目总体方向、技术方向或阶段边界时，才读取 `plan/plan_main.md`。
- 禁止在 `plan_main` 中维护完整执行计划索引、hotfix 明细或子任务进度，避免执行子 plan 时浪费 context。

---

## 四、计划命名硬约束

- 主 Plan 目录和文件只允许连续数字：`plan/plan_1/plan_1.md` -> `plan/plan_2/plan_2.md` -> `plan/plan_3/plan_3.md`。
- 禁止后续新增小数计划文件，例如 `plan_1.1.md`、`plan_2.1.md`、`plan_2.2.md`。
- 历史遗留的 `plan_1.1.md`、`plan_2.1.md`、`plan_2.2.md`、`plan_2.3.md` 只保留在对应主计划目录下，不作为新计划命名范式继续使用。
- 如果准备执行 `plan_{N+1}` 前发现必须先处理 `plan_N` 遗留问题，写入 `plan/plan_N/plan_N.hotfix_M.md`。
- 同一主计划下 hotfix 编号必须从 `hotfix_1` 连续递增，不跳号。

---

## 五、任务编号规范

- 每个主 plan 内小任务使用 `plan-id.task-id` 编号，如 `1.1`、`1.2`、`2.1`。
- 每个 hotfix 内任务使用能体现 hotfix 归属的编号，如 `3.hotfix_5.1` 或 `H5.1`，同一文件内保持一致。
- 每完成一个子任务，更新 plan 文档中的 `[ ]` -> `[x]` 或进度表状态，记录对应 commit hash。
- 如果未产生 commit，提交字段可暂记为 `—`，但后续提交后应回填。

---

## 六、Plan 进度追踪表规范

每个 `plan_N.md` 和 `plan_N.hotfix_M.md` 必须在任务详情之前放置进度追踪表：

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

## 七、索引维护规范

- `plan/index/README.md` 必须包含主计划索引，说明每个主 plan 的主要功能、状态和入口。
- `plan/index/README.md` 必须包含各主计划目录下的小数历史计划和 hotfix 明细。
- 新增、移动、完成或废弃计划文件时，必须同步更新索引。
- 索引只写摘要、状态和链接；具体任务、验收标准和变更记录保留在对应计划文件中。

---

## 八、相关约束文档路由

- Java 代码风格：`constraints/code-style.md`
- Java 包结构：`constraints/package-structure.md`
- Docker 部署结构：`constraints/docker-structure.md`
