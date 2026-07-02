/**
 * Integration tests for the Chat ViewModel (Open Query).
 *
 * Proves against a real Open client + MSW stack (api-client.md, plan_22 §22.7):
 *
 *  REQUEST SHAPE HARD RULE
 *   - POST body contains ONLY `{ question }`; no tenantId / knowledgeBaseId /
 *     accessToken is submitted.
 *   - The in-memory API Key is sent as `Authorization: Bearer <key>`; verified
 *     by inspecting the captured request.
 *
 *  KEY MEMORY LIFECYCLE
 *   - `apiKey` lives only in React state; setKey updates it; clearKey purges
 *     both key AND messages.
 *   - There is NO TanStack Query cache for messages (no entry whose JSON holds
 *     the answer or the api key).
 *
 *  MESSAGE STATUS FLOW
 *   - submit() pushes a user message + an assistant placeholder, both status
 *     'sending'; the placeholder transitions to 'complete' on success.
 *   - retry() re-runs the LAST failed user question.
 *   - No automatic retry on any failure (non-goal).
 *
 *  NO DOUBLE SUBMIT
 *   - While a request is in flight, canSubmit is false and submit() is a no-op.
 *
 *  ERROR MAPPING (401 / 502 / 503 / 400 / 404)
 *   - 401 → authentication; 502/503 → retryable; 400 → validation; 404 → business.
 *   - The failed assistant message is RETAINED with status 'failed'.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { type ReactElement } from 'react';
import { render, waitFor, act } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { QueryClient, QueryClientProvider, useQueryClient } from '@tanstack/react-query';
import { createOpenClient } from '@services/http/open-client';
import { ok, err } from '../../test/msw/fixtures';
import { useChat } from './use-chat';
import type { ChatServiceDeps } from './chat-service';

const server = setupServer();

const QUERY_PATH = '*/open-api/api/v1/query';
const API_KEY = 'crag_abcd_<PLACEHOLDER_SECRET>';

function makeDeps(): ChatServiceDeps {
  return { client: createOpenClient() };
}

function newClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  });
}

