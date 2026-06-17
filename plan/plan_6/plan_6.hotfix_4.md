# plan_6.hotfix_4 — Benchmark Skill 任务路由补齐

> 创建日期：2026-06-18  
> 状态：✅ 完成  
> 归属：`plan_6` Retrieval + Query 全链路

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 6.hotfix_4.1 | 在测试工作流约束中补充 benchmark skill 触发路由 | ✅ | — | 2026-06-18 |
| 6.hotfix_4.2 | 在 skill 索引补充任务路由表 | ✅ | — | 2026-06-18 |
| 6.hotfix_4.3 | 验证路由文本、skill 校验和测试 | ✅ | — | 2026-06-18 |

整体进度：3 / 3（100%）

## 背景

`plan_6.hotfix_2` 和 `plan_6.hotfix_3` 已经建立并优化了 `skill/crag-benchmark`，但测试工作流约束中还没有把 benchmark、评估集、回归测试、随机测试数据和置信区间分析这类意图明确导向该 skill。

结果是后续测试任务可能只读取 `constraints/test-workflow.md` 或 `benchmark/` 目录，而不会索引到 `skill/crag-benchmark`。

## 6.hotfix_4.1 测试约束路由

在 `constraints/test-workflow.md` 中补充 benchmark / evaluation skill 路由：

- 涉及 benchmark、评估集、随机测试数据、golden / adversarial / distribution case、回归测试、置信区间、Top1 / TopK 指标时，必须先查看 `skill/README.md`。
- 对 Retrieval / Query / RAG benchmark，必须使用 `skill/crag-benchmark/SKILL.md` 的工作流。
- 非单元测试仍遵守 Docker-only 约束。

**验收**：测试工作流约束能明确把 benchmark 测试意图导向 `crag-benchmark` skill。

## 6.hotfix_4.2 Skill 索引路由表

在 `skill/README.md` 中补充触发词和路由表，避免只有任务命令而缺少“何时使用”的索引。

**验收**：从 skill 索引能直接判断哪些请求应路由到 `crag-benchmark`。

## 6.hotfix_4.3 验证

执行：

- 路由关键词检索。
- 官方 skill validator。
- `generate_cases.py --self-test`。
- `score_report.py --self-test`。
- `./gradlew test`。

**验收**：验证命令和结果回填到本计划变更记录。

## 变更记录

- 2026-06-18：创建 hotfix 计划，等待实现与验证。
- 2026-06-18：在 `constraints/test-workflow.md` 补充 Benchmark / Evaluation Skill 路由，明确 benchmark、评估集、随机测试数据、golden/adversarial/distribution case、回归测试、置信区间、Top1/TopK 指标和 Retrieval / Query / RAG 链路质量评估必须先路由到 `skill/README.md` 与 `skill/crag-benchmark/SKILL.md`。
- 2026-06-18：在 `skill/README.md` 补充触发路由表，说明哪些用户意图必须使用 `crag-benchmark`。
- 2026-06-18：完成验证：
  - `rg -n "crag-benchmark|benchmark|golden/adversarial/distribution|Top1|TopK|置信区间|Benchmark / Evaluation Skill 路由" constraints/test-workflow.md skill/README.md` 命中测试约束和 skill 索引路由。
  - `rg -n "crag-benchmark|golden/adversarial/distribution|Benchmark / Evaluation Skill 路由|Top1|TopK|置信区间" AGENTS.md CLAUDE.md || true` 无输出，确认具体路由未写入项目入口文件。
  - `.venv/bin/python /Users/yuancheng/.codex/skills/.system/skill-creator/scripts/quick_validate.py skill/crag-benchmark` 通过，输出 `Skill is valid!`。
  - `python3 skill/crag-benchmark/scripts/generate_cases.py --self-test` 通过。
  - `python3 skill/crag-benchmark/scripts/score_report.py --self-test` 通过。
  - `./gradlew test` 通过。
