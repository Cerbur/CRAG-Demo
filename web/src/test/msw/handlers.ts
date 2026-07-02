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

/** Outcome shape for auth command (register/login/logout) handlers. */
export interface AuthOutcome {
  readonly status: 200 | 400 | 401 | 403 | 503;
  readonly body?: unknown;
}

export interface AuthHandlerOverrides {
  /**
   * Register outcome. Default: 200 with a placeholder AuthResponse including
   * the OWNER defaultTenant.
   */
  readonly register?: AuthOutcome;
  /** Login outcome. Default: 200 with placeholder AuthResponse (defaultTenant null). */
  readonly login?: AuthOutcome;
  /** Logout outcome. Default: 204 empty. */
  readonly logout?: AuthOutcome;
}

function authResponseFor(outcome: AuthOutcome, fallback: unknown): unknown {
  if (outcome.body !== undefined) return outcome.body;
  return fallback;
}

function errorEnvelope(status: number): { code: number; detail: ErrorDetailDto } {
  // Map status to a representative business code + reason.
  if (status === 400) {
    return {
      code: 40001,
      detail: {
        message: 'Validation failed',
        traceId: FIXED_TRACE_ID,
        reason: 'VALIDATION_ERROR',
        retryable: false,
        fieldErrors: [{ field: 'username', message: 'invalid', rejectedValue: null }],
      },
    };
  }
  if (status === 401) {
    return {
      code: 40102,
      detail: {
        message: 'Invalid credentials',
        traceId: FIXED_TRACE_ID,
        reason: 'INVALID_CREDENTIALS',
        retryable: false,
        fieldErrors: [],
      },
    };
  }
  if (status === 403) {
    return {
      code: 40301,
      detail: {
        message: 'Forbidden',
        traceId: FIXED_TRACE_ID,
        reason: 'CROSS_SITE_ORIGIN',
        retryable: false,
        fieldErrors: [],
      },
    };
  }
  return {
    code: 50301,
    detail: {
      message: 'Downstream unavailable',
      traceId: FIXED_TRACE_ID,
      reason: 'DOWNSTREAM_UNAVAILABLE',
      retryable: true,
      fieldErrors: [],
    },
  };
}

function authOutcomeResponse(outcome: AuthOutcome, successBody: unknown) {
  if (outcome.status === 200) {
    return HttpResponse.json(ok(authResponseFor(outcome, successBody)));
  }
  const { code, detail } = errorEnvelope(outcome.status);
  return HttpResponse.json(err(code, detail), { status: outcome.status });
}

/**
 * Create auth + tenant handlers for the auth feature tests (22.3). The returned
 * handlers cover register, login, logout, /me, /tenants and refresh so feature
 * tests can compose a complete server. Call counters let tests assert which
 * paths were hit.
 */
