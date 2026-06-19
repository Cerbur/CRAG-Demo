# CRAG-Demo Plan 工作流约束

> 本文档是计划分级、目录、状态、执行、提交和进度追踪的唯一权威。其他文档与 Skill 只能路由或实现本文规则，不得另行定义完成标准。
> workflow v3 自 `plan_8.hotfix_2` 验收提交后生效；缺少 `workflow_version` 的已完成历史 Plan 按兼容模式保留。

## 一、变更分级与规划门槛

### 1.1 必须创建主 Plan

- 新增业务能力、领域边界或公共接口。
- 跨模块架构调整、全局工程治理或显著扩大既有目标。
- 无法合理归属于已完成 Plan 的独立工作。

### 1.2 必须创建 Hotfix

- 修复已完成主 Plan 引入的明确缺陷、遗漏或技术债。
- Hotfix 按被修正对象归属；跨 Plan 时归入主要责任 Plan并注明关联范围。
- 主 Plan 尚在执行时，范围内修正直接更新主 Plan；只有需要独立提交与回滚边界的已完成任务问题才可创建 Hotfix，并记录中断关系。
- Hotfix 原则上不超过 5 个有效任务或 3 个业务模块；超过时默认升级为主 Plan，例外必须说明理由。

### 1.3 免建计划

仅当变更同时满足以下条件时可以免建 Plan：

- 不改变运行时行为、公共接口、依赖、配置、数据或测试逻辑。
- 不涉及约束、Plan、架构或部署文档。
- 仅修正错别字、排版、无语义注释或失效链接。
- 可在一个小提交内完成。

提交主题必须包含 `no-plan` 并说明原因。不确定是否改变语义时必须创建 Plan。紧急修复没有“先改后补”例外，至少先创建最小 Hotfix。

## 二、目录、命名与编号

```text
plan/
├── plan_main.md
├── index/README.md
├── templates/
│   ├── main-plan-template.md
│   ├── hotfix-template.md
│   └── archive-decision-template.md
├── plan_N/
│   ├── plan_N.md
│   └── plan_N.hotfix_M.md
└── plan_archive/
```

- 主 Plan 使用仓库已出现最大编号加一；编号一经创建永久占用，不插号、复用或重排。
- 禁止新增 `plan_1.1.md` 一类小数计划；历史文件仅保留。
- Hotfix 在所属主 Plan 内从 `hotfix_1` 连续递增。
- 主任务编号为 `N.1`、`N.2`；Hotfix 任务编号统一为 `N.hotfix_M.1`。
- Plan 进入 `in_progress` 前可重排编号；之后已有编号永久稳定，新任务只能追加。废弃任务保留原编号。
- 执行依赖写入任务详情的“前置任务”，不依靠编号暗示。

## 三、文档职责与上下文读取

- `plan_main.md`：只维护当前有效的项目定位、产品边界、技术方向、架构职责与阶段路线。
- `plan/index/README.md`：人工维护的计划汇总视图，包含摘要、状态、进度、入口及活跃 Hotfix。
- `plan_N.md` / `plan_N.hotfix_M.md`：范围、任务、验收和证据的事实来源。
- 执行具体计划时读取目标 Plan、相关 Hotfix、索引及相关约束；仅在确认总体方向时读取 `plan_main.md`。
- 禁止在 `plan_main.md` 维护任务表、Hotfix 明细或执行索引。

## 四、workflow v3 元信息

workflow v3 文件必须在 Markdown 标题前使用受限 YAML front matter，只允许简单 `key: value` 标量，不允许数组、嵌套、多行值或 YAML 高级语法。

主 Plan：

```yaml
---
workflow_version: 3
plan_id: plan_9
type: main
status: draft
created: 2026-06-19
updated: 2026-06-19
---
```

Hotfix 额外包含：

```yaml
parent_plan: plan_9
```

约束：

- `type` 只能是 `main` 或 `hotfix`。
- 禁止使用 `owner`；agent session 是阶段性执行者，不是 Plan 的长期所有者。
- `created`、`updated` 使用 `YYYY-MM-DD`。
- 状态、任务、范围、验收、风险、依赖、验证证据或 commit hash 变化时更新 `updated`；纯排版修正无需更新。
- YAML 不保存任务或整体进度，避免重复事实来源。

## 五、状态机

### 5.1 Plan 状态

