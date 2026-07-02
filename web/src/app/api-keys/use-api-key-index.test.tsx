/**
 * Integration tests for the standalone API Keys index ViewModel.
 *
 * Proves (against a real TanStack Query + MSW stack):
 *  - Aggregates keys across multiple KBs.
 *  - Concurrency cap of 4 is respected (peak simultaneous in-flight key-list
 *    requests <= 4).
 *  - PARTIAL FAILURE: when one KB's keys fetch fails, the successful KBs' keys
 *    still render and the failed KB is surfaced via failedKnowledgeBaseIds.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { type ReactElement } from 'react';
import { render, waitFor } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createConsoleClient } from '@services/http/console-client';
import { createSessionStore } from '@services/http/session-store';
import { ok, err } from '../../test/msw/fixtures';
import { useApiKeyIndex } from './use-api-key-index';
import type { ApiKeyServiceDeps } from './api-key-service';
import type { ApiKeyResponseDto } from '@features/api-keys/model/dto';

const server = setupServer();
const TENANT = '2001';
const KB_COLLECTION = `*/console-api/api/v1/tenants/${TENANT}/knowledge-bases`;

function makeDeps(): ApiKeyServiceDeps {
  const sessionStore = createSessionStore();
  sessionStore.setAccessToken('<PLACEHOLDER_ACCESS_JWT>');
  return { client: createConsoleClient({ sessionStore }) };
}

function newClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  });
}

function wrap(client: QueryClient, ui: ReactElement): ReturnType<typeof render> {
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

function keyDto(apiKeyId: string, kbId: string): ApiKeyResponseDto {
  return {
    apiKeyId,
    knowledgeBaseId: kbId,
    name: `key-${apiKeyId}`,
    status: 'ACTIVE',
    keyPrefix: 'crag_abcd',
    createdAt: '2026-07-02T09:00:00Z',
    expiresAt: null,
  };
}

function Probe({
  deps,
  onVm,
  concurrency = 4,
}: {
  deps: ApiKeyServiceDeps;
  onVm: (vm: ReturnType<typeof useApiKeyIndex>) => void;
  concurrency?: number;
}): null {
  const vm = useApiKeyIndex({ tenantId: TENANT, apiKeyDeps: deps, knowledgeDeps: deps, concurrency });
  onVm(vm);
  return null;
}

describe('useApiKeyIndex', () => {
  beforeEach(() => {
    server.listen({ onUnhandledRequest: 'error' });
  });
  afterEach(() => {
    server.resetHandlers();
    server.close();
  });

  it('aggregates keys across multiple KBs', async () => {
    server.use(
      http.get(KB_COLLECTION, () =>
        HttpResponse.json(
          ok({
            items: [
              { knowledgeBaseId: '3001', tenantId: TENANT, name: 'KB1', apiKeyReady: true, createdAt: '2026-07-02T09:00:00Z', updatedAt: '2026-07-02T09:00:00Z' },
              { knowledgeBaseId: '3002', tenantId: TENANT, name: 'KB2', apiKeyReady: true, createdAt: '2026-07-02T09:00:00Z', updatedAt: '2026-07-02T09:00:00Z' },
            ],
            nextPageToken: '',
          }),
        ),
      ),
      http.get(`*/console-api/api/v1/tenants/${TENANT}/knowledge-bases/3001/api-keys`, () =>
        HttpResponse.json(ok({ items: [keyDto('5001', '3001')], nextPageToken: null })),
      ),
      http.get(`*/console-api/api/v1/tenants/${TENANT}/knowledge-bases/3002/api-keys`, () =>
        HttpResponse.json(ok({ items: [keyDto('5002', '3002')], nextPageToken: null })),
      ),
    );
    let captured: ReturnType<typeof useApiKeyIndex> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} />);
    await waitFor(() => expect(captured!.status).toBe('ready'));
    expect(captured!.items).toHaveLength(2);
    expect(captured!.buckets).toHaveLength(2);
    expect(captured!.failedKnowledgeBaseIds).toEqual([]);
  });

  it('respects the concurrency cap of 4', async () => {
    // 6 KBs, each key-list handler tracks simultaneous in-flight count.
    let active = 0;
    let peak = 0;
    const kbs = Array.from({ length: 6 }, (_, i) => ({
      knowledgeBaseId: `300${i + 1}`,
      tenantId: TENANT,
      name: `KB${i + 1}`,
      apiKeyReady: true,
      createdAt: '2026-07-02T09:00:00Z',
      updatedAt: '2026-07-02T09:00:00Z',
    }));
    server.use(
      http.get(KB_COLLECTION, () =>
        HttpResponse.json(ok({ items: kbs, nextPageToken: '' })),
      ),
    );
    for (const kb of kbs) {
      server.use(
        http.get(`*/console-api/api/v1/tenants/${TENANT}/knowledge-bases/${kb.knowledgeBaseId}/api-keys`, async () => {
          active += 1;
          peak = Math.max(peak, active);
          await new Promise((r) => setTimeout(r, 20));
          active -= 1;
          return HttpResponse.json(ok({ items: [], nextPageToken: null }));
        }),
      );
    }
    let captured: ReturnType<typeof useApiKeyIndex> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} concurrency={4} />);
    await waitFor(() => expect(captured!.status).toBe('empty'));
    expect(peak).toBeLessThanOrEqual(4);
  });

  it('partial failure: a failed KB does not abort successful KBs', async () => {
    server.use(
      http.get(KB_COLLECTION, () =>
        HttpResponse.json(
          ok({
            items: [
              { knowledgeBaseId: '3001', tenantId: TENANT, name: 'OK-KB', apiKeyReady: true, createdAt: '2026-07-02T09:00:00Z', updatedAt: '2026-07-02T09:00:00Z' },
              { knowledgeBaseId: '3002', tenantId: TENANT, name: 'FAIL-KB', apiKeyReady: true, createdAt: '2026-07-02T09:00:00Z', updatedAt: '2026-07-02T09:00:00Z' },
            ],
            nextPageToken: '',
          }),
        ),
      ),
      http.get(`*/console-api/api/v1/tenants/${TENANT}/knowledge-bases/3001/api-keys`, () =>
        HttpResponse.json(ok({ items: [keyDto('5001', '3001')], nextPageToken: null })),
      ),
      http.get(`*/console-api/api/v1/tenants/${TENANT}/knowledge-bases/3002/api-keys`, () =>
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
    let captured: ReturnType<typeof useApiKeyIndex> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} />);
    await waitFor(() => expect(captured!.status).toBe('ready'));
    // Successful KB's key is present.
    expect(captured!.items.map((i) => i.id)).toContain('5001');
    // Failed KB surfaced.
    expect(captured!.failedKnowledgeBaseIds).toContain('3002');
    const failedBucket = captured!.buckets.find((b) => b.knowledgeBase.id === '3002');
    expect(failedBucket).toBeTruthy();
    expect(failedBucket!.errorMessage).not.toBeNull();
  });
});
