/**
 * Open API client.
 *
 * Open API uses Bearer API Key (not the Console Access JWT). The complete key
 * lives ONLY in page memory (Chat screen) and is supplied per-request via
 * `HttpRequest.bearerApiKey` (api-client.md §3).
 *
 * Hard rules enforced here:
 *  - Open client NEVER reads {@link ../session-store} (no shared auth).
 *  - Open client NEVER triggers Console single-flight refresh.
 *  - Open client never submits `tenantId` or `knowledgeBaseId` (the Chat
 *    ViewModel is responsible for only putting `question` in the body).
 *  - 401 from Open surfaces as a normal ApiError (kind `authentication`); it is
 *    NOT auth-marked, so there is no refresh attempt.
 *
 * The client trusts the caller to supply a key; feature code (Chat) owns the
 * key lifecycle and clears it on unmount.
 */
import type { HttpClient } from './console-client';
import {
  executeRequest,
  type FetchLike,
  type TransportLogEntry,
  type TransportOptions,
} from './transport';
import type { HttpRequest } from './types';

export interface OpenClientOptions {
  readonly fetch?: FetchLike | undefined;
  readonly log?: ((entry: TransportLogEntry) => void) | undefined;
}

function transportOptions(opts: OpenClientOptions): TransportOptions {
  if (opts.fetch) {
    return { fetch: opts.fetch, log: opts.log };
  }
  return { log: opts.log };
}

/**
 * Create an Open client. The client has no module state — every request must
 * supply its own `bearerApiKey`. This is intentional: it makes the Open
 * isolation invariant statically visible.
 */
export function createOpenClient(opts: OpenClientOptions = {}): HttpClient {
  const transport = transportOptions(opts);
  return {
    async request<T>(req: HttpRequest): Promise<T> {
      const headers: Record<string, string> = { ...req.headers };
      const key = req.bearerApiKey;
      if (typeof key !== 'string' || key.length === 0) {
        throw new Error('Open client requires a per-request bearerApiKey');
      }
      headers['Authorization'] = `Bearer ${key}`;
      const { result } = await executeRequest({ ...req, headers }, transport);
      return result as T;
    },
  };
}

/** Production Open client singleton. Used by the Chat feature (22.7). */
export const openClient: HttpClient = createOpenClient();
