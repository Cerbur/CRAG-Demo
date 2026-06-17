# Randomized Benchmark Data

Use randomized data to make benchmark runs less dependent on fixed examples while keeping failures reproducible.

## Case Shape

Each case should include:

- `id`: stable within the generated file.
- `topic`: the retrieval intent being tested.
- `sentinel`: a unique phrase that appears only in the target document and target query.
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
- Parent shape: single child for simple retrieval, multi-child parent when adjacency expansion matters.

## Guardrails

- Keep each target document long enough to become realistic chunk content.
- Put the sentinel near the target document body, not only in the title.
- Do not put the sentinel in noise documents.
- Use a deterministic seed and record it in the report metadata.
- Prefer mixed static plus randomized runs before replacing a stable baseline.
