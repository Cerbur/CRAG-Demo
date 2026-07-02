import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { http, HttpResponse, type HttpHandler } from 'msw';
import { createConsoleClient } from '@services/http/console-client';
import { createSessionStore, type SessionStore } from '@services/http/session-store';
import { ok, err } from '../../test/msw/fixtures';
import {
  apiErrorToFieldErrors,
  useLoginViewModel,
  useRegisterViewModel,
} from './use-auth-view-model';
import type { AuthSession } from '@entities/session';

let server: ReturnType<typeof setupServer> | null = null;
let sessionStore: SessionStore;

beforeEach(() => {
  sessionStore = createSessionStore();
});

function start(...handlers: ReadonlyArray<HttpHandler>): void {
  server = setupServer(...handlers);
  server.listen({ onUnhandledRequest: 'error' });
}

function stop(): void {
  if (server) {
    server.close();
    server = null;
  }
}

function deps() {
  return { client: createConsoleClient({ sessionStore }), sessionStore };
}

function renderLogin() {
  return renderHook(() =>
    useLoginViewModel({
      deps: deps(),
    }),
  );
}

function renderRegister() {
  return renderHook(() =>
    useRegisterViewModel({
      deps: deps(),
    }),
  );
}

describe('apiErrorToFieldErrors', () => {
  it('maps validation fieldErrors to the bag', () => {
    const out = apiErrorToFieldErrors({
      kind: 'validation',
      message: 'x',
      retryable: false,
      fieldErrors: [
        { field: 'username', message: 'taken' },
        { field: 'password', message: 'weak' },
      ],
    });
    expect(out).toEqual({ username: 'taken', password: 'weak' });
  });

  it('maps authentication to a generic _form error', () => {
    const out = apiErrorToFieldErrors({
      kind: 'authentication',
      message: 'Invalid credentials',
      retryable: false,
      fieldErrors: [],
    });
    expect(out).toEqual({ _form: 'Authentication failed' });
  });

  it('maps authorization (CROSS_SITE_ORIGIN) to a _form error', () => {
    const out = apiErrorToFieldErrors({
      kind: 'authorization',
      message: 'Forbidden',
      retryable: false,
      fieldErrors: [],
    });
    expect(out._form).toBeDefined();
  });

  it('maps retryable to a retry prompt', () => {
    const out = apiErrorToFieldErrors({
      kind: 'retryable',
      message: 'down',
      retryable: true,
      fieldErrors: [],
    });
    expect(out._form).toMatch(/retry/);
  });
});