| YAML | 中文展示 |
| --- | --- |
| `draft` | 草稿 |
| `ready` | 待开始 |
| `in_progress` | 进行中 |
| `verifying` | 待验收 |
| `blocked` | 阻塞 |
| `completed` | 完成 |
| `abandoned` | 废弃 |

合法转换：

- `draft → ready → in_progress → verifying → completed`
- `ready ↔ blocked`
- `in_progress ↔ blocked`
- `verifying ↔ blocked`
- `verifying → in_progress`
- `blocked → draft`
- `draft / ready / in_progress / verifying / blocked → abandoned`

`ready` 表示所有执行决策已解决且至少有一个有效任务。首次开始任务时转为 `in_progress`。只有全部未完成有效任务均已实现、自测、提交并记录真实实现 hash 后，Plan 才能转为 `verifying`。

`verifying` 表示整份 Plan 已交接给独立验收 session。验收发现实现或测试缺陷时，相关任务及 Plan 转回 `in_progress`；与缺陷无关且已经验收通过的任务可以保留 `completed`。验收因环境、凭据或外部服务阻塞时转为 `blocked`，解除后返回 `verifying`。

阻塞解除后的目标状态按事实选择：

- 尚未开工且内容仍完整：`blocked → ready`。
- 尚未开工但依赖完成后需要重新决策、校准版本、凭据或文件边界：`blocked → draft`。
- 已执行一部分且可从恢复点继续：`blocked → in_progress`。
- 验收交接已完成且仅验收环境恢复：`blocked → verifying`。

每次阻塞与解除都必须在阻塞记录或变更记录中说明转换原因；不得为了绕过 `ready` 完整度门槛直接恢复执行。

### 5.2 任务状态

| 规范值 | 中文展示 |
| --- | --- |
| `pending` | 待开始 |
| `in_progress` | 进行中 |
| `verifying` | 待验收 |
| `blocked` | 阻塞 |
| `completed` | 完成 |
| `abandoned` | 废弃 |

合法转换：

- `待开始 → 进行中 → 待验收 → 完成`
- `进行中 ↔ 阻塞`
- `待验收 → 进行中`（审查或测试失败）
- 未完成任务可转为废弃；完成任务不得回退或废弃。

发现已完成任务存在问题时新增修复任务或 Hotfix，不改写历史完成状态。Emoji 只用于展示，不参与逻辑判断。

## 六、进入执行的完整度门槛

`draft` 可以不完整；转为 `ready` 前必须包含：

1. 背景与目标。
2. 范围与非目标。
3. 前置依赖。
4. 文件或模块边界。
5. 任务拆分、顺序及每项前置任务。
6. 每项任务的目标、范围、非目标、验收标准、验证方式和涉及文件。
7. 测试与验证计划。
8. 关键决策与已处理的未决问题。
9. 风险与回滚策略。
10. 进度追踪表、验收记录和只追加的变更记录。

不得包含阻塞执行的 TODO、占位符、矛盾或未决问题。风险与回滚不得只写“无”；须说明可回滚步骤、不需要运行时回滚的撤销方式，或不可逆变更的处置。

达到 `ready` 后，必须先提交 Plan 与索引，再开始编码。修改范围或关键决策时，先更新并提交 Plan。

## 七、任务结构、进度与完成

每项任务详情使用固定字段：

```markdown
## 9.1 任务名称

**目标**：
**前置任务**：无
**范围**：
**非目标**：
**验收标准**：
**验证方式**：
**涉及文件**：
```

进度表固定为：

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |

- 执行 session 完成实现、自测和实现提交后，使用独立交接提交回填真实短 hash（至少 7 位），再将任务标为“待验收”；多个实现提交按时间顺序用逗号分隔。
- 待验收任务必须已经记录真实实现 hash，不得使用 `pending`、分支名或自然语言占位。
- 验收通过后任务才能标记“完成”并填写完成日期；未完成任务不得填写完成日期。
- 交接提交和最终验收提交不写入任务提交栏，也不得充当实现证据。
- 验收者通过 `git show --stat <hash>` 核对提交确实服务任务。
- 废弃任务必须记录原因、日期及替代任务或决策，不计入有效任务分母。

整体进度：

```text
整体进度：完成任务数 / 有效任务数（四舍五入整数%），废弃：N
```

