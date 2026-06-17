# plan_6.hotfix_3 — Benchmark Skill 评估集标准优化

> 创建日期：2026-06-18  
> 状态：✅ 完成  
> 归属：`plan_6` Retrieval 查询链路

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 6.hotfix_3.1 | 将评估集分层标准写入 `crag-benchmark` skill | ✅ | `6532585` | 2026-06-18 |
| 6.hotfix_3.2 | 扩展随机 case 生成脚本，支持 golden/adversarial/distribution 样本 | ✅ | `6532585` | 2026-06-18 |
| 6.hotfix_3.3 | 扩展评分脚本，输出 95% CI 与回归检测能力 | ✅ | `6532585` | 2026-06-18 |
| 6.hotfix_3.4 | 完成 skill 官方校验、脚本自检和单元测试回归 | ✅ | `6532585` | 2026-06-18 |

整体进度：4 / 4（100%）

## 背景

`plan_6.hotfix_2` 已经把 Retrieval benchmark 流程沉淀为项目内 skill，并支持 seeded randomized case 生成。但一次 benchmark 只有少量 case 时，只能证明链路可跑通，不能支撑部署决策或 prompt / retrieval 策略回归判断。

本 hotfix 按评估数据集质量标准优化 `crag-benchmark`：

- 黄金测试集：50-100 个核心场景精挑细选输入/输出对，每次变更都必须通过。
- 对抗性示例：20-50 个破坏性输入，覆盖提示注入、边缘情况、模糊查询、领域外问题和有害内容请求。
- 分布样本：100-200 个来自真实生产流量形态的随机样本，用于捕捉精选测试遗漏。
- 样本量与置信度：50 个 case 不足以区分 80% 与 96% 系统；部署决策至少使用 200 个 case，质量接近系统比较建议 500+。
- 回归测试：每次 prompt、retrieval 参数或 rerank 策略变化前后必须对比同一评估集。

## 6.hotfix_3.1 评估集分层标准

更新 `skill/crag-benchmark/SKILL.md` 与 references，明确：

- quick smoke 只用于本地流程自检。
- deployment decision 至少 200 个测试用例。
- golden / adversarial / distribution 三类数据必须分别统计。
- prompt 或 retrieval 策略变更需要 baseline / candidate 前后对比。

**验收**：skill 使用者能从入口文档直接理解不同样本量的决策含义。

## 6.hotfix_3.2 随机 case 生成扩展

更新 `skill/crag-benchmark/scripts/generate_cases.py`：

- 支持 `--profile quick|decision|release`。
- 支持显式 `--golden-count`、`--adversarial-count`、`--distribution-count`。
- 每个 case 带 `category` 字段和 query variant。
- 输出 metadata 中包含 category counts 与推荐用途。

**验收**：生成脚本自检覆盖分层样本、seed 可复现和 sentinel 不泄露到 noise 文档。

## 6.hotfix_3.3 评分脚本扩展

更新 `skill/crag-benchmark/scripts/score_report.py`：

- 输出 Top1 / TopK 成功率。
- 基于 Wilson 区间输出 95% CI。
- 输出 5 个百分点回归检测能力提示。
- 保留缺失 score 字段统计。

**验收**：评分脚本自检覆盖 CI、样本量提示和缺失字段统计。

## 6.hotfix_3.4 验证

执行：

- 官方 skill validator。
- 项目内 skill validator。
- case generator self-test。
- score report self-test。
- representative case generation。
- `./gradlew test`。

**验收**：验证命令和结果回填到本计划变更记录。

## 变更记录

- 2026-06-18：创建 hotfix 计划，等待实现与验证。
- 2026-06-18：按评估集质量标准更新 `crag-benchmark` skill，补充 golden / adversarial / distribution 三层数据集、样本量门槛和回归测试流程。
- 2026-06-18：扩展 `generate_cases.py`，支持 `quick`、`decision`、`release` profile，以及显式 `--golden-count`、`--adversarial-count`、`--distribution-count`。
- 2026-06-18：扩展 `score_report.py`，输出 Top1 / TopK 95% Wilson CI、分层 summary 和 5 个百分点回归检测能力提示。
- 2026-06-18：完成验证：
  - `.venv/bin/python /Users/yuancheng/.codex/skills/.system/skill-creator/scripts/quick_validate.py skill/crag-benchmark` 通过，输出 `Skill is valid!`。
  - `python3 skill/crag-benchmark/scripts/validate_skill.py` 通过。
  - `python3 skill/crag-benchmark/scripts/generate_cases.py --self-test` 通过，quick profile 输出 6 个 case：golden 4、adversarial 1、distribution 1。
  - `python3 skill/crag-benchmark/scripts/score_report.py --self-test` 通过，覆盖 95% CI、分层统计和回归检测提示。
  - `python3 skill/crag-benchmark/scripts/generate_cases.py --profile decision --seed 20260618 --output build/benchmark/generated_cases_decision_smoke.json` 通过，输出 200 个 case：golden 60、adversarial 30、distribution 110。
  - `python3 skill/crag-benchmark/scripts/score_report.py --input build/benchmark/retrieval_benchmark_report.json` 通过，旧 10-case benchmark 的 Top1 95% CI 宽度为 27.8 个百分点，回归检测能力为 `no`。
  - `./gradlew test` 通过。
- 2026-06-18：实现提交 hash 回填为 `6532585`。
