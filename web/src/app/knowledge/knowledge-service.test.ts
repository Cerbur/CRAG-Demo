/**
 * Tests for the app/knowledge knowledge-service. The service orchestrates the
 * Console client; it does NOT live under features/knowledge because the
 * architecture test forbids features/** from importing services/http.
 *
 * Covers: list with pageToken + pageSize, single-detail GET, create passes
 * body correctly, mapper is applied, errors propagate as ApiErrorException.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse, type HttpHandler } from 'msw';
import { createConsoleClient } from '@services/http/console-client';
import { createSessionStore, type SessionStore } from '@services/http/session-store';
import { ApiErrorException } from '@services/http/api-error';
import { ok, err } from '../../test/msw/fixtures';
import {
  listKnowledgeBases,
  createKnowledgeBase,
  getKnowledgeBase,
  type KnowledgeServiceDeps,
} from './knowledge-service';

let server: ReturnType<typeof setupServer> | null = null;
let sessionStore: SessionStore;

beforeEach(() => {
  sessionStore = createSessionStore();
  sessionStore.setAccessToken('<PLACEHOLDER_ACCESS_JWT>');
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

function deps(): KnowledgeServiceDeps {
  return { client: createConsoleClient({ sessionStore }) };
}

const kb = (id: string, apiKeyReady = true) => ({
  knowledgeBaseId: id,
  tenantId: '2001',
  name: `KB-${id}`,
  apiKeyReady,
  createdAt: '2026-07-02T09:00:00Z',
  updatedAt: '2026-07-02T09:00:00Z',
});

describe('listKnowledgeBases', () => {
  it('hits the tenant-scoped collection path with pageSize and empty pageToken', async () => {
    let calledPath = '';
    const calledQuery: Record<string, string[]> = {};
    start(
      http.get('*/console-api/api/v1/tenants/:tenantId/knowledge-bases', ({ params, request }) => {
        calledPath = params['tenantId'] as string;
        const url = new URL(request.url);
        for (const [k, v] of url.searchParams.entries()) {
          calledQuery[k] = (calledQuery[k] ?? []).concat(v);
        }
        return HttpResponse.json(ok({ items: [kb('3001')], nextPageToken: 'cur-1' }));
      }),
    );
    try {
      const page = await listKnowledgeBases(deps(), '2001', '', 15);
      expect(page.items[0]!.id).toBe('3001');
      expect(page.nextPageToken).toBe('cur-1');
      expect(calledPath).toBe('2001');
      expect(calledQuery).toEqual({ pageSize: ['15'] });
    } finally {
      stopServer();
    }
  });

  it('forwards pageToken when provided and omits pageSize when undefined', async () => {
    let calledQuery: Record<string, string[]> = {};
    start(
      http.get('*/console-api/api/v1/tenants/:tenantId/knowledge-bases', ({ request }) => {
        const url = new URL(request.url);
        calledQuery = {};
        for (const [k, v] of url.searchParams.entries()) {
          calledQuery[k] = (calledQuery[k] ?? []).concat(v);
        }
        return HttpResponse.json(ok({ items: [], nextPageToken: '' }));
      }),
    );
    try {
      const page = await listKnowledgeBases(deps(), '2001', 'next-cur');
      expect(page.items).toEqual([]);
      expect(page.nextPageToken).toBe('');
      expect(calledQuery).toEqual({ pageToken: ['next-cur'] });
    } finally {
      stopServer();
    }
  });

  it('propagates 503 as ApiErrorException with retryable kind', async () => {
    start(
      http.get('*/console-api/api/v1/tenants/:tenantId/knowledge-bases', () =>
        HttpResponse.json(
          err(50301, { message: 'down', reason: 'DOWNSTREAM_UNAVAILABLE', retryable: true }),
          { status: 503 },
        ),
      ),
    );
    try {
      await expect(listKnowledgeBases(deps(), '2001')).rejects.toMatchObject({
        name: 'ApiErrorException',
        apiError: { kind: 'retryable', retryable: true },
      });
    } finally {
      stopServer();
    }
  });
});

describe('createKnowledgeBase', () => {
  it('POSTs the name and returns the mapped KB on 201 partial-success (apiKeyReady=false)', async () => {
    let receivedBody: unknown = null;
    start(
      http.post('*/console-api/api/v1/tenants/:tenantId/knowledge-bases', async ({ request }) => {
        receivedBody = await request.json();
        return HttpResponse.json(ok(kb('3002', false)), { status: 201 });
      }),
    );
    try {
      const created = await createKnowledgeBase(deps(), '2001', '产品文档 v2');
      expect(created.id).toBe('3002');
      expect(created.apiKeyReady).toBe(false); // partial success, not an error
      expect(created.name).toBe('KB-3002');
      expect(receivedBody).toEqual({ name: '产品文档 v2' });
    } finally {
      stopServer();
    }
  });

  it('rejects an empty name before any HTTP call', async () => {
    let hit = false;
    start(
      http.post('*/console-api/api/v1/tenants/:tenantId/knowledge-bases', () => {
        hit = true;
        return HttpResponse.json(ok(kb('3003')));
      }),
    );
    try {
      await expect(createKnowledgeBase(deps(), '2001', '   ')).rejects.toThrow();
      expect(hit).toBe(false);
    } finally {
      stopServer();
    }
  });

  it('propagates 409 conflict as ApiErrorException', async () => {
    start(
      http.post('*/console-api/api/v1/tenants/:tenantId/knowledge-bases', () =>
        HttpResponse.json(
          err(40901, { message: 'duplicate name', reason: 'ALREADY_EXISTS' }),
          { status: 409 },
        ),
      ),
    );
    try {
      await expect(createKnowledgeBase(deps(), '2001', 'dup')).rejects.toBeInstanceOf(
        ApiErrorException,
      );
    } finally {
      stopServer();
    }
  });
});

describe('getKnowledgeBase', () => {
  it('GETs the item path and maps the result', async () => {
    let capturedId = '';
    start(
      http.get(
        '*/console-api/api/v1/tenants/:tenantId/knowledge-bases/:kbId',
        ({ params }) => {
          capturedId = params['kbId'] as string;
          return HttpResponse.json(ok(kb('3001')));
        },
      ),
    );
    try {
      const got = await getKnowledgeBase(deps(), '2001', '3001');
      expect(got.id).toBe('3001');
      expect(capturedId).toBe('3001');
    } finally {
      stopServer();
    }
  });

  it('propagates 404 not-found as ApiErrorException', async () => {
    start(
      http.get(
        '*/console-api/api/v1/tenants/:tenantId/knowledge-bases/:kbId',
        () =>
          HttpResponse.json(
            err(40401, { message: 'Resource not found', reason: 'NOT_FOUND' }),
            { status: 404 },
          ),
      ),
    );
    try {
      await expect(getKnowledgeBase(deps(), '2001', '9999')).rejects.toMatchObject({
        name: 'ApiErrorException',
      });
    } finally {
      stopServer();
    }
  });
});