待验收计入分母、不计入完成数；没有废弃任务时可省略“废弃：0”。没有有效任务时为 `0 / 0（0%）`，且 Plan 不得完成。

## 八、提交协议

- 一个提交原则上只对应一个 Plan/Hotfix；一个任务优先对应一个实现提交。
- 紧密耦合任务可共享提交，但必须在 Plan 中说明并共同引用 hash。
- 禁止混入其他 Plan 或无关工作区改动。
- 执行 ready Plan 即授权创建必要本地提交；不包含 push、PR、合并或改写历史。
- 用户要求不提交时，任务最多停留在“待验收”，Plan 不得完成。
- 执行 session 先创建实现提交，再创建独立交接提交回填 hash、将任务及 Plan 转为待验收并同步索引。
- 验收 session 通过后创建最终验收提交，记录验收证据、完成状态和索引更新；该提交不属于实现证据，也不需要写入自身 hash。

建议提交主题：

```text
plan(plan_9): create feature plan
feat(plan_9/9.2): implement feature
docs(plan_9): backfill implementation commits
docs(plan_9): accept completed plan
docs(no-plan): fix broken link
```

## 九、执行、验收、并行与中断

- 默认只有一个主 Plan 处于进行中。
- 任务互不依赖、文件边界不重叠且可独立验证时可并行；两份 Plan 必须记录原因、执行者和文件边界。
- Hotfix 可打断主 Plan；记录中断和恢复点，完成后恢复主 Plan。
- 恢复执行时重新读取 Plan、索引和约束，检查 Git 状态、提交和 diff；根据代码与验证证据重建进度，不能只信状态标记。
- 出现共享文件或架构决策冲突时停止并行，先确定归属。

### 9.1 执行 session

- 负责实现、行为测试、自测、实现提交和验收交接，不负责最终完成。
- 只有整份 Plan 的全部未完成有效任务均已进入“待验收”后，才能将 Plan 转为 `verifying`。
- 交接时必须更新验收记录中的已执行自测、回填实现 hash、同步索引，并明确提示用户启动未参与实现的新 agent session。
- 工作流只定义交接产物，不绑定或自动调用具体 agent 调度工具。

### 9.2 独立验收 session

- 必须是未参与该 Plan 实现的新 agent session；参与过实现的 session 没有最终完成权。
- 必须从仓库事实重建上下文，读取目标 Plan、相关约束与索引、声明的实现 commit/diff、代码和验收记录，不依赖执行 session 的聊天上下文或口头结论。
- 负责代码审查、验收标准核对、最终测试、提交范围核验和完成状态更新。
- 不得修改实现代码；发现实现问题时记录失败证据，将相关任务及 Plan 退回 `in_progress`，交由新的执行 session 修复。
- 验收通过后更新验收记录、任务和 Plan 状态、索引及队列，并创建最终验收提交。

阻塞必须记录原因、当前进度、解除条件、解除方或外部事件、恢复后的下一步及日期；解除时记录结果。测试失败或尚未做完不等于阻塞。

Plan 间执行依赖必须在“前置依赖”章节使用 `- **执行前置 Plan**：` 标记和规范 Plan ID（例如 `plan_9`、`plan_8.hotfix_1`）显式写明；多个依赖可写在同一标记行。不得只通过编号、索引顺序或其他自然语言暗示依赖；显式依赖不得形成环。

## 十、范围变化、废弃与归档

- 不改变目标、验收或模块边界的小调整可更新原 Plan并记录原因。
- 服务原目标的新增任务追加到原 Plan；改变目标、关键架构或显著扩大范围时拆出新主 Plan。
- 已完成 Plan 不改写历史范围，后续修正使用 Hotfix。
- 删除或迁移任务必须保留去向；计划文件原则上不删除。
- Plan 废弃时记录原因、已完成影响及替代计划。
- 已完成 Plan 保留原路径，不搬入归档。

产品边界、技术方向、架构职责或阶段路线变化时，先在 `plan_archive/` 创建决策记录，再同步更新 `plan_main.md`、受影响 Plan 与索引。归档只记录 before / after、原因、影响、迁移和回滚可能性，不复制完整计划正文。

## 十一、索引维护

