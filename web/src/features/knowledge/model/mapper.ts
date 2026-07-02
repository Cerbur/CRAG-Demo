/**
 * Pure mappers from Knowledge DTOs (raw `result` payloads returned by the
 * transport) to the {@link KnowledgeBase} / {@link KnowledgePage} domain types.
 *
 * The transport returns `result` as `unknown`. Each mapper validates the shape
 * defensively and throws a typed {@link KnowledgeDtoError} when the contract is
 * violated — never trusting unchecked casts.
 *
 * Domain invariants:
 *  - `id` is ALWAYS a string (the wire format is a decimal string; we never
 *    numericise it — large ids would silently lose precision as JS numbers).
 *  - `apiKeyReady=false` is a legitimate partial-success state, not an error.
 */
import type {
  CreateKnowledgeBaseRequestDto,
  KnowledgeBaseListResponseDto,
  KnowledgeBaseResponseDto,
} from './dto';

/** Domain KnowledgeBase. Mirrors OpenAPI KnowledgeBaseResponse with id alias. */
export interface KnowledgeBase {
  /** Decimal-string id (renamed from wire `knowledgeBaseId`). */
  readonly id: string;
  readonly tenantId: string;
  readonly name: string;
  /** Access Scope readiness. False on create partial success; polled to true. */
  readonly apiKeyReady: boolean;
  /** ISO-8601 UTC. */
  readonly createdAt: string;
  /** ISO-8601 UTC. */
  readonly updatedAt: string;
}

/** Paged list of KnowledgeBases. Empty nextPageToken === end of list. */
export interface KnowledgePage {
  readonly items: ReadonlyArray<KnowledgeBase>;
  readonly nextPageToken: string;
}

/** Thrown when a DTO does not match the OpenAPI contract. */
export class KnowledgeDtoError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'KnowledgeDtoError';
  }
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null;
}

function requireString(obj: Record<string, unknown>, key: string): string {
  const v = obj[key];
  if (typeof v !== 'string' || v.length === 0) {
    throw new KnowledgeDtoError(`expected non-empty string field "${key}"`);
  }
  return v;
}

function requireBoolean(obj: Record<string, unknown>, key: string): boolean {
  const v = obj[key];
  if (typeof v !== 'boolean') {
    throw new KnowledgeDtoError(`expected boolean field "${key}"`);
  }
  return v;
}

/** Narrow an unknown payload to a validated KnowledgeBaseResponseDto. */
function asKnowledgeBaseDto(v: unknown): KnowledgeBaseResponseDto {
  if (!isObject(v)) throw new KnowledgeDtoError('knowledge base response is not an object');
  return {
    knowledgeBaseId: requireString(v, 'knowledgeBaseId'),
    tenantId: requireString(v, 'tenantId'),
    name: requireString(v, 'name'),
    apiKeyReady: requireBoolean(v, 'apiKeyReady'),
    createdAt: requireString(v, 'createdAt'),
    updatedAt: requireString(v, 'updatedAt'),
  };
}

/** Narrow an unknown payload to a validated KnowledgeBaseListResponseDto. */
function asKnowledgeBaseListDto(v: unknown): KnowledgeBaseListResponseDto {
  if (!isObject(v)) throw new KnowledgeDtoError('knowledge base list is not an object');
  const itemsRaw = v['items'];
  if (!Array.isArray(itemsRaw)) throw new KnowledgeDtoError('list.items is not an array');
  const items = itemsRaw.map(asKnowledgeBaseDto);
  const nextPageTokenRaw = v['nextPageToken'];
  if (typeof nextPageTokenRaw !== 'string') {
    throw new KnowledgeDtoError('list.nextPageToken must be a string');
  }
  return { items, nextPageToken: nextPageTokenRaw };
}

/** Map a single DTO (result of GET/{id} or POST create) to the domain Model. */
export function mapKnowledgeBaseDto(result: unknown): KnowledgeBase {
  const dto = asKnowledgeBaseDto(result);
  return {
    id: dto.knowledgeBaseId,
    tenantId: dto.tenantId,
    name: dto.name,
    apiKeyReady: dto.apiKeyReady,
    createdAt: dto.createdAt,
    updatedAt: dto.updatedAt,
  };
}

/** Map a list DTO (result of GET list) to the domain page Model. */
export function mapKnowledgeBaseListDto(result: unknown): KnowledgePage {
  const dto = asKnowledgeBaseListDto(result);
  return {
    items: dto.items.map((d) => ({
      id: d.knowledgeBaseId,
      tenantId: d.tenantId,
      name: d.name,
      apiKeyReady: d.apiKeyReady,
      createdAt: d.createdAt,
      updatedAt: d.updatedAt,
    })),
    nextPageToken: dto.nextPageToken,
  };
}

/** Validate a create request body. Returns it unchanged on success. */
export function toCreateKnowledgeBaseRequest(
  name: string,
): CreateKnowledgeBaseRequestDto {
  const trimmed = name.trim();
  if (trimmed.length < 1 || trimmed.length > 128) {
    throw new KnowledgeDtoError('name must be 1..128 characters');
  }
  return { name: trimmed };
}
