import { describe, it, expect, afterEach } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { executeRequest, buildUrl } from './transport';
import type { ApiErrorException } from './api-error';
import { ok, err, FIXED_TRACE_ID } from '../../test/msw/fixtures';

let server: ReturnType<typeof setupServer> | null = null;
afterEach(() => {
  if (server) {
    server.close();
    server = null;
  }
});

function start(...handlers: ReadonlyArray<Parameters<typeof http.get>[1]>): void {
  server = setupServer(
    http.get('*/console-api/test', handlers[0]!),
    http.post('*/console-api/test', handlers[1] ?? handlers[0]!),
  );
  server.listen({ onUnhandledRequest: 'error' });
}

describe('buildUrl', () => {
  it('encodes query params', () => {
    expect(buildUrl('/x', { a: 'b', n: 1 })).toBe('/x?a=b&n=1');
  });
  it('handles repeated keys', () => {
    expect(buildUrl('/x', { id: ['1', '2'] })).toBe('/x?id=1&id=2');
  });
  it('skips null values', () => {
    expect(buildUrl('/x', { a: null, b: 'y' })).toBe('/x?b=y');
  });
  it('returns path when no query', () => {
    expect(buildUrl('/x')).toBe('/x');
  });
});

describe('executeRequest — envelope unwrap', () => {
  it('returns result on success envelope (code 0)', async () => {
    start(() => HttpResponse.json(ok({ hello: 'world' })));
    const { result } = await executeRequest({ method: 'GET', path: '/console-api/test' });
    expect(result).toEqual({ hello: 'world' });
  });

  it('treats 2xx with failure envelope as an error', async () => {
    start(() =>
      HttpResponse.json(err(50001, { message: 'Internal', reason: 'INTERNAL_ERROR' }), {
        status: 200,
      }),
    );
    await expect(
      executeRequest({ method: 'GET', path: '/console-api/test' }),
    ).rejects.toMatchObject({ name: 'ApiErrorException' });
  });
});

describe('executeRequest — field errors', () => {
  it('surfaces fieldErrors without rejectedValue', async () => {
    start(() =>
      HttpResponse.json(
        err(40001, {
          message: 'Validation failed',
          reason: 'VALIDATION_ERROR',
          fieldErrors: [
            { field: 'name', message: 'too long', rejectedValue: 'secret-should-not-leak' },
          ],
        }),
        { status: 400 },
      ),
    );
    try {
      await executeRequest({ method: 'GET', path: '/console-api/test' });
      throw new Error('should have thrown');
    } catch (e) {
      const apiErr = (e as ApiErrorException).apiError;
      expect(apiErr.fieldErrors).toEqual([{ field: 'name', message: 'too long' }]);
      expect(JSON.stringify(apiErr)).not.toContain('secret-should-not-leak');
    }
  });
});

describe('executeRequest — 502 / 503 / 504', () => {
  it('503 DOWNSTREAM_UNAVAILABLE maps to retryable', async () => {
    start(() =>
      HttpResponse.json(
        err(50301, {
          message: 'Downstream unavailable',
          reason: 'DOWNSTREAM_UNAVAILABLE',
          retryable: true,
        }),
        { status: 503 },
      ),
    );
    await expect(
      executeRequest({ method: 'GET', path: '/console-api/test' }),
    ).rejects.toMatchObject({
      apiError: { kind: 'retryable', retryable: true },
    });
  });
  it('502 LLM_UNAVAILABLE maps to retryable', async () => {
    start(() =>
      HttpResponse.json(
        err(50201, { message: 'LLM unavailable', reason: 'LLM_UNAVAILABLE', retryable: true }),
        { status: 502 },
      ),
    );
    await expect(
      executeRequest({ method: 'GET', path: '/console-api/test' }),
    ).rejects.toMatchObject({
      apiError: { kind: 'retryable' },
    });
  });
  it('504 DOWNSTREAM_TIMEOUT maps to retryable', async () => {
    start(() =>
      HttpResponse.json(
        err(50401, {
          message: 'Downstream timeout',
          reason: 'DOWNSTREAM_TIMEOUT',
          retryable: true,
        }),
        { status: 504 },
      ),
    );
    await expect(
      executeRequest({ method: 'GET', path: '/console-api/test' }),
    ).rejects.toMatchObject({
      apiError: { kind: 'retryable' },
    });
  });
});

describe('executeRequest — network / parse failures', () => {
  it('network failure becomes a retryable transport error', async () => {
    const fetchImpl = async () => {
      throw new TypeError('Failed to fetch');
    };
    await expect(
      executeRequest({ method: 'GET', path: '/console-api/test' }, { fetch: fetchImpl }),
    ).rejects.toMatchObject({
      apiError: { kind: 'retryable', retryable: true, message: 'Network error' },
    });
  });

  it('non-JSON body becomes a parse error', async () => {
    const fetchImpl = async () => new Response('<html>not json</html>', { status: 200 });
    await expect(
      executeRequest({ method: 'GET', path: '/console-api/test' }, { fetch: fetchImpl }),
    ).rejects.toMatchObject({
      apiError: { kind: 'unknown', retryable: false },
    });
  });
});

describe('executeRequest — 401 auth mark', () => {
  it('401 produces an auth-marked error', async () => {
    start(() =>
      HttpResponse.json(err(40101, { message: 'Unauthenticated', reason: 'UNAUTHENTICATED' }), {
        status: 401,
      }),
    );
    try {
      await executeRequest({ method: 'GET', path: '/console-api/test' });
      throw new Error('should have thrown');
    } catch (e) {
      const apiErr = (e as ApiErrorException).apiError as ApiErrorException['apiError'] & {
        status?: number;
        isAuthError?: boolean;
      };
      expect(apiErr.status).toBe(401);
      expect(apiErr.isAuthError).toBe(true);
      expect(apiErr.kind).toBe('authentication');
    }
  });

  it('logs only method/path/status/traceId', async () => {
    const logged: Array<Record<string, unknown>> = [];
    start(() => HttpResponse.json(ok({}), { headers: { 'X-Request-Id': FIXED_TRACE_ID } }));
    await executeRequest(
      {
        method: 'POST',
        path: '/console-api/test',
        body: { secret: 'never-log-me' },
        headers: { Authorization: 'Bearer super-secret-jwt' },
      },
      { log: (e) => logged.push({ ...e }) },
    );
    expect(logged).toHaveLength(1);
    const entry = logged[0]!;
    expect(Object.keys(entry).sort()).toEqual(['method', 'path', 'status', 'traceId']);
    expect(entry.traceId).toBe(FIXED_TRACE_ID);
    expect(JSON.stringify(entry)).not.toContain('super-secret-jwt');
    expect(JSON.stringify(entry)).not.toContain('never-log-me');
  });
});
