# plan_6.hotfix_2 — Benchmark Skill 化与随机测试数据生成

> 创建日期：2026-06-18  
> 状态：✅ 完成  
> 归属：`plan_6` Retrieval + Query 全链路

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 6.hotfix_2.1 | 建立项目内 `skill/crag-benchmark` skill 结构 | ✅ | — | 2026-06-18 |
| 6.hotfix_2.2 | 沉淀 benchmark 随机数据生成、运行和评分流程 | ✅ | — | 2026-06-18 |
| 6.hotfix_2.3 | 建立项目内 skill 使用任务索引 | ✅ | — | 2026-06-18 |
| 6.hotfix_2.4 | 完成 skill 结构校验与脚本级验证 | ✅ | — | 2026-06-18 |

整体进度：4 / 4（100%）

## 背景

`plan_6.hotfix_1` 已经将 Retrieval benchmark 从一次性脚本整理为长期资产，但当前 benchmark case 仍以内置静态数据为主。静态 case 适合做 baseline 回归，却容易让验证流程逐渐变成固定题库，难以及时暴露随机噪声、弱关键词、中英混合、相似干扰文档和多 child parent 场景下的排序问题。

因此本 hotfix 将 benchmark 工作流沉淀为项目内 Codex skill。skill 不替代已有 `benchmark/` 目录，而是作为流程化测试协议入口，指导后续通过随机化 case、可复现 seed、Docker-only 运行和结构化评分报告持续验证 Retrieval / Query / RAG 链路。

## 6.hotfix_2.1 建立项目内 skill 结构

在项目根目录新增：

```text
skill/crag-benchmark/
├── SKILL.md
├── agents/openai.yaml
├── references/
└── scripts/
```

`SKILL.md` 只保留核心工作流和资源路由，详细端点、评分口径和数据生成策略放入 `references/`，可执行辅助逻辑放入 `scripts/`。

**验收**：skill 元数据、触发描述、资源路由和项目测试约束清晰，后续 Codex 能按 skill 入口执行 benchmark 工作流。

## 6.hotfix_2.2 沉淀随机数据生成、运行和评分流程

新增脚本能力：

- 基于 seed 生成可复现 benchmark case。
- 同时生成目标文档、相似噪声文档、sentinel phrase、精确查询、语义改写查询和中英混合查询。
- 输出 case JSON，供现有或后续 benchmark runner 消费。
- 提供轻量评分工具，按 Top1 / TopK / RRF / score completeness 输出摘要。

**验收**：脚本在不启动 Docker 的情况下可完成 case 生成和评分单元级自检；非单元的真实链路验证仍必须通过 Docker Compose。

## 6.hotfix_2.3 建立项目内 skill 使用任务索引

新增项目内 skill 任务索引，说明：

- 什么时候使用 `crag-benchmark` skill。
- baseline / randomized / mixed 三种模式的边界。
- 与 `benchmark/` 目录、`build/benchmark/` 报告产物和 Docker-only 测试约束的关系。
- 后续扩展到 Query / LLM 端到端 benchmark 的任务入口。

**验收**：项目内存在可发现的 skill 使用任务索引，并从 benchmark 文档建立路由。

## 6.hotfix_2.4 完成验证

执行以下验证：

- skill 结构校验。
- 随机 case 生成脚本验证。
- 评分脚本验证。
- Java 单元测试回归。

真实 Docker Compose benchmark 不在本 hotfix 中强制执行；如果未执行，需在变更记录中明确原因和后续运行命令。

**验收**：验证命令和结果回填到本计划变更记录。

## 变更记录

- 2026-06-18：创建 hotfix 计划，等待实现与验证。
- 2026-06-18：新增 `skill/crag-benchmark`，包含 `SKILL.md`、`agents/openai.yaml`、3 份 reference 和 3 个辅助脚本。
- 2026-06-18：新增 `skill/README.md` 作为项目内 skill 使用任务索引，并从 `benchmark/README.md` 建立路由。
- 2026-06-18：完成验证：
  - `python3 skill/crag-benchmark/scripts/validate_skill.py` 通过。
  - `python3 skill/crag-benchmark/scripts/generate_cases.py --self-test` 通过。
  - `python3 skill/crag-benchmark/scripts/score_report.py --self-test` 通过。
  - `python3 skill/crag-benchmark/scripts/generate_cases.py --seed 20260618 --case-count 2 --noise-per-case 2 --output build/benchmark/generated_cases_smoke.json` 通过，产物位于 git 忽略的 `build/benchmark/`。
  - `./gradlew test` 通过。
- 2026-06-18：尝试执行 skill-creator 官方 `quick_validate.py`，但当前 Python 运行时缺少 `yaml` 模块，脚本无法启动；本 hotfix 使用零依赖 `validate_skill.py` 覆盖本 skill 的 frontmatter、UI metadata、references 和 scripts 结构校验。
