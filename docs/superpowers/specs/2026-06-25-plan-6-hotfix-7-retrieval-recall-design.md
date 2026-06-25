# plan_6.hotfix_7 Retrieval Recall Design

## Context

`plan_15` independent Docker HTTP acceptance reran
`scripts/tests/http/query_stub_success_test.sh` and found that a freshly written document
was indexed successfully, but UserQuery returned no sources for a question such as
`verify-qs-... 使用什么数据库？`.

Runtime evidence showed:

- `Retrieval search — sparse=0, dense=0`.
- PostgreSQL direct pgvector search with the same query embedding returned a row.
- Sparse FTS used `plainto_tsquery`, whose token semantics require every query token to match.
- The failing query mixed a unique verification code, Chinese content terms, and question words
  that do not appear in the stored chunk.

The defect belongs to the completed `plan_6` Retrieval query path. It is not caused by
`plan_15` ID migration, which did not change retrieval SQL, RRF, rerank, or query recall logic.

## Goal

Refine `plan_6.hotfix_7` so it is executable as a small hotfix with two bounded task lines:

1. Diagnose and fix the Dense Java-layer zero-result defect.
2. Fix Sparse recall semantics so question words or expansion terms do not force a complete miss.

Both recall legs must be repaired. Passing `query_stub_success_test.sh` through only one leg is
not sufficient.

## Non-Goals

- Do not change `plan_15` BIGINT IDs or decimal string API boundaries.
- Do not redesign Retrieval, RRF, Rerank, Query, or AdminRag architecture.
- Do not change embedding or rerank models.
- Do not rebuild existing `chunk_fts` data.
- Do not fix `plan_10.hotfix_1` Docker wait timing.

## Design

### Task Shape

Keep `plan_6.hotfix_7` as a two-task hotfix:

- `6.hotfix_7.1 Dense Java-layer recall fix`
- `6.hotfix_7.2 Sparse partial-match recall fix`

The current task count fits the hotfix rule because the work is limited to Retrieval read-path
behavior in `crag-retrieval` plus FTS/vector query access in `crag-storage`.

### Dense Recall Line

The Dense task should be expressed as a reproducible diagnosis and minimal repair, not as a vague
"root cause still pending" item.

Execution should follow this boundary order:

1. Reproduce the failing Docker HTTP query and preserve the original evidence.
2. Confirm `EmbeddingClient.embed(query)` returns a non-empty finite vector with the expected
   dimension.
3. Confirm `DenseQueryService.search` receives the vector and positive topK.
4. Confirm `ChunkEmbeddingDao.searchSimilar` builds the vector literal expected by pgvector.
5. Confirm `ChunkEmbeddingRepository.searchSimilar` row count from the Java call.
6. Compare the same vector literal in the Docker database using direct SQL.
7. Apply the smallest fix at the first boundary where Java behavior diverges from direct DB
   behavior.

The default assumption is that `ChunkEmbeddingRepository.searchSimilar` SQL stays unchanged because
the DB-level query already returned a row. SQL changes are acceptable only if diagnosis proves the
Java-bound parameter or cast path is the divergent boundary.

The task must not add a Dense score threshold, fallback to Sparse, or swallow embedding failures
into empty results.

### Sparse Recall Line

Sparse should keep write-side CJK preprocessing unchanged:

```text
raw content -> regexp_replace(CJK spacing) -> to_tsvector('simple', ...)
```

Only query-side tsquery construction should change. The replacement must support partial matching
for query text that contains useful content tokens plus non-matching question words or expansion
terms.

Preferred implementation order:

1. Use a PostgreSQL-supported tsquery construction with parameter binding.
2. Preserve the current CJK preprocessing intent on the query side.
3. Preserve deterministic ordering: rank descending, then `chunk_id` ascending.
4. Avoid string-concatenating raw user input into SQL.
5. Avoid full-table scans for blank or tokenless queries.

Implementation candidates include `websearch_to_tsquery`, `phraseto_tsquery` only if evidence shows
it satisfies the partial-match need, or an explicit OR tsquery built from sanitized tokens. The
execution plan should choose the smallest candidate that passes the regression and preserves safety.

### Data Flow

The runtime flow remains unchanged:

```text
UserQuery
  -> RetrievalService.retrieveEvidence
  -> EmbeddingClient.embed
  -> SparseQueryService.search
  -> DenseQueryService.search
  -> RrfFusionService.fuse
  -> RerankService.rerank
  -> parent evidence assembly
```

`RetrievalService`, RRF, Rerank, and Query should only be changed if diagnosis proves they are part
of the defect. The expected changes are in `crag-storage` FTS/vector query access and possibly the
Dense query boundary.

## Error Handling

- Dense embedding failures should remain explicit exceptions through the existing module boundary.
- Empty, null, or invalid vectors should not become silent successful retrievals.
- Sparse blank or tokenless query input should return empty results, not all rows.
- Logging may include counts, dimensions, chunk IDs, and run IDs, but must not log full document
  content, full prompts, or vector values.

## Testing

### Gradle Tests

Update or add focused tests for:

- Dense valid vector path delegates to storage and maps non-empty results.
- Dense invalid vector path keeps the existing empty-result or explicit-error semantics.
- Sparse query with a content token plus question or expansion words still matches relevant chunks.
- Sparse ordering remains stable by rank descending and chunk ID ascending.
- Regression coverage for the failing query shape: unique marker plus `使用什么数据库？`.

Use the project test taxonomy:

- Pure unit tests for service and DAO mapping behavior.
- Component tests only where Spring wiring is the behavior under test.
- Docker HTTP regression for PostgreSQL, pgvector, Sidecar, and full Query behavior.

### Docker HTTP Regression

Final execution and independent acceptance should run:

```bash
docker compose up -d --build
bash scripts/tests/http/query_stub_success_test.sh
bash scripts/tests/http/retrieval_evidence_test.sh
```

`query_stub_success_test.sh` must find the freshly written parent in sources and keep the decimal
string ID assertions from `plan_15`.

`retrieval_evidence_test.sh` must remain stable so the existing parent evidence path does not
regress.

### Evidence To Record

The plan acceptance record should include:

- The failing RED evidence before the fix, if reproduced in the execution session.
- The real Dense root cause and the boundary where Java diverged from direct DB behavior.
- `Retrieval search — sparse=X, dense=Y` for the fixed target query.
- Docker HTTP command results and dates.
- Any unexecuted command with reason, risk, and follow-up owner.

Because both recall legs are in scope, the evidence must show Dense and Sparse repair separately.
One leg succeeding must not substitute for the other.

## Risks And Rollback

- Sparse query changes may alter ranking or over-recall. Mitigate with stable ordering tests and
  Docker query assertions. Roll back the Sparse SQL change independently if needed.
- Dense changes may affect pgvector parameter formatting or query mapping. Mitigate with DAO tests
  plus Docker pgvector verification. Roll back Dense changes independently if needed.
- Extra diagnostic logging may expose sensitive values if careless. Keep logs to counts and IDs.

Both task lines should be implemented in separate commits when practical, allowing independent
revert of Dense and Sparse changes.

## Plan Update Requirements

When refining `plan/plan_6/plan_6.hotfix_7.md`, update:

- Background text to remove ambiguous "root cause still pending" wording from ready-state
  execution criteria.
- Task details to include the Dense diagnosis sequence and separate Sparse partial-match criteria.
- Testing and verification plan to require proof for both recall legs.
- Risk and rollback sections to mention separate Dense and Sparse revert paths.
- Progress and index only if plan status or scope changes.
