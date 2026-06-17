---
name: crag-benchmark
description: "Use this skill when working on CRAG-Demo benchmark workflows: generating randomized Retrieval or Query test data, running Docker-only benchmark checks, scoring Sparse/Dense/RRF/Rerank results, updating benchmark task indexes, or turning one-off benchmark experiments into repeatable validation assets."
---

# CRAG Benchmark

## Overview

This skill turns CRAG-Demo benchmark work into a repeatable workflow: generate seeded random test cases, run the Docker-only benchmark path, score retrieval quality, and keep project task indexes current.

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
4. Generate cases with `scripts/generate_cases.py` when randomized data is needed.
5. Run real Retrieval or Query benchmark checks only through Docker Compose.
6. Score or summarize reports with `scripts/score_report.py`.
7. Save generated reports under `build/benchmark/`; keep source scripts and task indexes tracked.
8. Record validation results and commit hashes in the relevant plan hotfix.

## Resource Routing

- Read `references/data-generation.md` when designing randomized target documents, noise documents, query variants, sentinel phrases, or seed behavior.
- Read `references/crag-test-endpoints.md` before wiring a runner to CRAG-Demo test endpoints.
- Read `references/scoring.md` before changing scoring thresholds, report schemas, or failure interpretation.
- Use `scripts/generate_cases.py` for deterministic case generation.
- Use `scripts/score_report.py` for lightweight report scoring and self-tests.

## Commands

Generate randomized cases:

```bash
python3 skill/crag-benchmark/scripts/generate_cases.py --seed 20260618 --case-count 6 --noise-per-case 3 --output build/benchmark/generated_cases.json
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
- Preserve static baseline cases when adding randomized flows, so regressions remain comparable.
- Do not commit generated reports from `build/benchmark/`.
