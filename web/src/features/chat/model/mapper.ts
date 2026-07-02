/**
 * Pure Chat (Open Query) model.
 *
 * Source of truth: docs/api/open-api.openapi.yaml — QueryResponse, CitationResponse.
 *
 * Domain invariants (plan_22 §22.7):
 *  - The body sent to the Open Query endpoint is ONLY `{ question }`. There is
 *    no tenantId / knowledgeBaseId / accessToken in the request; the Open
 *    client attaches the in-memory API Key as a Bearer header (api-client.md).
 *  - A {@link ChatMessage} is a memory-only object. It is never persisted, never
 *    placed into the TanStack Query cache (the Chat ViewModel keeps messages in
 *    React state only).
 *  - Sources only ever carry `reference` / `documentId` / `excerpt` — never
 *    chunk id, score, prompt or context (OpenAPI guards this server-side too).
 *
 * These helpers are pure; the service/ViewModel under app/chat own transport
 * and orchestration. No file here may import services/http (architecture test).
 */
import type { ChatMessage, QuerySource } from './types';

// Re-export the domain types so feature/app code can import everything from the
// mapper barrel without a second import line.
export type { ChatMessage, QuerySource, ChatMessageStatus } from './types';

/** Wire-level DTO mirror of the OpenAPI CitationResponse. */
export interface CitationResponseDto {
  readonly reference: string;
  readonly documentId: string;
  readonly excerpt: string;
}

/** Wire-level DTO mirror of the OpenAPI QueryResponse. */
export interface QueryResponseDto {
  readonly answer: string;
  readonly sources?: ReadonlyArray<CitationResponseDto>;
}

/** Domain result of a successful query — answer text + sources. */
export interface QueryResult {
  readonly answer: string;
  readonly sources: ReadonlyArray<QuerySource>;
}

/** Thrown when a DTO does not match the OpenAPI contract. */
export class ChatDtoError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ChatDtoError';
  }
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null;
}

function requireString(obj: Record<string, unknown>, key: string): string {
  const v = obj[key];
  if (typeof v !== 'string') {
    throw new ChatDtoError(`expected string field "${key}"`);
  }
  return v;
}

/** Map an unknown payload (the transport `result`) to a {@link QueryResult}. */
export function mapQueryResponseDto(result: unknown): QueryResult {
  if (!isObject(result)) throw new ChatDtoError('query response is not an object');
  const answer = requireString(result, 'answer');
  const sourcesRaw = result['sources'];
  // Missing sources is treated as empty (no results); a non-array is an error.
  if (sourcesRaw === undefined || sourcesRaw === null) {
    return { answer, sources: [] };
  }
  if (!Array.isArray(sourcesRaw)) {
    throw new ChatDtoError('query response.sources is not an array');
  }
  const sources: QuerySource[] = sourcesRaw.map((s, i) => {
    if (!isObject(s)) {
      throw new ChatDtoError(`query response.sources[${i}] is not an object`);
    }
    return {
      reference: requireString(s, 'reference'),
      documentId: requireString(s, 'documentId'),
      excerpt: requireString(s, 'excerpt'),
    };
  });
  return { answer, sources };
}

let idCounter = 0;
/** Generate a unique in-memory message id. NOT persisted — page-scoped only. */
export function newMessageId(): string {
  idCounter += 1;
  return `msg-${Date.now().toString(36)}-${idCounter.toString(36)}`;
}

/** Create a user message in the `sending` status. */
export function createUserMessage(content: string): ChatMessage {
  return {
    id: newMessageId(),
    role: 'user',
    content,
    sources: [],
    status: 'sending',
  };
}

/** Create an assistant placeholder in the `sending` status (paired with a user message). */
export function createAssistantPlaceholder(): ChatMessage {
  return {
    id: newMessageId(),
    role: 'assistant',
    content: '',
    sources: [],
    status: 'sending',
  };
}

/** Mark an assistant message complete with the answer + sources. */
export function markComplete(
  msg: ChatMessage,
  answer: string,
  sources: ReadonlyArray<QuerySource>,
): ChatMessage {
  return { ...msg, content: answer, sources, status: 'complete' };
}

/** Mark an assistant message failed (content/sources stay empty). */
export function markFailed(msg: ChatMessage): ChatMessage {
  return { ...msg, content: msg.content, sources: msg.sources, status: 'failed' };
}

/** Also mark the user message complete (it has been accepted by the server). */
export function markUserComplete(msg: ChatMessage): ChatMessage {
  return { ...msg, status: 'complete' };
}

/**
 * Validate and trim a question. The OpenAPI contract requires 1..2000 Unicode
 * characters after trimming. Throws {@link ChatDtoError} on invalid input.
 */
export function validateQuestion(input: unknown): string {
  if (typeof input !== 'string') {
    throw new ChatDtoError('question must be a string');
  }
  const trimmed = input.trim();
  if (trimmed.length < 1 || trimmed.length > 2000) {
    throw new ChatDtoError('question must be 1..2000 characters after trimming');
  }
  return trimmed;
}
