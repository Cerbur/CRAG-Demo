# Benchmark Scoring

Scoring should reward both ranking quality and score observability, then report uncertainty clearly enough to support decisions.

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

## Confidence and Regression

Report Top1 and TopK rates with a 95% Wilson confidence interval. Interpret sample size conservatively:

| Test cases | At 90% observed accuracy, approximate 95% CI width | Can detect 5 point regression? |
| ---: | ---: | --- |
| 50 | 19 percentage points | No |
| 100 | 12 percentage points | Weak |
| 200 | 9 percentage points | Yes |
| 500 | 5 percentage points | Supported |
| 1000 | 3 percentage points | Precise |

Use the same seed, same generated dataset, and same scoring rules before and after a prompt, retrieval, rerank, or LLM behavior change. A one-off 50-case run can catch obvious breakage but should not be used for deployment decisions.

## Failure Interpretation

- Target missing from RRF but present after retrieval usually means the benchmark expectation or endpoint semantics need inspection.
- Target present in sparse but missing dense can indicate semantic recall weakness.
- Target present in dense but missing sparse can be acceptable for paraphrase queries but suspicious for exact keyword queries.
- Golden test failures should block the change unless the expected behavior changed intentionally.
- Adversarial failures should be triaged by risk type, not averaged away.
- Distribution failures should be sampled and clustered to find real user-intent gaps.
- Missing score fields reduce observability even when rank is correct.
- Randomized failures must record seed, case id, category, query variant, and distractor count.

## Report Metadata

Reports should include:

- `run_id`
- `seed`
- `mode`
- `profile`
- `case_count`
- `category_counts`
- `noise_per_case`
- `generated_at`
- `average_score`
- `top1_hits`
- `top1_95_ci`
- `top_k_hits`
- `top_k_95_ci`
- `regression_detection`
