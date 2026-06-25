# RAG Service Module Consolidation Design

## Background

CRAG-Demo currently keeps the core RAG implementation split across several Gradle subprojects:

- `crag-storage`
- `crag-retrieval`
- `crag-query`
- `crag-ingestion`
- `crag-api`
- `crag-smoke`

These modules are not independently deployed, versioned, or reused outside the RAG runtime. Their code is composed by `crag-rag-service`, while `crag-console-api` and `crag-open-api` are the intended future formal HTTP entry modules. The current `crag-api` controllers are therefore legacy verification endpoints rather than the long-term API boundary.

## Goal

Consolidate the RAG implementation into `crag-rag-service` as the single RAG Gradle module.

After the migration:

- `crag-rag-service` owns the RAG runtime, internal RAG packages, smoke HTTP verification endpoints, gRPC probe, and application composition.
- `crag-storage`, `crag-retrieval`, `crag-query`, `crag-ingestion`, `crag-api`, and `crag-smoke` no longer exist as Gradle subprojects.
- Formal HTTP APIs are left to `crag-console-api` and `crag-open-api`.
- Existing legacy write and query HTTP behavior is retained only as smoke verification behavior.

## Non-Goals

- Do not redesign the future `crag-console-api` or `crag-open-api` contracts.
- Do not change ingestion, retrieval, query, storage, LLM, or rerank business behavior.
- Do not refactor service internals except where required by package movement, dependency consolidation, or smoke URI changes.
- Do not change HTTP script logic, assertions, request bodies, cleanup, waits, or execution flow. Only URL paths change.

## Target Module Structure

`settings.gradle.kts` keeps `crag-rag-service` and removes these subprojects:

- `crag-storage`
- `crag-retrieval`
- `crag-query`
- `crag-ingestion`
- `crag-api`
- `crag-smoke`

`crag-rag-service` directly depends on the remaining shared/runtime modules:

- `crag-common`
- `crag-id`
- `crag-platform-contracts`
- `crag-grpc-runtime`

It also carries the external dependencies previously required by the merged modules, including Spring Boot Web MVC, JPA, Redis, Validation, Spring AI, PostgreSQL, ArchUnit, H2 test runtime, and existing test dependencies.

## Target Package Structure

Source files move into `crag-rag-service/src/main/java` while preserving meaningful package boundaries:

```text
ai.cerbur.crag.storage      // JPA Entity, Repository, DAO, database projections
ai.cerbur.crag.retrieval    // Embedding, Sparse, Dense, RRF, Rerank, retrieval facade
ai.cerbur.crag.query        // Context, Prompt, LLM, answer orchestration
ai.cerbur.crag.ingestion    // Admin ingestion, chunk split, indexing, cron orchestration
ai.cerbur.crag.smoke        // Smoke-only HTTP verification endpoints and DTOs
ai.cerbur.crag.rag          // Application, platform probe, RAG composition root
```

The existing `storage`, `retrieval`, `query`, and `ingestion` package names should stay stable to minimize import churn and preserve domain boundaries.

The current `crag-api` code moves into smoke-owned packages:

```text
ai.cerbur.crag.smoke.controller
ai.cerbur.crag.smoke.controller.advice
ai.cerbur.crag.smoke.dto.rag
ai.cerbur.crag.smoke.dto.query
```

## Smoke HTTP Contract

All legacy RAG HTTP verification endpoints use the smoke URI namespace and are active only under the `smoke` Spring profile.

The URI changes are:

| Current URI | Target URI |
| --- | --- |
| `POST /api/v1/admin/rag` | `POST /api/v1/smoke/admin/rag` |
| `POST /api/v1/query` | `POST /api/v1/smoke/query` |
| `/api/v1/test/**` | `/api/v1/smoke/test/**` |

Each smoke controller must be annotated with `@Profile("smoke")`. This makes the activation rule explicit and keeps the existing ArchUnit-style review easy to enforce.

The existing global exception handler moves with the smoke HTTP boundary. It remains the mapper for smoke HTTP validation responses and errors, but it no longer represents a formal public API contract.

## Script Updates

HTTP regression scripts and related references should be updated only for URL paths.

Allowed changes:

- Replace old paths with the target `/api/v1/smoke/**` paths.
- Update comments or labels only when they directly mention the old path.

Disallowed changes:

- Changing request bodies.
- Changing assertions.
- Changing wait or retry behavior.
- Changing cleanup behavior.
- Changing execution order.
- Renaming scripts unless required by an existing hard-coded path.

## Testing Design

Tests move into `crag-rag-service/src/test` with their test category preserved.

- Pure unit tests from `crag-storage`, `crag-retrieval`, `crag-query`, and `crag-ingestion` move to matching package paths under `crag-rag-service`.
- Controller and exception mapping component tests from `crag-api` move to smoke package paths and update expected URLs to `/api/v1/smoke/**`.
- Existing smoke tests, if any, move to the same module and keep the smoke profile behavior.
- Mockito extension resources from merged test source sets are preserved under `crag-rag-service/src/test/resources`.

Architecture tests remain in `crag-rag-service` and shift from Gradle-subproject boundary assumptions to package boundary rules.

Rules to keep or adapt:

- Repository access is restricted to `ai.cerbur.crag.storage..`.
- Smoke controllers must be under `ai.cerbur.crag.smoke.controller..` and annotated with `@Profile("smoke")`.
- `ai.cerbur.crag.rag.app..` must not directly call RAG business packages.
- `ai.cerbur.crag.ingestion..` must use `ai.cerbur.crag.retrieval.api..` for retrieval access.
- `ai.cerbur.crag.query..` must use `ai.cerbur.crag.retrieval.api..` for retrieval access.
- Access and Knowledge services must not depend on RAG packages.
- Package cycles remain forbidden.

## Constraint Documentation Updates

Implementation must update architecture constraints to reflect the new target state.

Required updates:

- `constraints/package-structure.md`
  - Describe `crag-rag-service` as the owner of RAG internal packages and smoke verification HTTP.
  - Remove `crag-storage`, `crag-retrieval`, `crag-query`, `crag-ingestion`, `crag-api`, and `crag-smoke` as standalone module responsibilities.
  - Preserve package-level boundary rules for storage, retrieval, query, ingestion, smoke, and rag app.
- `constraints/api-style.md`
  - Remove the rule that HTTP DTO ownership belongs to `crag-api`.
  - State that current legacy RAG write/query HTTP endpoints are smoke verification endpoints.
  - State that formal HTTP API ownership belongs to `crag-console-api` and `crag-open-api`.
- `constraints/docker-structure.md`
  - Update only references affected by the removed RAG subprojects or smoke URI namespace.
- `constraints/test-workflow.md`
  - Update only references affected by the smoke URI namespace or test/module structure.

## Verification

Implementation verification should include:

```bash
./gradlew spotlessCheck
./gradlew test
./gradlew check
```

Because this migration changes Spring composition, HTTP controller activation, persistence packaging, and smoke URLs, affected Docker HTTP smoke regression scripts must also be executed through Docker Compose according to `constraints/test-workflow.md`.

The implementation plan should list the exact scripts after scanning `scripts/tests/http/` for old URLs.

## Risks

- Package movement can leave stale Gradle project dependencies or source-set assumptions.
- Moving all tests into one module can expose duplicate test support resources or class names.
- Smoke profile enforcement can be accidentally weakened if only package-level configuration is used. Class-level `@Profile("smoke")` is required.
- URL-only script changes must be reviewed carefully so the migration does not accidentally change regression semantics.
- Architecture documentation and ArchUnit rules must be updated together; otherwise the repo may claim old Gradle boundaries that no longer exist.
