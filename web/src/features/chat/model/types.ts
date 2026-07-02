/**
 * Chat (Open Query) domain types.
 *
 * These are the in-memory, page-scoped types used by the Chat feature. They are
 * NOT persisted and NOT cached in TanStack Query (plan_22 §22.7 non-goals:
 * no chat history persistence, no streaming).
 *
 * `QuerySource` mirrors the OpenAPI CitationResponse shape but is a separate
 * type so the mapper is the only place that narrows DTOs.
 */

/** A single citation returned alongside an answer. */
export interface QuerySource {
  /** Reference label that corresponds to `[S1]` markers in the answer text. */
  readonly reference: string;
  /** Decimal-string document id. */
  readonly documentId: string;
  /** Defensive ≤500-char excerpt from the source document. */
  readonly excerpt: string;
}

/** Lifecycle status of a single chat message. */
export type ChatMessageStatus = 'sending' | 'complete' | 'failed';

/** One row in the in-memory chat transcript. */
export interface ChatMessage {
  readonly id: string;
  readonly role: 'user' | 'assistant';
  readonly content: string;
  readonly sources: ReadonlyArray<QuerySource>;
  readonly status: ChatMessageStatus;
}
