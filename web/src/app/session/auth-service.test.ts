/**
 * Tests for the app/session auth-service. The auth-service orchestrates the
 * Console client, SessionStore and mappers; it does NOT live under
 * features/auth because the architecture test forbids features/** from
 * importing services/http.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse, type HttpHandler } from 'msw';
import { createConsoleClient } from '@services/http/console-client';
import { createSessionStore, type SessionStore } from '@services/http/session-store';
import { ApiErrorException } from '@services/http/api-error';
import { ok, err, FIXED_TRACE_ID } from '../../test/msw/fixtures';
import {
  loginWithCredentials,
  registerNewUser,
  restoreFromCookie,
  type AuthServiceDeps,
} from './auth-service';

let server: ReturnType<typeof setupServer> | null = null;
let sessionStore: SessionStore;

beforeEach(() => {
  sessionStore = createSessionStore();
});

function stopServer(): void {
  if (server) {
    server.close();
    server = null;
  }
}

function start(...handlers: ReadonlyArray<HttpHandler>): void {
  server = setupServer(...handlers);
  server.listen({ onUnhandledRequest: 'error' });
}

function deps(): AuthServiceDeps {
  return {
    client: createConsoleClient({ sessionStore }),
    sessionStore,
  };
}

const registerResult = () => ({
  accessToken: '<PLACEHOLDER_ACCESS_JWT>',
  accessExpiresAt: '2026-07-02T12:00:00Z',
  user: { userId: '1001', nickname: 'alice' },
  defaultTenant: { tenantId: '2001', name: 'alice 的默认租户', role: 'OWNER' },
});

const loginResult = () => ({
  accessToken: '<PLACEHOLDER_ACCESS_JWT>',
  accessExpiresAt: '2026-07-02T12:00:00Z',
  user: { userId: '1001', nickname: 'alice' },
  defaultTenant: null,
});

const tenantsResult = () => ({
  items: [{ tenantId: '2001', name: 'alice 的默认租户', role: 'OWNER' }],
  nextPageToken: '',
});

describe('registerNewUser', () => {
  it('stores token and returns session built from defaultTenant', async () => {
    let registerCalledWith: unknown = null;
    start(
      http.post('*/console-api/api/v1/auth/register', async ({ request }) => {
        registerCalledWith = await request.json();
        return HttpResponse.json(ok(registerResult()));
      }),
    );
    try {
      const { session } = await registerNewUser(deps(), {
        nickname: 'alice',
        username: 'alice',
        password: 'password123456',
      });
      expect(session).toEqual({
        userId: '1001',
        nickname: 'alice',
        tenantId: '2001',
        role: 'OWNER',
      });
      expect(sessionStore.getAccessToken()).toBe('<PLACEHOLDER_ACCESS_JWT>');
      expect(registerCalledWith).toEqual({
        nickname: 'alice',
        username: 'alice',
        password: 'password123456',
      });
    } finally {
      stopServer();
    }
  });

  it('propagates 400 validation errors as ApiErrorException', async () => {
    start(
      http.post('*/console-api/api/v1/auth/register', () =>
        HttpResponse.json(
          err(40001, {
            message: 'Validation failed',
            reason: 'VALIDATION_ERROR',
            fieldErrors: [
              { field: 'username', message: 'already taken', rejectedValue: null },
            ],
          }),
          { status: 400 },
        ),
      ),
    );
    try {
      await expect(
        registerNewUser(deps(), {
          nickname: 'a',
          username: 'alice',
          password: 'password123456',
        }),
      ).rejects.toMatchObject({
        name: 'ApiErrorException',
        apiError: {
          kind: 'validation',
          fieldErrors: [{ field: 'username', message: 'already taken' }],
        },
      });
      expect(sessionStore.getAccessToken()).toBe(null);
    } finally {
      stopServer();
    }
  });

  it('does not store token on failure', async () => {
    start(
      http.post('*/console-api/api/v1/auth/register', () =>
        HttpResponse.json(err(40102, { message: 'bad', reason: 'INVALID_CREDENTIALS' }), {
          status: 401,
        }),
      ),
    );
    try {
      await expect(
        registerNewUser(deps(), { nickname: 'a', username: 'u', password: 'password123456' }),
      ).rejects.toBeInstanceOf(ApiErrorException);
      expect(sessionStore.getAccessToken()).toBe(null);
    } finally {
      stopServer();
    }
  });
});

