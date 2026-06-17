# plan_6.hotfix_1 — Retrieval Benchmark 长期化

> 创建日期：2026-06-17  
> 状态：✅ 完成  
> 归属：`plan_6` Retrieval + Query 全链路

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 6.hotfix_1.1 | 建立 benchmark 目录与任务链路索引 | ✅ | `e8ea240` | 2026-06-17 |
| 6.hotfix_1.2 | Retrieval benchmark runner 从一次性脚本升级为长期资产 | ✅ | `e8ea240` | 2026-06-17 |
| 6.hotfix_1.3 | Benchmark report 输出迁移到 build 目录，避免 git trace | ✅ | `e8ea240` | 2026-06-17 |
| 6.hotfix_1.4 | 记录 benchmark 评分口径与后续迭代方向 | ✅ | `e8ea240` | 2026-06-17 |

整体进度：4 / 4（100%）

## 背景

plan_6 的 Retrieval 链路已经进入 Sparse / Dense / RRF / Rerank 可联调阶段。单元测试能覆盖局部算法和服务编排，但无法衡量真实 Docker 环境下长文本写入、异步索引、混合召回和 rerank 排序的整体质量。

因此将一次性 retrieval benchmark 升级为长期可复跑资产，用于后续持续观察检索质量、召回覆盖、分数完整性和相似文档干扰能力。

## 6.hotfix_1.1 建立 benchmark 目录与任务链路索引

新增 `benchmark/README.md`，作为 benchmark 入口索引，说明：

- benchmark 的作用和边界。
- Docker-only 运行要求。
- TestController 任务链路。
- 评分口径。
- 后续长期迭代方向。

**验收**：`benchmark/README.md` 能作为后续 benchmark 维护入口。

## 6.hotfix_1.2 Retrieval benchmark runner 长期化

新增 `benchmark/retrieval_benchmark_runner.py`，内置 10 个复杂长文本 case，通过 Docker Compose 内部调用 Spring Boot TestController：

1. `/api/v1/test/smoke`
2. `/api/v1/test/chunk`
3. `/api/v1/test/chunk/{chunkId}/indexes`
4. `/api/v1/test/rrf`
5. `/api/v1/test/retrieval`

评分维度包含最终排名、RRF 排名、rerankScore、denseScore、sparseScore 和 rrfScore 完整性。

**验收**：脚本可在 Docker Compose 环境中复跑，并输出结构化结果。

## 6.hotfix_1.3 Report 输出迁移到 build 目录

benchmark 运行产物输出到：

```text
build/benchmark/retrieval_benchmark_report.json
build/benchmark/retrieval_benchmark_report.md
```

`build/` 已在 `.gitignore` 中，不进入 git trace。`benchmark/` 目录只保存可维护脚本和说明。

**验收**：报告产物不再放在 `tmp/`，后续默认不被 git 跟踪。

## 6.hotfix_1.4 后续迭代方向

后续可扩展：

- 多 child parent 文档，用于验证相邻 child rerank candidate expansion。
- 中英混合 query、弱关键词 query、噪声相似文档和更大候选集。
- benchmark run id / metadata，支持清理测试数据和趋势对比。
- 将历史 benchmark 产物作为外部 artifact 保存。

**验收**：长期收益和迭代方向已在 benchmark 索引和 hotfix 中明确。
