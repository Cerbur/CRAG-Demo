# Distributed Snowflake ID Design

## Context

Plan 15 introduces distributed IDs as the first infrastructure step after the multi-service skeleton. The goal is not a fully general global ID platform; it is a demo-friendly, service-scoped Snowflake implementation that can immediately replace the current RAG UUID-style document and chunk identifiers.

The earlier multi-tenant platform design described a classic Snowflake layout. This document supersedes that ID section for Plan 15 and records the decisions confirmed during the Plan 15 design conversation.

## Goals

- Add a reusable `crag-id` module for ID generation, parsing and validation.
- Use Snowflake-style 64-bit numeric IDs with entity type encoded in the high bits.
- Guarantee uniqueness within `service domain + entity type`.
- Immediately switch current RAG persistent IDs (`docId`, `chunkId`, `parentChunkId`) to numeric IDs.
- Accept one-time RAG data reset in the development/demo environment; do not build migration or compatibility layers.
- Keep HTTP IDs as decimal strings to avoid JavaScript precision loss.

## Non-goals

- No database segment allocator.
- No online hot migration, dual-write, or old-ID compatibility.
- No global uniqueness guarantee across unrelated service domains.
- No Access, Tenant, KnowledgeBase, API Key or event-domain IDs in this plan.
- No Redis Streams, Outbox, Consumer Groups or event reliability work.
- No production secret management, mTLS, or Kubernetes concerns.

## ID uniqueness model

The uniqueness scope is:

```text
service domain + entity type
```

Examples:

- `rag:LEGACY_DOCUMENT`
- `rag:CHUNK`
- later `knowledge:DOCUMENT`

Different service domains may produce duplicate numeric IDs. This is acceptable by design. Cross-service APIs and storage must not treat a naked numeric ID as a globally unique resource identity; the owning service or resource type must be explicit in the contract.

## Bit layout

Plan 15 uses the following signed-positive `long` layout:

```text
sign 1 | entity type 8 | timestamp 41 | worker 4 | sequence 10
```

- Sign bit: always `0`.
- Entity type: 8 bits, 256 possible entity codes.
- Timestamp: milliseconds since `2026-01-01T00:00:00Z`, 41 bits, about 69.7 years.
- Worker: 4 bits, 16 workers per `service domain + entity type`.
- Sequence: 10 bits, 1024 IDs per worker per millisecond.

The entity type is part of the ID. The service domain is not part of the ID; it is part of the Redis lease namespace and the calling context.

## Entity registry

`crag-id` owns the entity type registry. Entity codes are stable and never reused after assignment.

Plan 15 enables only:

| Entity | Intended scope | Notes |
| --- | --- | --- |
| `LEGACY_DOCUMENT` | `rag:LEGACY_DOCUMENT` | Temporary current AdminRag document ID while old RAG compatibility entry stays in RAG. |
| `CHUNK` | `rag:CHUNK` | Current RAG chunk and parent chunk IDs. |

Later plans may add:

- `DOCUMENT` for `knowledge:DOCUMENT` when Knowledge owns document lifecycle.
- `USER`, `TENANT` and membership/API entities in Access.

`LEGACY_DOCUMENT` is intentionally temporary and should be removed when the old AdminRag compatibility surface is removed.

## Redis Worker lease

Redis is used only to allocate and renew Snowflake worker IDs. It is not used as an ID buffer and does not introduce event infrastructure.

Lease namespace:

```text
crag:id:{serviceDomain}:{entityType}
```

Runtime behavior:

- Lease TTL defaults to 30 seconds.
- Renewal interval defaults to 10 seconds.
- A generator lazily acquires a worker for the required `service domain + entity type`.
- Services with required ID issuers must report readiness `DOWN` when those issuers cannot acquire or renew leases.
- If Redis is unavailable at startup, required issuers do not issue IDs and readiness is `DOWN`.
- If a lease is lost or expired, the issuer stops issuing IDs and readiness is `DOWN`.
- If Redis and clock state recover, the issuer reacquires a lease and resumes.

## Clock rollback policy

- Rollback `<= 5ms`: wait until the local clock catches up.
- Rollback `> 5ms`: stop issuing IDs and report readiness `DOWN`.
- If the clock recovers while the lease remains valid, resume issuing IDs.
- If the lease expired during recovery, reacquire the worker before issuing again.

## RAG transition

Plan 15 performs a cold switch for RAG IDs:

- `docId`, `chunkId` and `parentChunkId` become Java `long` / database `BIGINT`.
- Parent chunks use sentinel `0` for no parent instead of an empty string.
- HTTP DTOs expose IDs as decimal strings.
- Request parsing validates both numeric syntax and expected entity type.
- Document-oriented APIs reject chunk-segment IDs.
- Existing RAG development/demo data can be removed or recreated; no migration is required.

## Testing strategy

Required verification:

- Unit tests for bit layout, encode/decode, entity validation, sequence rollover and clock rollback.
- Unit/component tests for Redis lease lifecycle using fakes or controlled test doubles where possible.
- RAG service tests proving document and chunk IDs are issued from `crag-id`, stored as `BIGINT`, and returned as decimal strings.
- Docker HTTP regression proving Redis-backed startup, RAG ingestion and query compatibility still work through the demo topology.

