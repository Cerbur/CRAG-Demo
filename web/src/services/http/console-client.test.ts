import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse, type HttpHandler } from 'msw';
import { createConsoleClient } from './console-client';
import { createOpenClient } from './open-client';
import { createSessionStore, type SessionStore } from './session-store';
import { ApiErrorException } from './api-error';
import { authResponse, ok, err, FIXED_TRACE_ID } from '../../test/msw/fixtures';

let server: ReturnType<typeof setupServer> | null = null;
let refreshCalls = 0;
let protectedCalls = 0;
const firstProtectedStatuses: number[] = [];

function start(...handlers: ReadonlyArray<HttpHandler>): void {
  server = setupServer(...handlers);
  server.listen({ onUnhandledRequest: 'error' });
}

beforeEach(() => {
  refreshCalls = 0;
  protectedCalls = 0;
  firstProtectedStatuses.length = 0;
});

afterEach(() => {
  if (server) {
    server.close();
    server = null;
  }
});

/** Build a protected GET that returns the configured status, advancing per-call.
 *  Only the very first pass of statuses is recorded for assertions. */
function protectedHandler(statuses: ReadonlyArray<number>): HttpHandler {
  return http.get('*/console-api/api/v1/tenants', () => {
    protectedCalls += 1;
    const idx = Math.min(protectedCalls - 1, statuses.length - 1);
    const status = statuses[idx]!;
    // Record only the initial pass (before any replay).
    if (protectedCalls <= statuses.length) firstProtectedStatuses.push(status);
    if (status === 200) {
      return HttpResponse.json(
        ok({ items: [{ tenantId: '2001', name: 't', role: 'OWNER' }], nextPageToken: '' }),
      );
    }
    return HttpResponse.json(
      err(40101, { message: 'Unauthenticated', reason: 'UNAUTHENTICATED' }),
      { status },
    );
  });
}

function refreshHandler(outcomes: ReadonlyArray<{ status: number; body?: unknown }>): HttpHandler {
  let seq = 0;
  return http.post('*/console-api/api/v1/auth/refresh', () => {
    refreshCalls += 1;
    const o = outcomes[Math.min(seq, outcomes.length - 1)]!;
    seq += 1;
    if (o.status === 200)
      return HttpResponse.json(
        ok(o.body ?? authResponse({ accessToken: '<PLACEHOLDER_NEW_JWT>' })),
      );
    return HttpResponse.json(err(40101), { status: o.status });
  });
}

let sessionStore: SessionStore;
beforeEach(() => {
  sessionStore = createSessionStore();
  sessionStore.setAccessToken('<PLACEHOLDER_ACCESS_JWT>');
});