export function createAuthHandlers(overrides: AuthHandlerOverrides = {}): {
  readonly handlers: ReadonlyArray<HttpHandler>;
  readonly calls: {
    readonly register: () => number;
    readonly login: () => number;
    readonly logout: () => number;
    readonly refresh: () => number;
    readonly me: () => number;
    readonly tenants: () => number;
  };
} {
  let registerCalls = 0;
  let loginCalls = 0;
  let logoutCalls = 0;
  let refreshCalls = 0;
  let meCalls = 0;
  let tenantsCalls = 0;

  const register = overrides.register ?? { status: 200 as const };
  const login = overrides.login ?? { status: 200 as const };
  const logout = overrides.logout ?? { status: 200 as const };

  const registerSuccess = authResponse({
    accessToken: '<PLACEHOLDER_ACCESS_JWT>',
    defaultTenant: { tenantId: '2001', name: 'alice 的默认租户', role: 'OWNER' },
  });
  const loginSuccess = authResponse({
    accessToken: '<PLACEHOLDER_ACCESS_JWT>',
    defaultTenant: null,
  });

  const handlers: HttpHandler[] = [
    http.post('*/console-api/api/v1/auth/register', () => {
      registerCalls += 1;
      return authOutcomeResponse(register, registerSuccess);
    }),
    http.post('*/console-api/api/v1/auth/login', () => {
      loginCalls += 1;
      return authOutcomeResponse(login, loginSuccess);
    }),
    http.post('*/console-api/api/v1/auth/logout', () => {
      logoutCalls += 1;
      if (logout.status === 200) return new HttpResponse(null, { status: 204 });
      const { code, detail } = errorEnvelope(logout.status);
      return HttpResponse.json(err(code, detail), { status: logout.status });
    }),
    http.post('*/console-api/api/v1/auth/refresh', () => {
      refreshCalls += 1;
      return HttpResponse.json(
        ok(authResponse({ accessToken: '<PLACEHOLDER_NEW_JWT>', defaultTenant: null })),
      );
    }),
    http.get('*/console-api/api/v1/auth/me', () => {
      meCalls += 1;
      return HttpResponse.json(ok({ userId: '1001', nickname: 'alice' }));
    }),
    http.get('*/console-api/api/v1/tenants', () => {
      tenantsCalls += 1;
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
      register: () => registerCalls,
      login: () => loginCalls,
      logout: () => logoutCalls,
      refresh: () => refreshCalls,
      me: () => meCalls,
      tenants: () => tenantsCalls,
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

/** KnowledgeBase fixture shape used by the Knowledge handlers below. */
interface KnowledgeBaseFixture {
  readonly knowledgeBaseId: string;
  readonly tenantId: string;
  readonly name: string;
  readonly apiKeyReady: boolean;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface KnowledgeHandlerOverrides {
  /** Items returned on the first list page (pageToken=''). */
  readonly items?: ReadonlyArray<KnowledgeBaseFixture>;
  /** nextPageToken returned on the first list page. '' = no more pages. */
  readonly nextPageToken?: string;
  /** Outcome of POST create. status 201 = success (apiKeyReady per body). */
  readonly create?: {
    readonly status?: 201 | 400 | 401 | 403 | 409 | 503;
    readonly body?: KnowledgeBaseFixture;
  };
  /** Outcome of GET /{id}. Default: returns the matching item from `items`. */
  readonly detail?: {
    readonly status?: 200 | 401 | 403 | 404 | 503;
    readonly body?: KnowledgeBaseFixture;
  };
}

/**
 * Create Knowledge handlers (list/create/get) for the Knowledge feature tests
 * (22.4+). The default state mirrors the OpenAPI examples: one ready KB on page
 * 1, empty nextPageToken, create returns 201 ready, detail returns the matching
 * item. Tests override individual outcomes as needed.
 *
 * The handlers track call counts so tests can assert paging and polling
 * deterministically. No secrets are logged.
 */
export function createKnowledgeHandlers(
  tenantId: string,
  overrides: KnowledgeHandlerOverrides = {},
): {
  readonly handlers: ReadonlyArray<HttpHandler>;
  readonly calls: {
    readonly list: () => number;
    readonly create: () => number;
    readonly detail: () => number;
  };
} {
  let listCalls = 0;
  let createCalls = 0;
  let detailCalls = 0;

  const items = overrides.items ?? [
    {
      knowledgeBaseId: '3001',
      tenantId,
      name: '产品文档',
      apiKeyReady: true,
      createdAt: '2026-07-02T09:00:00Z',
      updatedAt: '2026-07-02T09:00:00Z',
    },
  ];
  const nextPageToken = overrides.nextPageToken ?? '';

  const collectionPath = `*/console-api/api/v1/tenants/${tenantId}/knowledge-bases`;
  const itemPath = `*/console-api/api/v1/tenants/${tenantId}/knowledge-bases/:kbId`;

  const handlers: HttpHandler[] = [
    http.get(collectionPath, ({ request }) => {
      listCalls += 1;
      const url = new URL(request.url);
      const token = url.searchParams.get('pageToken') ?? '';
      // For the first page return the configured items/token; for subsequent
      // pages return empty by default (tests can override behaviour by
      // replacing the handler).
      if (token === '') {
        return HttpResponse.json(ok({ items, nextPageToken }));
      }
      return HttpResponse.json(ok({ items: [], nextPageToken: '' }));
    }),
    http.post(collectionPath, async () => {
      createCalls += 1;
      const outcome = overrides.create;
      const status = outcome?.status ?? 201;
      if (status === 201) {
        const body =
          outcome?.body ??
          ({
            knowledgeBaseId: '3002',
            tenantId,
            name: '新建知识库',
            apiKeyReady: true,
            createdAt: '2026-07-02T09:00:00Z',
            updatedAt: '2026-07-02T09:00:00Z',
          } as KnowledgeBaseFixture);
        return HttpResponse.json(ok(body), { status: 201 });
      }
      const { code, detail } = errorEnvelope(status);
      return HttpResponse.json(err(code, detail), { status });
    }),
    http.get(itemPath, ({ params }) => {
      detailCalls += 1;
      const outcome = overrides.detail;
      if (outcome?.status && outcome.status !== 200) {
        const { code, detail } = errorEnvelope(outcome.status);
        return HttpResponse.json(err(code, detail), { status: outcome.status });
      }
      const id = params['kbId'] as string;
      const match = items.find((i) => i.knowledgeBaseId === id);
      if (match) {
        return HttpResponse.json(ok(match));
      }
      if (outcome?.body) {
        return HttpResponse.json(ok(outcome.body));
      }
      // Default 404 for unknown ids.
      const { code, detail } = errorEnvelope(404);
      return HttpResponse.json(err(code, detail), { status: 404 });
    }),
  ];

  return {
    handlers,
    calls: {
      list: () => listCalls,
      create: () => createCalls,
      detail: () => detailCalls,
    },
  };
}

