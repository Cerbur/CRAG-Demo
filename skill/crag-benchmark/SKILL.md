---
name: crag-benchmark
description: "Use this skill when working on CRAG-Demo benchmark workflows: generating randomized Retrieval or Query test data, running Docker-only benchmark checks, scoring Sparse/Dense/RRF/Rerank results, updating benchmark task indexes, or turning one-off benchmark experiments into repeatable validation assets."
---

# CRAG Benchmark

## Overview

This skill turns CRAG-Demo benchmark work into a repeatable evaluation workflow: build layered datasets, generate seeded random test cases, run the Docker-only benchmark path, score retrieval quality with confidence intervals, and keep project task indexes current.

Use it for Retrieval, Query, or RAG validation work where static benchmark data is not enough.

## Workflow

1. Read the project constraints before changing files:
   - `constraints/plan-workflow.md`
   - `constraints/test-workflow.md`
   - relevant `plan/plan_*/` files
2. If the work changes benchmark scope, first create or update the appropriate plan hotfix and `plan/index/README.md`.
3. Choose benchmark mode:
   - `baseline`: reuse stable checked-in cases such as `benchmark/retrieval_benchmark_runner.py`.
   - `randomized`: generate new seeded cases and distractors for a fresh validation pass.
   - `mixed`: run stable baseline plus seeded randomized noise.
4. Choose evaluation size:
   - `quick`: 6 cases, only for local smoke checks.
   - `decision`: 200 cases, minimum for deployment decisions.
   - `release`: 500 cases, for comparing similar systems or higher-confidence release checks.
5. Generate cases with `scripts/generate_cases.py` when randomized data is needed.
6. Run real Retrieval or Query benchmark checks only through Docker Compose.
7. Score or summarize reports with `scripts/score_report.py`, including 95% CI and regression-detection guidance.
8. Save generated reports under `build/benchmark/`; keep source scripts and task indexes tracked.
9. Record validation results and commit hashes in the relevant plan hotfix.

## Dataset Standard

Good benchmark quality depends on use cases, not just a runnable script.

| Type | Count | Purpose |
| --- | ---: | --- |
| Golden tests | 50-100 | Curated core scenarios that must pass on every change |
| Adversarial examples | 20-50 | Prompt injection, edge cases, ambiguous queries, out-of-domain and unsafe requests |
| Distribution samples | 100-200 | Random samples shaped like real production traffic |

50 cases are not enough for deployment decisions. At 90% observed accuracy, 50 cases still leave a very wide 95% confidence interval, so it cannot distinguish an 80% system from a 96% system. Use at least 200 cases for deployment decisions, and 500+ when comparing two systems with close quality.

Every prompt, retrieval-parameter, rerank, or LLM behavior change needs a before/after regression run on the same seed and dataset profile.

## Resource Routing

- Read `references/data-generation.md` when designing randomized target documents, noise documents, query variants, sentinel phrases, or seed behavior.
- Read `references/crag-test-endpoints.md` before wiring a runner to CRAG-Demo test endpoints.
- Read `references/scoring.md` before changing scoring thresholds, report schemas, or failure interpretation.
- Use `scripts/generate_cases.py` for deterministic case generation.
- Use `scripts/score_report.py` for lightweight report scoring and self-tests.

## Commands

Generate randomized cases:

```bash
python3 skill/crag-benchmark/scripts/generate_cases.py --seed 20260618 --profile decision --output build/benchmark/generated_cases.json
```

Generate a custom 200-case evaluation set:

```bash
python3 skill/crag-benchmark/scripts/generate_cases.py --golden-count 60 --adversarial-count 30 --distribution-count 110 --seed 20260618
```

Self-test case generation:

```bash
python3 skill/crag-benchmark/scripts/generate_cases.py --self-test
```

Self-test scoring:

```bash
python3 skill/crag-benchmark/scripts/score_report.py --self-test
```

Run the existing Docker-only retrieval baseline:

```bash
docker compose up -d --build
python3 benchmark/retrieval_benchmark_runner.py
```

## Rules

- Do not run non-unit benchmark checks by directly starting Java or Python services; use Docker Compose.
- Keep randomized benchmark data reproducible with an explicit seed.
- Include at least one unique sentinel per case so expected targets are unambiguous.
- Include distractors that share vocabulary with the target; pure random filler is too easy.
- Separate golden, adversarial, and distribution results in reports.
- Preserve static baseline cases when adding randomized flows, so regressions remain comparable.
- Do not commit generated reports from `build/benchmark/`.