describe('useLoginViewModel', () => {
  it('status starts idle and goes submitting → authenticated on success', async () => {
    start(
      http.post('*/console-api/api/v1/auth/login', () =>
        HttpResponse.json(
          ok({
            accessToken: '<PLACEHOLDER_ACCESS_JWT>',
            accessExpiresAt: '2026-07-02T12:00:00Z',
            user: { userId: '1001', nickname: 'alice' },
            defaultTenant: null,
          }),
        ),
      ),
      http.get('*/console-api/api/v1/tenants', () =>
        HttpResponse.json(
          ok({
            items: [{ tenantId: '2001', name: 't', role: 'OWNER' }],
            nextPageToken: '',
          }),
        ),
      ),
    );
    try {
      const { result } = renderLogin();
      expect(result.current.status).toBe('idle');
      await act(async () => {
        await result.current.submit({ username: 'alice', password: 'password123456' });
      });
      expect(result.current.status).toBe('authenticated');
      expect(result.current.session?.tenantId).toBe('2001');
      expect(sessionStore.getAccessToken()).toBe('<PLACEHOLDER_ACCESS_JWT>');
    } finally {
      stop();
    }
  });

  it('400 validation surfaces field errors and status error', async () => {
    start(
      http.post('*/console-api/api/v1/auth/login', () =>
        HttpResponse.json(
          err(40001, {
            message: 'validation',
            reason: 'VALIDATION_ERROR',
            fieldErrors: [{ field: 'username', message: 'too short', rejectedValue: null }],
          }),
          { status: 400 },
        ),
      ),
    );
    try {
      const { result } = renderLogin();
      await act(async () => {
        await result.current.submit({ username: 'alice', password: 'password123456' });
      });
      expect(result.current.status).toBe('error');
      expect(result.current.fieldErrors.username).toBe('too short');
      expect(sessionStore.getAccessToken()).toBe(null);
    } finally {
      stop();
    }
  });

  it('401 invalid credentials surfaces a generic _form error', async () => {
    start(
      http.post('*/console-api/api/v1/auth/login', () =>
        HttpResponse.json(
          err(40102, { message: 'Invalid credentials', reason: 'INVALID_CREDENTIALS' }),
          { status: 401 },
        ),
      ),
    );
    try {
      const { result } = renderLogin();
      await act(async () => {
        await result.current.submit({ username: 'alice', password: 'wrong' });
      });
      expect(result.current.status).toBe('error');
      expect(result.current.fieldErrors._form).toBe('Authentication failed');
    } finally {
      stop();
    }
  });

  it('403 cross-site origin surfaces a _form error and does not refresh', async () => {
    let refreshCalls = 0;
    start(
      http.post('*/console-api/api/v1/auth/login', () =>
        HttpResponse.json(err(40301, { message: 'Forbidden', reason: 'CROSS_SITE_ORIGIN' }), {
          status: 403,
        }),
      ),
      http.post('*/console-api/api/v1/auth/refresh', () => {
        refreshCalls += 1;
        return HttpResponse.json(ok({}));
      }),
    );
    try {
      const { result } = renderLogin();
      await act(async () => {
        await result.current.submit({ username: 'alice', password: 'x' });
      });
      expect(result.current.status).toBe('error');
      expect(result.current.fieldErrors._form).toBeDefined();
      expect(refreshCalls).toBe(0);
    } finally {
      stop();
    }
  });

  it('client-side Zod rejection surfaces field errors without a network call', async () => {
    let loginCalls = 0;
    start(
      http.post('*/console-api/api/v1/auth/login', () => {
        loginCalls += 1;
        return HttpResponse.json(ok({}));
      }),
    );
    try {
      const { result } = renderLogin();
      await act(async () => {
        await result.current.submit({ username: '', password: 'password123456' });
      });
      expect(result.current.status).toBe('error');
      expect(result.current.fieldErrors.username).toBeDefined();
      expect(loginCalls).toBe(0);
    } finally {
      stop();
    }
  });

  it('invokes onAuthenticated with the new session on success', async () => {
    const captured: AuthSession[] = [];
    start(
      http.post('*/console-api/api/v1/auth/login', () =>
        HttpResponse.json(
          ok({
            accessToken: '<PLACEHOLDER_ACCESS_JWT>',
            accessExpiresAt: '2026-07-02T12:00:00Z',
            user: { userId: '1001', nickname: 'alice' },
            defaultTenant: null,
          }),
        ),
      ),
      http.get('*/console-api/api/v1/tenants', () =>
        HttpResponse.json(
          ok({
            items: [{ tenantId: '2001', name: 't', role: 'OWNER' }],
            nextPageToken: '',
          }),
        ),
      ),
    );
    try {
      const { result } = renderHook(() =>
        useLoginViewModel({
          deps: deps(),
          onAuthenticated: (s) => captured.push(s),
        }),
      );
      await act(async () => {
        await result.current.submit({ username: 'alice', password: 'password123456' });
      });
      expect(captured).toHaveLength(1);
      expect(captured[0]?.userId).toBe('1001');
    } finally {
      stop();
    }
  });
});

describe('useRegisterViewModel', () => {
  it('status goes authenticated on success and stores token', async () => {
    start(
      http.post('*/console-api/api/v1/auth/register', () =>
        HttpResponse.json(
          ok({
            accessToken: '<PLACEHOLDER_ACCESS_JWT>',
            accessExpiresAt: '2026-07-02T12:00:00Z',
            user: { userId: '1001', nickname: 'alice' },
            defaultTenant: { tenantId: '2001', name: 't', role: 'OWNER' },
          }),
        ),
      ),
    );
    try {
      const { result } = renderRegister();
      await act(async () => {
        await result.current.submit({
          nickname: 'alice',
          username: 'alice',
          password: 'password123456',
          confirmPassword: 'password123456',
        });
      });
      expect(result.current.status).toBe('authenticated');
      expect(result.current.session?.tenantId).toBe('2001');
      expect(sessionStore.getAccessToken()).toBe('<PLACEHOLDER_ACCESS_JWT>');
    } finally {
      stop();
    }
  });

  it('client-side mismatched confirmPassword surfaces a confirmPassword error', async () => {
    const { result } = renderRegister();
    await act(async () => {
      await result.current.submit({
        nickname: 'alice',
        username: 'alice',
        password: 'password123456',
        confirmPassword: 'different123456',
      });
    });
    expect(result.current.status).toBe('error');
    expect(result.current.fieldErrors.confirmPassword).toBeDefined();
  });

  it('reset returns status to idle and clears fieldErrors', async () => {
    const { result } = renderRegister();
    await act(async () => {
      await result.current.submit({
        nickname: 'alice',
        username: 'a',
        password: 'password123456',
        confirmPassword: 'different123456',
      });
    });
    expect(result.current.status).toBe('error');
    act(() => {
      result.current.reset();
    });
    expect(result.current.status).toBe('idle');
    expect(result.current.fieldErrors).toEqual({});
  });
});
