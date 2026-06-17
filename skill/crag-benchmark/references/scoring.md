# Benchmark Scoring

Scoring should reward both ranking quality and score observability.

## Retrieval Dimensions

Recommended 100 point retrieval score:

| Dimension | Points | Meaning |
| --- | ---: | --- |
| Final retrieval rank | 45 | Target chunk appears high after full retrieval and rerank |
| RRF rank | 20 | Target chunk appears high before rerank |
| Rerank score present | 15 | Final result exposes `rerankScore` |
| Dense score present | 8 | Final result exposes `denseScore` |
| Sparse score present | 8 | Final result exposes `sparseScore` |
| RRF score present | 4 | Final result exposes `rrfScore` |

## Failure Interpretation

- Target missing from RRF but present after retrieval usually means the benchmark expectation or endpoint semantics need inspection.
- Target present in sparse but missing dense can indicate semantic recall weakness.
- Target present in dense but missing sparse can be acceptable for paraphrase queries but suspicious for exact keyword queries.
- Missing score fields reduce observability even when rank is correct.
- Randomized failures must record seed, case id, query variant, and distractor count.

## Report Metadata

Reports should include:

- `run_id`
- `seed`
- `mode`
- `case_count`
- `noise_per_case`
- `generated_at`
- `average_score`
- `top1_hits`
- `top_k_hits`
