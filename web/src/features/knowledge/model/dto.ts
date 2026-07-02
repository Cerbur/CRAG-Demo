/**
 * Knowledge DTO shapes mirroring the OpenAPI Console contract.
 *
 * These live under features/knowledge/model so the mapper can import them
 * without crossing into services/http (the architecture test forbids any
 * `features/**` file from importing `services/http`). The transport returns
 * the raw `result` payload as `unknown`; the mapper narrows against these
 * types.
 *
 * Source of truth: docs/api/console-api.openapi.yaml —
 *   KnowledgeBaseResponse, KnowledgeBaseListResponse, CreateKnowledgeBaseRequest.
 *
 * IDs are decimal strings in the wire contract; they MUST stay as `string`
 * through the whole pipeline — never numericised.
 */

/** GET .../knowledge-bases/{id} | POST .../knowledge-bases result payload. */
export interface KnowledgeBaseResponseDto {
  /** Decimal-string id; never a JS number. */
  readonly knowledgeBaseId: string;
  readonly tenantId: string;
  readonly name: string;
  /** Access Scope readiness flag; false on partial-success create. */
  readonly apiKeyReady: boolean;
  /** ISO-8601 UTC. */
  readonly createdAt: string;
  /** ISO-8601 UTC. */
  readonly updatedAt: string;
}

/** GET .../knowledge-bases result payload (paged). */
export interface KnowledgeBaseListResponseDto {
  readonly items: ReadonlyArray<KnowledgeBaseResponseDto>;
  /** Empty string signals "no more pages". */
  readonly nextPageToken: string;
}

/** POST .../knowledge-bases request body. Name length 1..128. */
export interface CreateKnowledgeBaseRequestDto {
  readonly name: string;
}
