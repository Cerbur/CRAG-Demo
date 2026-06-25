# Skill Task Index

> 项目内 Codex skill 使用入口。  
> 本目录只放与 CRAG-Demo 项目强相关的 skill、任务索引和辅助脚本；通用个人 skill 不放在这里。

## Skill 索引

| Skill | 作用 | 状态 | 入口 |
| --- | --- | --- | --- |
| accept-crag-plan | 按 workflow v3 独立验收或重新验收指定 Plan，记录通过或失败结论 | ✅ 可用 | [accept-crag-plan/SKILL.md](accept-crag-plan/SKILL.md) |
| crag-benchmark | 生成随机 benchmark 数据、运行 Docker-only Retrieval / Query 验证、评分并维护报告 | ✅ 可用 | [crag-benchmark/SKILL.md](crag-benchmark/SKILL.md) |
| execute-crag-plan | 按 workflow v3 执行、恢复或修复指定 Plan，并交接独立验收 | ✅ 可用 | [execute-crag-plan/SKILL.md](execute-crag-plan/SKILL.md) |
| execute-plan-with-opencode | 历史 OpenCode 编排流程 | ⛔ 停用 | [execute-plan-with-opencode/SKILL.md](execute-plan-with-opencode/SKILL.md) |
| repair-crag-plan | 验收失败后修复指定 Plan 的退回问题，并重新交接验收 | ✅ 可用 | [repair-crag-plan/SKILL.md](repair-crag-plan/SKILL.md) |

## execute-crag-plan 路由

以下意图必须路由到 [`execute-crag-plan/SKILL.md`](execute-crag-plan/SKILL.md)：

- “执行 plan7”“实现 Plan 7”。
- “继续 / 恢复 plan_7”。
- 独立验收退回后修复指定 Plan。
- 继续完成 Plan 中未完成任务并交接验收。

该 Skill 是执行 session 流程，不负责最终独立验收。旧 `execute-plan-with-opencode` 不再使用。

## accept-crag-plan 路由

以下意图必须路由到 [`accept-crag-plan/SKILL.md`](accept-crag-plan/SKILL.md)：

- “验收 plan7”“独立验收 Plan 7”。
- “重新验收 plan_7”。
- “验收失败，记录失败结论”。
- 实现 session 已交接后，需要判定 Plan 是否可以完成。

该 Skill 不负责实现修复；验收失败后需要修复时，使用 `repair-crag-plan`。

## repair-crag-plan 路由

以下意图必须路由到 [`repair-crag-plan/SKILL.md`](repair-crag-plan/SKILL.md)：

- “修复 plan7 验收失败项”。
- “处理验收退回”。
- “根据验收失败记录修复 Plan”。
- “修完后重新交接验收”。

该 Skill 不负责最终验收；修复完成后应交给新的独立验收 session 使用 `accept-crag-plan`。

## crag-benchmark 任务入口

### 触发路由

当用户请求包含以下意图时，必须路由到 [`crag-benchmark/SKILL.md`](crag-benchmark/SKILL.md)：

- benchmark、评估、evaluation、评估集、质量评估、链路质量。
- 随机测试数据、golden tests、adversarial examples、distribution samples、对抗性示例、分布样本。
- Retrieval / Query / RAG 的回归测试、前后对比、prompt 变更评估、rerank 参数评估。
- Top1、TopK、命中率、95% CI、置信区间、5 个百分点回归检测、样本量判断。
- 需要生成或分析 `build/benchmark/` 下的 report。

普通 Java 单元测试仍按 `constraints/test-workflow.md` 执行；涉及真实 Spring Boot、PostgreSQL、pgvector 或 sidecar 的 benchmark 必须同时遵守 Docker-only 约束。

| 任务 | 模式 | 说明 | 推荐命令 |
| --- | --- | --- | --- |
| 固定 Retrieval 回归 | baseline | 使用 `benchmark/retrieval_benchmark_runner.py` 的静态 case，观察版本间稳定性 | `python3 benchmark/retrieval_benchmark_runner.py` |
| 快速随机数据生成 | quick | 生成 6 个 case，只用于本地流程自检 | `python3 skill/crag-benchmark/scripts/generate_cases.py --profile quick --seed 20260618` |
| 部署决策评估集 | decision | 生成 200 个 case，包含 golden/adversarial/distribution 三层样本 | `python3 skill/crag-benchmark/scripts/generate_cases.py --profile decision --seed 20260618` |
| 发布对比评估集 | release | 生成 500 个 case，用于质量相近系统比较 | `python3 skill/crag-benchmark/scripts/generate_cases.py --profile release --seed 20260618` |
| 混合 benchmark 准备 | mixed | 固定 baseline 加随机噪声，降低题库化风险 | `python3 skill/crag-benchmark/scripts/generate_cases.py --mode mixed` |
| 报告评分摘要 | report | 汇总 Top1 / TopK / 95% CI / 回归检测能力和缺失 score 字段 | `python3 skill/crag-benchmark/scripts/score_report.py --input build/benchmark/retrieval_benchmark_report.json` |

## 使用约束

- 涉及真实 Spring Boot、PostgreSQL、pgvector 或 sidecar 的 benchmark 必须通过 Docker Compose 运行。
- 随机数据必须记录 seed，便于复现失败 case。
- 部署决策至少使用 200 个 case；比较质量相近系统建议 500+。
- Prompt、retrieval 参数或 rerank 策略变化必须用同一 seed 做前后回归对比。
- 运行产物输出到 `build/benchmark/`，不进入 git trace。
- benchmark 范围变更必须同步更新对应 `plan/plan_*/` hotfix 和 `plan/index/README.md`。