describe('loginWithCredentials', () => {
  it('recovers tenant via GET /tenants and stores token', async () => {
    let loginCalledWith: unknown = null;
    let tenantsCalls = 0;
    start(
      http.post('*/console-api/api/v1/auth/login', async ({ request }) => {
        loginCalledWith = await request.json();
        return HttpResponse.json(ok(loginResult()));
      }),
      http.get('*/console-api/api/v1/tenants', () => {
        tenantsCalls += 1;
        return HttpResponse.json(ok(tenantsResult()));
      }),
    );
    try {
      const { session } = await loginWithCredentials(deps(), {
        username: 'alice',
        password: 'password123456',
      });
      expect(session.tenantId).toBe('2001');
      expect(session.role).toBe('OWNER');
      expect(sessionStore.getAccessToken()).toBe('<PLACEHOLDER_ACCESS_JWT>');
      expect(tenantsCalls).toBe(1);
      expect(loginCalledWith).toEqual({ username: 'alice', password: 'password123456' });
    } finally {
      stopServer();
    }
  });

  it('maps 401 invalid credentials to authentication kind', async () => {
    start(
      http.post('*/console-api/api/v1/auth/login', () =>
        HttpResponse.json(err(40102, { message: 'Invalid credentials', reason: 'INVALID_CREDENTIALS' }), {
          status: 401,
        }),
      ),
    );
    try {
      await expect(
        loginWithCredentials(deps(), { username: 'alice', password: 'wrong' }),
      ).rejects.toMatchObject({
        name: 'ApiErrorException',
        apiError: { kind: 'authentication', message: 'Invalid credentials' },
      });
      expect(sessionStore.getAccessToken()).toBe(null);
    } finally {
      stopServer();
    }
  });

  it('maps 403 cross-site origin to authorization kind', async () => {
    start(
      http.post('*/console-api/api/v1/auth/login', () =>
        HttpResponse.json(err(40301, { message: 'Forbidden', reason: 'CROSS_SITE_ORIGIN' }), {
          status: 403,
        }),
      ),
    );
    try {
      await expect(
        loginWithCredentials(deps(), { username: 'alice', password: 'x' }),
      ).rejects.toMatchObject({
        apiError: { kind: 'authorization', retryable: false },
      });
    } finally {
      stopServer();
    }
  });
});

describe('restoreFromCookie', () => {
  it('returns a session when refresh cookie is valid (refresh on first /me 401)', async () => {
    // First /me 401 (no access token yet) → Console client single-flight refreshes
    // via the cookie → token written → /me replayed with 200.
    let meCalls = 0;
    start(
      http.get('*/console-api/api/v1/auth/me', () => {
        meCalls += 1;
        if (meCalls === 1) {
          return HttpResponse.json(
            err(40101, { message: 'Unauthenticated', reason: 'UNAUTHENTICATED' }),
            { status: 401 },
          );
        }
        return HttpResponse.json(ok({ userId: '1001', nickname: 'alice' }));
      }),
      http.post('*/console-api/api/v1/auth/refresh', () =>
        HttpResponse.json(ok(loginResult())),
      ),
      http.get('*/console-api/api/v1/tenants', () =>
        HttpResponse.json(ok(tenantsResult())),
      ),
    );
    try {
      const session = await restoreFromCookie(deps());
      expect(session?.tenantId).toBe('2001');
      expect(session?.nickname).toBe('alice');
      expect(sessionStore.getAccessToken()).toBe('<PLACEHOLDER_ACCESS_JWT>');
    } finally {
      stopServer();
    }
  });

  it('returns null when /me rejects with 401 and refresh fails', async () => {
    start(
      http.get('*/console-api/api/v1/auth/me', () =>
        HttpResponse.json(err(40101, { message: 'Unauthenticated', reason: 'UNAUTHENTICATED' }), {
          status: 401,
        }),
      ),
      http.post('*/console-api/api/v1/auth/refresh', () =>
        HttpResponse.json(err(40101, { message: 'Unauthenticated', reason: 'UNAUTHENTICATED' }), {
          status: 401,
        }),
      ),
    );
    try {
      const session = await restoreFromCookie(deps());
      expect(session).toBe(null);
      expect(sessionStore.getAccessToken()).toBe(null);
    } finally {
      stopServer();
    }
  });

  it('returns null on 403 cross-site origin without throwing', async () => {
    start(
      http.get('*/console-api/api/v1/auth/me', () =>
        HttpResponse.json(err(40301, { message: 'Forbidden', reason: 'CROSS_SITE_ORIGIN' }), {
          status: 403,
        }),
      ),
    );
    try {
      const session = await restoreFromCookie(deps());
      expect(session).toBe(null);
    } finally {
      stopServer();
    }
  });

  it('returns null when tenant list is empty', async () => {
    start(
      http.get('*/console-api/api/v1/auth/me', () =>
        HttpResponse.json(ok({ userId: '1001', nickname: 'alice' })),
      ),
      http.get('*/console-api/api/v1/tenants', () =>
        HttpResponse.json(ok({ items: [], nextPageToken: '' })),
      ),
    );
    try {
      const session = await restoreFromCookie(deps());
      expect(session).toBe(null);
    } finally {
      stopServer();
    }
  });
});

// Silence the unused-variable check for the trace fixture imported for completeness.
void FIXED_TRACE_ID;