describe('consoleClient — single-flight refresh + replay', () => {
  it('concurrent 401s trigger exactly one refresh; each request replays once and succeeds', async () => {
    // First two tenant calls 401; refresh 200; subsequent calls 200.
    start(protectedHandler([401, 401, 200, 200]), refreshHandler([{ status: 200 }]));
    const client = createConsoleClient({ sessionStore });

    const [a, b] = await Promise.all([
      client.request({ method: 'GET', path: '/console-api/api/v1/tenants' }),
      client.request({ method: 'GET', path: '/console-api/api/v1/tenants' }),
    ]);

    expect((a as { items: unknown[] }).items).toHaveLength(1);
    expect((b as { items: unknown[] }).items).toHaveLength(1);
    expect(refreshCalls).toBe(1); // single-flight invariant
    // 2 initial 401 calls + 2 replays = 4 total protected calls.
    expect(protectedCalls).toBe(4);
    // The first pass (calls 1 and 2) both returned 401 before refresh.
    expect(firstProtectedStatuses.slice(0, 2)).toEqual([401, 401]);
  });

  it('refresh failure clears the session and rejects every queued request', async () => {
    const clearSpy: string[] = [];
    const observedStore: SessionStore = {
      getAccessToken: sessionStore.getAccessToken.bind(sessionStore),
      setAccessToken: sessionStore.setAccessToken.bind(sessionStore),
      clear: () => {
        clearSpy.push('cleared');
        sessionStore.clear();
      },
      subscribe: sessionStore.subscribe.bind(sessionStore),
    };
    start(protectedHandler([401, 401]), refreshHandler([{ status: 401 }]));
    const client = createConsoleClient({ sessionStore: observedStore });

    const results = await Promise.allSettled([
      client.request({ method: 'GET', path: '/console-api/api/v1/tenants' }),
      client.request({ method: 'GET', path: '/console-api/api/v1/tenants' }),
    ]);

    expect(results.every((r) => r.status === 'rejected')).toBe(true);
    expect(refreshCalls).toBe(1);
    // Each queued request clears the session on failure; SessionStore.clear()
    // is idempotent (second call is a no-op), but the spy records both calls.
    expect(clearSpy.length).toBeGreaterThanOrEqual(1);
    expect(observedStore.getAccessToken()).toBe(null);
  });

  it('a request that already replayed once does not refresh again on a second 401', async () => {
    // Protected endpoint always 401; refresh 200; replay also 401 — must NOT loop.
    start(protectedHandler([401, 401, 401]), refreshHandler([{ status: 200 }]));
    const client = createConsoleClient({ sessionStore });

    await expect(
      client.request({ method: 'GET', path: '/console-api/api/v1/tenants' }),
    ).rejects.toMatchObject({ name: 'ApiErrorException' });
    // One initial call + exactly one replay.
    expect(protectedCalls).toBe(2);
    expect(refreshCalls).toBe(1);
  });

  it('re-success request re-attaches the new token from SessionStore', async () => {
    start(protectedHandler([401, 200]), refreshHandler([{ status: 200 }]));
    const client = createConsoleClient({ sessionStore });
    await client.request({ method: 'GET', path: '/console-api/api/v1/tenants' });
    expect(sessionStore.getAccessToken()).toBe('<PLACEHOLDER_NEW_JWT>');
    expect(refreshCalls).toBe(1);
    expect(protectedCalls).toBe(2);
  });

  it('refresh request itself uses credentials: include', async () => {
    let seq = 0;
    const localServer = setupServer(
      http.post('*/console-api/api/v1/auth/refresh', () => {
        seq += 1;
        return HttpResponse.json(ok(authResponse({ accessToken: '<PLACEHOLDER_NEW_JWT>' })));
      }),
      http.get('*/console-api/api/v1/tenants', () => {
        if (seq === 0) {
          return HttpResponse.json(err(40101), { status: 401 });
        }
        return HttpResponse.json(ok({ items: [], nextPageToken: '' }));
      }),
    );
    localServer.listen({ onUnhandledRequest: 'error' });
    // Custom fetch that captures credentials (avoid naming the DOM-only lib
    // type RequestCredentials so eslint no-undef stays happy).
    let capturedCreds: string | undefined;
    const spyFetch: typeof fetch = async (input, init) => {
      capturedCreds = init?.credentials;
      return fetch(input, init);
    };
    try {
      const client = createConsoleClient({ sessionStore, fetch: spyFetch });
      await client.request({ method: 'GET', path: '/console-api/api/v1/tenants' });
      expect(capturedCreds).toBe('include');
    } finally {
      localServer.close();
    }
  });
});

describe('openClient — isolation from Console refresh', () => {
  it('Open 401 does NOT trigger Console refresh', async () => {
    start(
      http.post('*/open-api/api/v1/query', () =>
        HttpResponse.json(
          err(40102, { message: 'Authentication failed', reason: 'INVALID_API_KEY' }),
          {
            status: 401,
          },
        ),
      ),
      // Console refresh handler — must never be hit.
      http.post('*/console-api/api/v1/auth/refresh', () => {
        refreshCalls += 1;
        return HttpResponse.json(ok(authResponse()));
      }),
    );
    const open = createOpenClient();
    await expect(
      open.request({
        method: 'POST',
        path: '/open-api/api/v1/query',
        body: { question: 'q?' },
        bearerApiKey: 'crag_test_key',
      }),
    ).rejects.toMatchObject({
      apiError: { kind: 'authentication', message: 'Authentication failed' },
    });
    expect(refreshCalls).toBe(0); // Open isolation invariant
  });

  it('Open client requires a per-request bearerApiKey', async () => {
    const open = createOpenClient();
    await expect(
      open.request({ method: 'POST', path: '/open-api/api/v1/query', body: { question: 'q?' } }),
    ).rejects.toThrow(/bearerApiKey/);
  });

  it('Open client does not read SessionStore', async () => {
    // If it read the store, supplying a key would still go through and the
    // Authorization would come from the store. We assert via the wire: only the
    // supplied key must appear.
    let observedAuth: string | null = null;
    const localServer = setupServer(
      http.post('*/open-api/api/v1/query', ({ request }) => {
        observedAuth = request.headers.get('Authorization');
        return HttpResponse.json(ok({ answer: 'a', sources: [] }));
      }),
    );
    localServer.listen({ onUnhandledRequest: 'error' });
    try {
      const open = createOpenClient();
      await open.request({
        method: 'POST',
        path: '/open-api/api/v1/query',
        body: { question: 'q?' },
        bearerApiKey: 'crag_open_supplied_key',
      });
      expect(observedAuth).toBe('Bearer crag_open_supplied_key');
    } finally {
      localServer.close();
    }
  });

  it('Open 503 maps to retryable (LLM/downstream) without refresh', async () => {
    start(
      http.post('*/open-api/api/v1/query', () =>
        HttpResponse.json(
          err(50301, {
            message: 'Downstream unavailable',
            reason: 'DOWNSTREAM_UNAVAILABLE',
            retryable: true,
          }),
          { status: 503 },
        ),
      ),
    );
    const open = createOpenClient();
    await expect(
      open.request({
        method: 'POST',
        path: '/open-api/api/v1/query',
        body: { question: 'q?' },
        bearerApiKey: 'crag_test_key',
      }),
    ).rejects.toMatchObject({ apiError: { kind: 'retryable' } });
  });
});