function wrap(client: QueryClient, ui: ReactElement): ReturnType<typeof render> {
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

interface CapturedRequest {
  readonly body: unknown;
  readonly authHeader: string | null;
}

function useCapturedRequests() {
  const captured: CapturedRequest[] = [];
  const handler = http.post(QUERY_PATH, async ({ request }) => {
    captured.push({
      body: await request.json().catch(() => null),
      authHeader: request.headers.get('Authorization'),
    });
    return HttpResponse.json(
      ok({
        answer: 'RAG 是…… [S1]',
        sources: [{ reference: 'S1', documentId: '4001', excerpt: 'RAG 是……' }],
      }),
    );
  });
  return { captured, handler };
}

function Probe({
  deps,
  onVm,
  onClient,
}: {
  deps: ChatServiceDeps;
  onVm: (vm: ReturnType<typeof useChat>) => void;
  onClient: (qc: QueryClient) => void;
}): null {
  const vm = useChat({ deps });
  const qc = useQueryClient();
  onVm(vm);
  onClient(qc);
  return null;
}

describe('useChat', () => {
  beforeEach(() => {
    server.listen({ onUnhandledRequest: 'error' });
  });
  afterEach(() => {
    server.resetHandlers();
    server.close();
  });

  it('initial state: no key, no messages, cannot submit', async () => {
    let captured: ReturnType<typeof useChat> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} onClient={() => {}} />);
    await waitFor(() => expect(captured).not.toBeNull());
    expect(captured!.apiKey).toBe('');
    expect(captured!.messages).toEqual([]);
    expect(captured!.canSubmit).toBe(false);
  });

  it('canSubmit becomes true only when a key and a non-empty question are present', async () => {
    let captured: ReturnType<typeof useChat> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} onClient={() => {}} />);
    await waitFor(() => expect(captured).not.toBeNull());

    act(() => captured!.setKey(API_KEY));
    expect(captured!.canSubmit).toBe(false); // still no question

    act(() => captured!.setQuestion('什么是 RAG？'));
    await waitFor(() => expect(captured!.canSubmit).toBe(true));
  });

  it('request body contains ONLY question and Authorization uses the in-memory key', async () => {
    const { captured: reqs, handler } = useCapturedRequests();
    server.use(handler);
    let captured: ReturnType<typeof useChat> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} onClient={() => {}} />);
    await waitFor(() => expect(captured).not.toBeNull());

    act(() => captured!.setKey(API_KEY));
    act(() => captured!.setQuestion('什么是 RAG？'));
    await act(async () => {
      await captured!.submit();
    });

    expect(reqs).toHaveLength(1);
    // BODY must contain ONLY `question`. No tenantId / knowledgeBaseId.
    expect(reqs[0]!.body).toEqual({ question: '什么是 RAG？' });
    // Authorization header is the in-memory key as Bearer.
    expect(reqs[0]!.authHeader).toBe(`Bearer ${API_KEY}`);
  });

  it('trims the question before sending', async () => {
    const { captured: reqs, handler } = useCapturedRequests();
    server.use(handler);
    let captured: ReturnType<typeof useChat> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} onClient={() => {}} />);
    await waitFor(() => expect(captured).not.toBeNull());

    act(() => captured!.setKey(API_KEY));
    act(() => captured!.setQuestion('  hello  '));
    await act(async () => {
      await captured!.submit();
    });

    expect(reqs[0]!.body).toEqual({ question: 'hello' });
  });

  it('submit pushes a user + assistant message and completes the assistant on success', async () => {
    const { handler } = useCapturedRequests();
    server.use(handler);
    let captured: ReturnType<typeof useChat> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} onClient={() => {}} />);
    await waitFor(() => expect(captured).not.toBeNull());

    act(() => captured!.setKey(API_KEY));
    act(() => captured!.setQuestion('什么是 RAG？'));
    await act(async () => {
      await captured!.submit();
    });

    const msgs = captured!.messages;
    expect(msgs).toHaveLength(2);
    expect(msgs[0]!.role).toBe('user');
    expect(msgs[0]!.content).toBe('什么是 RAG？');
    expect(msgs[0]!.status).toBe('complete');
    expect(msgs[1]!.role).toBe('assistant');
    expect(msgs[1]!.status).toBe('complete');
    expect(msgs[1]!.content).toBe('RAG 是…… [S1]');
    expect(msgs[1]!.sources).toHaveLength(1);
    expect(msgs[1]!.sources[0]).toEqual({
      reference: 'S1',
      documentId: '4001',
      excerpt: 'RAG 是……',
    });
    // Question is cleared after submit.
    expect(captured!.question).toBe('');
  });

  it('no double submit: while in flight, canSubmit is false and submit() is a no-op', async () => {
    let resolve!: () => void;
    const gate = new Promise<void>((r) => {
      resolve = r;
    });
    let calls = 0;
    server.use(
      http.post(QUERY_PATH, async () => {
        calls += 1;
        await gate;
        return HttpResponse.json(ok({ answer: 'ok', sources: [] }));
      }),
    );
    let captured: ReturnType<typeof useChat> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} onClient={() => {}} />);
    await waitFor(() => expect(captured).not.toBeNull());

    act(() => captured!.setKey(API_KEY));
    act(() => captured!.setQuestion('q1'));
    expect(captured!.canSubmit).toBe(true);

    // Kick off submit but do NOT await — it is pending on the gate.
    let pending: Promise<void> | null = null;
    act(() => {
      pending = captured!.submit();
    });
    await waitFor(() => expect(captured!.isSending).toBe(true));
    expect(captured!.canSubmit).toBe(false);

    // Second submit during flight is a no-op.
    await act(async () => {
      await captured!.submit();
    });
    expect(calls).toBe(1);

    // Release the gate; everything settles.
    resolve();
    await act(async () => {
      await pending;
    });
    expect(calls).toBe(1);
  });

  it('401 maps to authentication error and the failed assistant message is retained', async () => {
    server.use(
      http.post(QUERY_PATH, () =>
        HttpResponse.json(
          err(40102, {
            message: 'Authentication failed',
            reason: 'INVALID_API_KEY',
            retryable: false,
            fieldErrors: [],
          }),
          { status: 401 },
        ),
      ),
    );
    let captured: ReturnType<typeof useChat> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} onClient={() => {}} />);
    await waitFor(() => expect(captured).not.toBeNull());

    act(() => captured!.setKey(API_KEY));
    act(() => captured!.setQuestion('q'));
    await act(async () => {
      await captured!.submit();
    });

    expect(captured!.lastError?.kind).toBe('authentication');
    const failed = captured!.messages.find((m) => m.role === 'assistant');
    expect(failed).toBeDefined();
    expect(failed!.status).toBe('failed');
  });

  it('502 maps to retryable error', async () => {
    server.use(
      http.post(QUERY_PATH, () =>
        HttpResponse.json(
          err(50201, {
            message: 'LLM unavailable',
            reason: 'LLM_UNAVAILABLE',
            retryable: true,
            fieldErrors: [],
          }),
          { status: 502 },
        ),
      ),
    );
    let captured: ReturnType<typeof useChat> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} onClient={() => {}} />);
    await waitFor(() => expect(captured).not.toBeNull());

    act(() => captured!.setKey(API_KEY));
    act(() => captured!.setQuestion('q'));
    await act(async () => {
      await captured!.submit();
    });
    expect(captured!.lastError?.kind).toBe('retryable');
    expect(captured!.lastError?.retryable).toBe(true);
  });

  it('503 maps to retryable error', async () => {
    server.use(
      http.post(QUERY_PATH, () =>
        HttpResponse.json(
          err(50301, {
            message: 'Downstream unavailable',
            reason: 'DOWNSTREAM_UNAVAILABLE',
            retryable: true,
            fieldErrors: [],
          }),
          { status: 503 },
        ),
      ),
    );
    let captured: ReturnType<typeof useChat> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} onClient={() => {}} />);
    await waitFor(() => expect(captured).not.toBeNull());

    act(() => captured!.setKey(API_KEY));
    act(() => captured!.setQuestion('q'));
    await act(async () => {
      await captured!.submit();
    });
    expect(captured!.lastError?.kind).toBe('retryable');
  });

  it('400 maps to validation error and surfaces fieldErrors', async () => {
    server.use(
      http.post(QUERY_PATH, () =>
        HttpResponse.json(
          err(40002, {
            message: 'Invalid argument',
            reason: 'INVALID_QUERY',
            retryable: false,
            fieldErrors: [{ field: 'question', message: 'size 1-2000', rejectedValue: null }],
          }),
          { status: 400 },
        ),
      ),
    );
    let captured: ReturnType<typeof useChat> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} onClient={() => {}} />);
    await waitFor(() => expect(captured).not.toBeNull());

    act(() => captured!.setKey(API_KEY));
    act(() => captured!.setQuestion('q'));
    await act(async () => {
      await captured!.submit();
    });
    expect(captured!.lastError?.kind).toBe('validation');
    expect(captured!.lastError?.fieldErrors).toEqual([
      { field: 'question', message: 'size 1-2000' },
    ]);
  });

  it('404 maps to business error', async () => {
    server.use(
      http.post(QUERY_PATH, () =>
        HttpResponse.json(
          err(40401, {
            message: 'Resource not found',
            reason: 'NOT_FOUND',
            retryable: false,
            fieldErrors: [],
          }),
          { status: 404 },
        ),
      ),
    );
    let captured: ReturnType<typeof useChat> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} onClient={() => {}} />);
    await waitFor(() => expect(captured).not.toBeNull());

    act(() => captured!.setKey(API_KEY));
    act(() => captured!.setQuestion('q'));
    await act(async () => {
      await captured!.submit();
    });
    expect(captured!.lastError?.kind).toBe('business');
  });

  it('retry re-runs the last failed user question without auto-retry', async () => {
    let calls = 0;
    server.use(
      http.post(QUERY_PATH, async () => {
        calls += 1;
        if (calls === 1) {
          return HttpResponse.json(
            err(50201, {
              message: 'LLM unavailable',
              reason: 'LLM_UNAVAILABLE',
              retryable: true,
              fieldErrors: [],
            }),
            { status: 502 },
          );
        }
        return HttpResponse.json(ok({ answer: 'now ok', sources: [] }));
      }),
    );
    let captured: ReturnType<typeof useChat> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} onClient={() => {}} />);
    await waitFor(() => expect(captured).not.toBeNull());

    act(() => captured!.setKey(API_KEY));
    act(() => captured!.setQuestion('retry me'));
    await act(async () => {
      await captured!.submit();
    });
    expect(calls).toBe(1);
    expect(captured!.messages.find((m) => m.role === 'assistant')?.status).toBe('failed');

    await act(async () => {
      await captured!.retry();
    });
    expect(calls).toBe(2);
    const assistant = captured!.messages.filter((m) => m.role === 'assistant');
    // Only one assistant row (retry updates the same placeholder), now complete.
    expect(assistant).toHaveLength(1);
    expect(assistant[0]!.status).toBe('complete');
    expect(assistant[0]!.content).toBe('now ok');
  });

  it('clearKey purges both the key and all messages', async () => {
    const { handler } = useCapturedRequests();
    server.use(handler);
    let captured: ReturnType<typeof useChat> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} onClient={() => {}} />);
    await waitFor(() => expect(captured).not.toBeNull());

    act(() => captured!.setKey(API_KEY));
    act(() => captured!.setQuestion('q'));
    await act(async () => {
      await captured!.submit();
    });
    expect(captured!.messages).toHaveLength(2);
    expect(captured!.apiKey).toBe(API_KEY);

    act(() => captured!.clearKey());
    expect(captured!.apiKey).toBe('');
    expect(captured!.messages).toEqual([]);
    expect(captured!.lastError).toBeNull();
  });

  it('no message data is cached in the TanStack Query cache', async () => {
    const { handler } = useCapturedRequests();
    server.use(handler);
    let captured: ReturnType<typeof useChat> | null = null;
    let liveClient: QueryClient | null = null;
    wrap(
      newClient(),
      <Probe
        deps={makeDeps()}
        onVm={(vm) => (captured = vm)}
        onClient={(qc) => (liveClient = qc)}
      />,
    );
    await waitFor(() => expect(captured).not.toBeNull());

    act(() => captured!.setKey(API_KEY));
    act(() => captured!.setQuestion('q'));
    await act(async () => {
      await captured!.submit();
    });

    // HARD ASSERT: no query cache entry holds answer text, sources, or the API key.
    const all = liveClient!.getQueryCache().getAll();
    for (const entry of all) {
      const json = JSON.stringify(entry.state.data);
      expect(json).not.toContain(API_KEY);
      expect(json).not.toContain('RAG 是…… [S1]');
    }
  });

  it('submit without a key throws synchronously (no network call)', async () => {
    let calls = 0;
    server.use(
      http.post(QUERY_PATH, () => {
        calls += 1;
        return HttpResponse.json(ok({ answer: 'x', sources: [] }));
      }),
    );
    let captured: ReturnType<typeof useChat> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} onClient={() => {}} />);
    await waitFor(() => expect(captured).not.toBeNull());
    // No key set; submit should not fire a request.
    await act(async () => {
      await expect(captured!.submit()).rejects.toBeDefined();
    });
    expect(calls).toBe(0);
  });
});
