/**
 * Chat ViewModel (Open Query).
 *
 * Composes the Open Query flow into a page-scoped, in-memory transcript:
 *
 *  STATE MODEL
 *   - `apiKey`: the complete API Key, held ONLY in React state (page memory).
 *     It is NEVER persisted (no localStorage / sessionStorage / cookie / URL /
 *     Query cache). {@link clearKey} purges both the key AND the transcript.
 *   - `messages`: the in-memory transcript. NEVER placed in the TanStack Query
 *     cache (plan_22 §22.7 non-goal: no chat history persistence).
 *   - `question`: the composer draft.
 *
 *  FLOW
 *   - {@link submit} validates the question, pushes a user message + an
 *     assistant placeholder (both `sending`), calls {@link sendQuery}, and on
 *     success marks both `complete` (filling the assistant's answer + sources).
 *   - On failure the assistant placeholder is retained with status `failed`,
 *     and `lastError` holds the mapped {@link ApiError}.
 *   - {@link retry} re-runs the LAST user question against the failed assistant
 *     placeholder. There is NO automatic retry on any failure (OpenAPI non-goal).
 *
 *  NO DOUBLE SUBMIT
 *   - While `isSending` is true, {@link canSubmit} is false and {@link submit}
 *     is a no-op (returns immediately).
 *
 *  REQUEST SHAPE
 *   - The body is ONLY `{ question }`. The Open client attaches the in-memory
 *     API Key as `Authorization: Bearer <key>`. No tenantId / knowledgeBaseId /
 *     accessToken is ever submitted.
 */
import { useCallback, useState } from 'react';
import type { ApiError } from '@services/http/api-error';
import { ApiErrorException } from '@services/http/api-error';
import {
  createAssistantPlaceholder,
  createUserMessage,
  markComplete,
  markFailed,
  markUserComplete,
  validateQuestion,
  type ChatMessage,
} from '@features/chat/model/mapper';
import { sendQuery, type ChatServiceDeps } from './chat-service';
import { defaultChatDeps } from './default-deps';

export interface UseChatOptions {
  readonly deps?: ChatServiceDeps;
}

export interface ChatViewModel {
  /** In-memory API Key (page memory only; cleared on unmount / refresh). */
  readonly apiKey: string;
  /** Composer draft (not yet sent). */
  readonly question: string;
  /** In-memory transcript; never persisted. */
  readonly messages: ReadonlyArray<ChatMessage>;
  /** True while a query is in flight. */
  readonly isSending: boolean;
  /** Last mapped error, or null after a success / clear. */
  readonly lastError: ApiError | null;
  /** True iff a key is set, a question is draftable, and no request is in flight. */
  readonly canSubmit: boolean;

  setKey(key: string): void;
  /** Clear the key AND the transcript AND any error (refresh-equivalent). */
  clearKey(): void;
  setQuestion(question: string): void;
  /** Send the current draft. No-op (and safe) while a request is in flight. */
  submit(): Promise<void>;
  /** Re-run the last failed user question against its assistant placeholder. */
  retry(): Promise<void>;
}

export function useChat(options: UseChatOptions = {}): ChatViewModel {
  const deps = options.deps ?? defaultChatDeps;

  const [apiKey, setApiKey] = useState<string>('');
  const [question, setQuestion] = useState<string>('');
  const [messages, setMessages] = useState<ReadonlyArray<ChatMessage>>([]);
  const [isSending, setIsSending] = useState<boolean>(false);
  const [lastError, setLastError] = useState<ApiError | null>(null);

  const setKey = useCallback((key: string) => {
    setApiKey(key);
  }, []);

  const clearKey = useCallback(() => {
    setApiKey('');
    setMessages([]);
    setLastError(null);
    setQuestion('');
    setIsSending(false);
  }, []);

  const setQuestionCb = useCallback((q: string) => {
    setQuestion(q);
  }, []);

  const canSubmit =
    apiKey.length > 0 && !isSending && (() => {
      try {
        validateQuestion(question);
        return true;
      } catch {
        return false;
      }
    })();

  /**
   * Core send: paired user+assistant rows, call the service, settle status.
   * Shared by submit() and retry(). The caller is responsible for choosing the
   * question text and (for retry) reusing the existing placeholder ids.
   */
  const runSend = useCallback(
    async (rawQuestion: string, existingUser?: ChatMessage, existingAssistant?: ChatMessage) => {
      const userMsg = existingUser ?? createUserMessage(rawQuestion);
      const assistantMsg = existingAssistant ?? createAssistantPlaceholder();

      // Append (new submit) or reuse ids (retry). The user message starts as
      // `sending` and is marked `complete` once the server accepts the request
      // (i.e. on success). On failure the user message is still considered
      // accepted (the question was valid and reached the server); only the
      // assistant placeholder transitions to `failed`.
      if (existingUser) {
        // Retry path: rows already exist; nothing to append.
      } else {
        setMessages((prev) => [...prev, userMsg, assistantMsg]);
      }
      setQuestion('');
      setIsSending(true);
      setLastError(null);

      try {
        const result = await sendQuery(deps, apiKey, rawQuestion);
        setMessages((prev) =>
          prev.map((m) => {
            if (m.id === userMsg.id) return markUserComplete(m);
            if (m.id === assistantMsg.id) return markComplete(m, result.answer, result.sources);
            return m;
          }),
        );
      } catch (e) {
        const apiError =
          e instanceof ApiErrorException
            ? e.apiError
            : ((): ApiError => {
                // Defensive: unknown error shape. Treat as unknown / retryable=false.
                const message = e instanceof Error ? e.message : 'Chat failed';
                return {
                  kind: 'unknown',
                  message,
                  retryable: false,
                  fieldErrors: [],
                };
              })();
        setLastError(apiError);
        setMessages((prev) =>
          prev.map((m) => (m.id === assistantMsg.id ? markFailed(m) : m)),
        );
      } finally {
        setIsSending(false);
      }
    },
    [apiKey, deps],
  );

  const submit = useCallback(async () => {
    if (isSending) return; // no double submit
    if (apiKey.length === 0) {
      // Surface a synchronous error so the caller can react; no network call.
      throw new Error('Chat requires an API Key before sending a query');
    }
    let trimmed: string;
    try {
      trimmed = validateQuestion(question);
    } catch {
      return; // invalid draft — guard canSubmit already prevents this in the View
    }
    await runSend(trimmed);
  }, [apiKey, isSending, question, runSend]);

  const retry = useCallback(async () => {
    if (isSending) return;
    if (apiKey.length === 0) {
      throw new Error('Chat requires an API Key before retrying a query');
    }
    // Find the last user message and the assistant placeholder paired with it.
    let userMsg: ChatMessage | undefined;
    for (let i = messages.length - 1; i >= 0; i -= 1) {
      if (messages[i]!.role === 'user') {
        userMsg = messages[i]!;
        break;
      }
    }
    if (!userMsg) return;
    // The assistant placeholder is the next message after the user message.
    const userIdx = messages.findIndex((m) => m.id === userMsg!.id);
    const assistantMsg = messages[userIdx + 1];
    if (!assistantMsg || assistantMsg.role !== 'assistant') return;
    await runSend(userMsg.content, userMsg, assistantMsg);
  }, [apiKey, isSending, messages, runSend]);

  return {
    apiKey,
    question,
    messages,
    isSending,
    lastError,
    canSubmit,
    setKey,
    clearKey,
    setQuestion: setQuestionCb,
    submit,
    retry,
  };
}