describe('ApiErrorException carries ApiError', () => {
  it('exposes .apiError for callers', async () => {
    start(protectedHandler([401]), refreshHandler([{ status: 401 }]));
    const client = createConsoleClient({ sessionStore });
    try {
      await client.request({ method: 'GET', path: '/console-api/api/v1/tenants' });
      throw new Error('should have thrown');
    } catch (e) {
      expect(e).toBeInstanceOf(ApiErrorException);
      const apiErr = (e as ApiErrorException).apiError;
      expect(apiErr.traceId).toBe(FIXED_TRACE_ID);
    }
  });
});

describe('skip-refresh auth endpoints', () => {
  it('login 401 does NOT trigger refresh — credential failure surfaces as-is', async () => {
    start(
      http.post('*/console-api/api/v1/auth/login', () =>
        HttpResponse.json(
          err(40102, { message: 'Invalid credentials', reason: 'INVALID_CREDENTIALS' }),
          { status: 401 },
        ),
      ),
      http.post('*/console-api/api/v1/auth/refresh', () => {
        refreshCalls += 1;
        return HttpResponse.json(ok(authResponse()));
      }),
    );
    const client = createConsoleClient({ sessionStore });
    await expect(
      client.request({
        method: 'POST',
        path: '/console-api/api/v1/auth/login',
        body: { username: 'x', password: 'y' },
      }),
    ).rejects.toMatchObject({
      apiError: { kind: 'authentication', message: 'Invalid credentials' },
    });
    expect(refreshCalls, 'login 401 must not trigger refresh').toBe(0);
  });

  it('register 401 does NOT trigger refresh', async () => {
    start(
      http.post('*/console-api/api/v1/auth/register', () =>
        HttpResponse.json(err(40102, { reason: 'INVALID_CREDENTIALS' }), { status: 401 }),
      ),
      http.post('*/console-api/api/v1/auth/refresh', () => {
        refreshCalls += 1;
        return HttpResponse.json(ok(authResponse()));
      }),
    );
    const client = createConsoleClient({ sessionStore });
    await expect(
      client.request({
        method: 'POST',
        path: '/console-api/api/v1/auth/register',
        body: { nickname: 'a', username: 'a', password: 'a' },
      }),
    ).rejects.toMatchObject({ apiError: { kind: 'authentication' } });
    expect(refreshCalls).toBe(0);
  });

  it('/auth/me 401 DOES trigger refresh (bootstrap recovery)', async () => {
    let meCalls = 0;
    start(
      http.get('*/console-api/api/v1/auth/me', () => {
        meCalls += 1;
        if (meCalls === 1) {
          return HttpResponse.json(err(40101, { reason: 'UNAUTHENTICATED' }), { status: 401 });
        }
        return HttpResponse.json(ok({ userId: '1', nickname: 'a' }));
      }),
      http.post('*/console-api/api/v1/auth/refresh', () => {
        refreshCalls += 1;
        return HttpResponse.json(ok(authResponse({ accessToken: '<PLACEHOLDER_NEW_JWT>' })));
      }),
    );
    const client = createConsoleClient({ sessionStore });
    const r = await client.request({ method: 'GET', path: '/console-api/api/v1/auth/me' });
    expect((r as { userId: string }).userId).toBe('1');
    expect(refreshCalls).toBe(1);
    expect(sessionStore.getAccessToken()).toBe('<PLACEHOLDER_NEW_JWT>');
  });
});
