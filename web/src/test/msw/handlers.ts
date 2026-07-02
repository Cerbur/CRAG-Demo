/**
 * Reusable MSW handlers for the Console/Open API.
 *
 * Feature tests (22.3+) import {@link createConsoleHandlers} /
 * {@link createOpenHandlers} and merge their own handlers. The defaults here
 * cover the auth refresh/logout + a few generic passthrough stubs so 22.2's
 * own transport/client tests can compose the pieces they need.
 *
 * Security: handlers never log Authorization headers or response bodies.
 */
import { http, type HttpHandler, HttpResponse } from 'msw';
import { ok, err, authResponse, FIXED_TRACE_ID } from './fixtures';
import type { ErrorDetailDto } from '@services/http/types';

export interface RefreshOutcome {
  readonly status: 200 | 401 | 403 | 503;
  readonly body?: unknown;
}

export interface ConsoleHandlerOverrides {
  /**
   * Override the refresh outcome. If an array is supplied, successive refresh
   * calls return successive outcomes (last one repeats). Default: 200 with the
   * placeholder access JWT.
   */
  readonly refresh?: RefreshOutcome | ReadonlyArray<RefreshOutcome>;
}

function outcomeFor(index: number, list: ReadonlyArray<RefreshOutcome>): RefreshOutcome {
  return list[Math.min(index, list.length - 1)]!;
}

function refreshResponse(outcome: RefreshOutcome) {
  if (outcome.status === 200) {
    return HttpResponse.json(ok(outcome.body ?? authResponse()));
  }
  const detail: ErrorDetailDto =
    outcome.status === 401
      ? {
          message: 'Unauthenticated',
          traceId: FIXED_TRACE_ID,
          reason: 'UNAUTHENTICATED',
          retryable: false,
          fieldErrors: [],
        }
      : outcome.status === 403
        ? {
            message: 'Forbidden',
            traceId: FIXED_TRACE_ID,
            reason: 'CROSS_SITE_ORIGIN',
            retryable: false,
            fieldErrors: [],
          }
        : {
            message: 'Downstream unavailable',
            traceId: FIXED_TRACE_ID,
            reason: 'DOWNSTREAM_UNAVAILABLE',
            retryable: true,
            fieldErrors: [],
          };
  const code =
    detail.reason === 'UNAUTHENTICATED'
      ? 40101
      : detail.reason === 'CROSS_SITE_ORIGIN'
        ? 40301
        : 50301;
  return HttpResponse.json(err(code, detail), { status: outcome.status });
}

/**
 * Create a minimal set of Console handlers for transport/client tests. Each
 * handler counts invocations on the returned `calls` map so tests can assert
 * single-flight refresh and replay counts deterministically.
 */
export function createConsoleHandlers(overrides: ConsoleHandlerOverrides = {}): {
  readonly handlers: ReadonlyArray<HttpHandler>;
  readonly calls: {
    readonly refresh: () => number;
    readonly protected: () => number;
  };
} {
  let refreshCalls = 0;
  let protectedCalls = 0;

  const outcomes: ReadonlyArray<RefreshOutcome> = overrides.refresh
    ? Array.isArray(overrides.refresh)
      ? overrides.refresh
      : [overrides.refresh]
    : [{ status: 200 as const, body: authResponse({ accessToken: '<PLACEHOLDER_NEW_JWT>' }) }];

  const handlers: HttpHandler[] = [
    http.post('*/console-api/api/v1/auth/refresh', () => {
      const outcome = outcomeFor(refreshCalls, outcomes);
      refreshCalls += 1;
      return refreshResponse(outcome);
    }),
    http.get('*/console-api/api/v1/auth/me', () => {
      protectedCalls += 1;
      return HttpResponse.json(ok({ userId: '1001', nickname: 'alice' }));
    }),
    http.get('*/console-api/api/v1/tenants', () => {
      protectedCalls += 1;
      return HttpResponse.json(
        ok({
          items: [{ tenantId: '2001', name: 'alice 的默认租户', role: 'OWNER' }],
          nextPageToken: '',
        }),
      );
    }),
  ];

  return {
    handlers,
    calls: {
      refresh: () => refreshCalls,
      protected: () => protectedCalls,
    },
  };
}

export interface OpenHandlerOverrides {
  readonly queryStatus?: 200 | 400 | 401 | 404 | 502 | 503;
  readonly queryBody?: unknown;
}

/**
 * Create a minimal set of Open API handlers. The Open client never touches the
 * Console handlers, so this set is independent.
 */
export function createOpenHandlers(overrides: OpenHandlerOverrides = {}): {
  readonly handlers: ReadonlyArray<HttpHandler>;
  readonly calls: { readonly query: () => number };
} {
  let queryCalls = 0;
  const status = overrides.queryStatus ?? 200;
  const handlers: HttpHandler[] = [
    http.post('*/open-api/api/v1/query', ({ request }) => {
      queryCalls += 1;
      // Never log Authorization.
      void request;
      if (status === 200) {
        return HttpResponse.json(
          ok({
            answer: 'RAG 是一种结合检索与生成的架构。',
            sources: [{ reference: 'S1', documentId: '4001', excerpt: 'RAG 是……' }],
          }),
        );
      }
      const detail: ErrorDetailDto =
        status === 401
          ? {
              message: 'Authentication failed',
              traceId: FIXED_TRACE_ID,
              reason: 'INVALID_API_KEY',
              retryable: false,
              fieldErrors: [],
            }
          : status === 400
            ? {
                message: 'Invalid argument',
                traceId: FIXED_TRACE_ID,
                reason: 'INVALID_QUERY',
                retryable: false,
                fieldErrors: [{ field: 'question', message: 'size 1-2000', rejectedValue: null }],
              }
            : status === 502
              ? {
                  message: 'LLM unavailable',
                  traceId: FIXED_TRACE_ID,
                  reason: 'LLM_UNAVAILABLE',
                  retryable: true,
                  fieldErrors: [],
                }
              : status === 503
                ? {
                    message: 'Downstream unavailable',
                    traceId: FIXED_TRACE_ID,
                    reason: 'DOWNSTREAM_UNAVAILABLE',
                    retryable: true,
                    fieldErrors: [],
                  }
                : {
                    message: 'Resource not found',
                    traceId: FIXED_TRACE_ID,
                    reason: 'NOT_FOUND',
                    retryable: false,
                    fieldErrors: [],
                  };
      const code =
        status === 401
          ? 40102
          : status === 400
            ? 40002
            : status === 502
              ? 50201
              : status === 503
                ? 50301
                : 40401;
      return HttpResponse.json(err(code, detail), { status });
    }),
  ];
  return {
    handlers,
    calls: { query: () => queryCalls },
  };
}

/**
 * Build an MSW worker or server. Tests use `setupServer` from `msw/node`; the
 * browser worker is provided by 22.7+ when needed. Re-exported here as a single
 * entry so feature tests do not import msw internals directly.
 */
export { http, HttpResponse } from 'msw';
