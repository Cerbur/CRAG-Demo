# Benchmark Index

> CRAG-Demo retrieval benchmark 入口。  
> 本目录只放可维护的 benchmark 脚本、case 定义和说明；运行产物输出到 `build/benchmark/`，不进入 git trace。

## 作用

Benchmark 用于持续评估 plan_6 Retrieval 链路质量，不替代单元测试，也不作为普通本地测试运行。

它覆盖：

- 通过 `TestController` 写入复杂长文本 Doc。
- 等待 DenseEmbeddingCron / SparseEmbeddingCron 完成索引。
- 调用 `/api/v1/test/rrf` 验证 Sparse + Dense + RRF 融合。
- 调用 `/api/v1/test/retrieval` 验证 Embed → Sparse + Dense → RRF → 邻接扩展 → Rerank。
- 对目标 chunk 的召回、排序和四路分数完整性打分。

## Skill 入口

项目内 benchmark 流程化能力维护在 [`skill/crag-benchmark`](../skill/crag-benchmark/SKILL.md)。

当需要生成随机测试数据、混合 baseline 与噪声文档、总结 benchmark report 或扩展 Query / LLM 链路验证时，优先使用该 skill，并从 [`skill/README.md`](../skill/README.md) 查看任务索引。

## 任务链路

```text
benchmark/retrieval_benchmark_runner.py
  ├─ /api/v1/test/smoke
  ├─ /api/v1/test/chunk
  ├─ /api/v1/test/chunk/{chunkId}/indexes
  ├─ /api/v1/test/rrf
  └─ /api/v1/test/retrieval

输出：
build/benchmark/retrieval_benchmark_report.json
build/benchmark/retrieval_benchmark_report.md
```

## 运行方式

先启动完整 Docker Compose 依赖：

```bash
docker compose up -d --build
```

然后运行：

```bash
python3 benchmark/retrieval_benchmark_runner.py
```

> 非单元测试必须走 Docker。该 benchmark 依赖真实 PostgreSQL/pgvector、Spring Boot app、Python sidecar embedding/rerank 模型。

## 评分口径

满分 100 分：

| 维度 | 分值 | 说明 |
| --- | ---: | --- |
| Final retrieval rank | 45 | 目标 chunk Top1 得满分 |
| RRF rank | 20 | 目标 chunk fused Top1 得满分 |
| Rerank score present | 15 | 最终结果包含 `rerankScore` |
| Dense score present | 8 | 最终结果包含 `denseScore` |
| Sparse score present | 8 | 最终结果包含 `sparseScore` |
| RRF score present | 4 | 最终结果包含 `rrfScore` |

## 长期迭代方向

- 增加真实多 child parent 文档，专门验证相邻 child rerank candidate expansion。
- 增加中文、英文、中英混合、相似噪声文档和弱关键词 query。
- 增加 benchmark run id / metadata，方便清理测试数据和做趋势对比。
- 将历史 benchmark 产物作为外部 artifact 保存，不写入 git。
