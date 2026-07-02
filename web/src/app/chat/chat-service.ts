/**
 * Chat (Open Query) orchestration service.
 *
 * Lives under app/chat (NOT features/chat) because it must import the Open
 * client, and the architecture test forbids any `features/**` file from
 * importing `services/http`.
 *
 * HARD RULES (api-client.md §3, plan_22 §22.7):
 *  - The request body sent to POST /open-api/api/v1/query is ONLY `{ question }`.
 *    This service never submits tenantId / knowledgeBaseId / accessToken. The
 *    in-memory API Key is supplied per-request via {@link HttpRequest.bearerApiKey}.
 *  - The Open client does NOT read the Console SessionStore and does NOT trigger
 *    Console single-flight refresh; a 401 surfaces as a normal ApiError.
 *  - There is no automatic retry of LLM Query (OpenAPI non-goal); the caller
 *    (Chat ViewModel) decides whether to expose an explicit Retry button.
 */
import type { HttpClient } from '@services/http/console-client';
import { mapQueryResponseDto, validateQuestion, type QueryResult } from '@features/chat/model/mapper';

/** Injectable dependencies. Tests pass an isolated client. */
export interface ChatServiceDeps {
  readonly client: HttpClient;
}

/** The Open Query endpoint (relative prefix; the runtime server proxies this). */
const QUERY_PATH = '/open-api/api/v1/query';

/**
 * Send a question to the Open Query endpoint.
 *
 * @param deps       Injectable client.
 * @param apiKey     The complete in-memory API Key (format `crag_<prefix>_<secret>`).
 *                   Supplied as `Authorization: Bearer <apiKey>` by the Open
 *                   client. The complete key is NEVER logged by the transport.
 * @param question   Raw question string; trimmed and validated to 1..2000 chars.
 * @returns The mapped {@link QueryResult} on success. Throws {@link ApiErrorException}
 *          (carrying an {@link ApiError}) on any failure — the caller maps it.
 */
export async function sendQuery(
  deps: ChatServiceDeps,
  apiKey: string,
  question: string,
): Promise<QueryResult> {
  if (typeof apiKey !== 'string' || apiKey.length === 0) {
    // Defensive: the ViewModel should also guard this, but we double-check here
    // so the Open client never throws a generic "missing bearerApiKey" Error.
    throw new Error('Chat requires a non-empty API Key before sending a query');
  }
  const trimmed = validateQuestion(question);
  const result = await deps.client.request<unknown>({
    method: 'POST',
    path: QUERY_PATH,
    body: { question: trimmed },
    bearerApiKey: apiKey,
  });
  return mapQueryResponseDto(result);
}