- 创建 Plan 时立即登记；状态变化时同步更新。
- 索引只展示 Plan 状态和整体进度，不复制任务明细。
- 主计划表只列主 Plan；Hotfix 位于所属 Plan 明细。
- 主计划表包含“活跃修正”列：已完成主 Plan 有进行中或阻塞 Hotfix 时显示其链接、状态和进度，主 Plan 本身仍保持完成。
- Plan YAML/任务表是事实来源；索引不一致时校验失败。
- 新增、移动、完成、废弃计划或 Hotfix 时，在同一提交同步索引。
- 索引必须分别维护唯一“当前执行队列”和“当前验收队列”。
- 执行队列列出全部 `draft`、`ready`、`in_progress`、`blocked` 的 workflow v3 Plan/Hotfix，每项恰好一次；`blocked` 项保留其恢复后的串行位置。
- 验收队列只列出全部 `verifying` Plan/Hotfix，每项恰好一次。
- 同一 Plan 不得同时出现在两个队列。执行队列必须满足所有显式前置依赖；队列只展示当前串行顺序，不替代 Plan 内的前置依赖事实。
- 前置 Plan 只有 `completed` 才算依赖完成；进入 `verifying` 不得放行后续 Plan。

## 十二、验证证据与完成门槛

验收记录至少包含：

- 验证日期和环境。
- 实际执行命令。
- 每项结果为通过、失败或未执行。
- 关键结果摘要。
- 未执行项的原因、风险和后续动作。

自动化测试、Docker 冒烟与人工检查分别记录，不粘贴大段终端输出。大型报告保存在既有 `build/` 路径并记录链接或摘要。

Plan 只有同时满足以下条件才能完成：

- 独立验收 session 已完成验收，且该 session 未参与实现。
- 所有有效任务均完成，废弃任务有原因。
- 所有验收标准有证据，必需测试全部通过且无跳过项。
- 实现 commit hash 全部回填并核对范围。
- Plan 严格静态校验通过。
- 索引状态和进度同步。
- 工作区不存在属于该 Plan 的未提交变更。
- 无未解除阻塞或未记录风险。

## 十三、静态校验

- 入口：`python3 scripts/validate_plans.py`。
- 修改 Plan、索引、模板或本约束时必须执行。
- workflow v3 使用严格校验；缺少 `workflow_version` 的历史 Plan 使用兼容检查。
- `--strict` 将残留 workflow v2 视为迁移错误；`--verify-git` 额外检查 commit hash 存在。
- Plan 完成前必须运行 `python3 scripts/validate_plans.py --strict --verify-git`。
- 根 Gradle `check` 依赖 `validatePlans`；暂不引入 Git hook。
- `ERROR` 阻断检查；`WARNING` 不阻断，但完成前必须处理或在验收记录解释。

校验范围：

- `plan_main.md`：检查职责边界，不按执行 Plan 校验。
- `plan/index/README.md`：检查链接、登记及状态/进度一致性。
- `plan/templates/`：检查占位结构，不要求真实 ID、日期或 commit。
- `plan_archive/`：按方向变更结构检查，不要求任务表。
- `plan_N.md` 与 `plan_N.hotfix_M.md`：执行 Plan 状态校验。

校验器还必须检查：

- `blocked` Plan 的阻塞记录字段完整。
- workflow v3 Plan/Hotfix 的显式前置依赖不存在环。
- 当前执行队列与当前验收队列各自完整、互斥，且执行队列顺序满足依赖。
- 主 Plan 与 Hotfix 的索引状态、进度均与文件事实一致。

## 十四、历史兼容与生效

- 缺少 `workflow_version` 的已完成历史 Plan 不全面重写，不补造证据。
- 新建 Plan 和仍未完成的 Plan 必须使用当前 workflow 版本。
- workflow v2 Plan 由 `plan_8.hotfix_2` 统一迁移为 v3；已完成 Plan 保持历史状态和证据，不重新验收。
- 工作流发生不兼容变化时递增 `workflow_version`。
- 修改全局约束必须创建工程治理主 Plan 或归属明确的 Hotfix，并按修改前的现行规则创建；新规则从治理 Plan 完成提交后生效，不追溯约束该 Plan 自身。

## 十五、相关约束路由

- Java 代码风格：`constraints/code-style.md`
- Java 包结构：`constraints/package-structure.md`
- Docker 部署结构：`constraints/docker-structure.md`
- 测试工作流：`constraints/test-workflow.md`
