# Skill Task Index

> 项目内 Codex skill 使用入口。  
> 本目录只放与 CRAG-Demo 项目强相关的 skill、任务索引和辅助脚本；通用个人 skill 不放在这里。

## Skill 索引

| Skill | 作用 | 状态 | 入口 |
| --- | --- | --- | --- |
| crag-benchmark | 生成随机 benchmark 数据、运行 Docker-only Retrieval / Query 验证、评分并维护报告 | ✅ 可用 | [crag-benchmark/SKILL.md](crag-benchmark/SKILL.md) |

## crag-benchmark 任务入口

| 任务 | 模式 | 说明 | 推荐命令 |
| --- | --- | --- | --- |
| 固定 Retrieval 回归 | baseline | 使用 `benchmark/retrieval_benchmark_runner.py` 的静态 case，观察版本间稳定性 | `python3 benchmark/retrieval_benchmark_runner.py` |
| 随机 Retrieval 数据生成 | randomized | 用 seed 生成目标文档、相似噪声文档、sentinel 和查询变体 | `python3 skill/crag-benchmark/scripts/generate_cases.py --seed 20260618` |
| 混合 benchmark 准备 | mixed | 固定 baseline 加随机噪声，降低题库化风险 | `python3 skill/crag-benchmark/scripts/generate_cases.py --mode mixed` |
| 报告评分摘要 | report | 汇总 Top1 / TopK / 平均分和缺失 score 字段 | `python3 skill/crag-benchmark/scripts/score_report.py --input build/benchmark/retrieval_benchmark_report.json` |

## 使用约束

- 涉及真实 Spring Boot、PostgreSQL、pgvector 或 sidecar 的 benchmark 必须通过 Docker Compose 运行。
- 随机数据必须记录 seed，便于复现失败 case。
- 运行产物输出到 `build/benchmark/`，不进入 git trace。
- benchmark 范围变更必须同步更新对应 `plan/plan_*/` hotfix 和 `plan/index/README.md`。
