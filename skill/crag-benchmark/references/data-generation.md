# Randomized Benchmark Data

Use randomized data to make benchmark runs less dependent on fixed examples while keeping failures reproducible.

## Dataset Layers

Build evaluation sets from three layers:

| Layer | Recommended count | What it proves |
| --- | ---: | --- |
| Golden tests | 50-100 | Core use cases and curated expected outputs |
| Adversarial examples | 20-50 | Resistance to malicious, ambiguous, edge, out-of-domain, and unsafe inputs |
| Distribution samples | 100-200 | Realistic traffic shape and coverage beyond hand-picked examples |

Use `--profile decision` for the minimum deployment-grade shape: 60 golden, 30 adversarial, and 110 distribution cases. Use `--profile release` for 500 cases when comparing similar systems.

## Case Shape

Each generated case includes:

- `id`: stable within the generated file.
- `category`: `golden`, `adversarial`, or `distribution`.
- `topic`: the retrieval intent being tested.
- `sentinel`: a unique phrase that appears only in the target document and exact query.
- `keywords`: exact terms expected to help sparse retrieval.
- `query_variants`: `exact`, `semantic`, `mixed`, and `weak`.
- `target_document`: the expected hit.
- `noise_documents`: similar distractors that share broad vocabulary but omit the sentinel.

## Randomization Dimensions

Vary these dimensions by seed:

- Topic: Dense, Sparse, RRF, Rerank, ingestion, Docker verification, Query context, observability.
- Language: English, Chinese, and mixed English/Chinese.
- Query type: exact keyword query, semantic paraphrase, weak query, mixed-language query.
- Noise strength: docs sharing RAG vocabulary, docs sharing stage names, docs sharing operational wording.
- Adversarial pressure: prompt injection, ambiguous wording, out-of-domain questions, unsafe requests, and scoring-bypass attempts.
- Parent shape: single child for simple retrieval, multi-child parent when adjacency expansion matters.

## Sample Size Rules

- 50 cases: smoke/regression hint only.
- 100 cases: weak signal; can catch large regressions.
- 200 cases: minimum for deployment decisions.
- 500 cases: enough to compare two close systems.
- 1000 cases: precise ongoing quality tracking.

## Guardrails

- Keep each target document long enough to become realistic chunk content.
- Put the sentinel near the target document body, not only in the title.
- Do not put the sentinel in noise documents.
- Use a deterministic seed and record it in the report metadata.
- Run baseline and candidate on the same generated dataset when comparing prompt or retrieval changes.
- Prefer mixed static plus randomized runs before replacing a stable baseline.
