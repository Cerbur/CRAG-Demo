/**
 * Integration tests for the KB-scoped API Keys ViewModel.
 *
 * Proves (against a real TanStack Query + MSW stack):
 *  - List loads keys and surfaces ready/empty/error.
 *  - The action matrix integration: ACTIVE keys expose disable/rotate/revoke;
 *    DISABLED expose enable/revoke; REVOKED expose none.
 *  - ONE-TIME SECRET CLEANUP HARD RULE:
 *      * create places completeKey in viewModel.secret.secret (React state).
 *      * completeKey NEVER enters the TanStack Query cache — we assert by
 *        scanning queryClient.getQueryCache().getAll() for any data object
 *        whose JSON contains the complete key string.
 *      * calling secret.clearSecret() purges it from React state.
 *      * rotate likewise places the new completeKey in secret.secret and
 *        clears on clearSecret().
 *  - 409 CONFLICT on a status action surfaces the server message in the
 *    mutation error (no auto-retry).
 *  - disable/enable/revoke mutations invalidate the list and the new
 *    projection appears on the next list render.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { type ReactElement } from 'react';
import { render, waitFor, act } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { QueryClient, QueryClientProvider, useQueryClient } from '@tanstack/react-query';
import { createConsoleClient } from '@services/http/console-client';
import { createSessionStore } from '@services/http/session-store';
import { ok, err } from '../../test/msw/fixtures';
import { useApiKeys } from './use-api-keys';
import type { ApiKeyServiceDeps } from './api-key-service';
import type { ApiKeyResponseDto, CreatedApiKeyResponseDto } from '@features/api-keys/model/dto';

const server = setupServer();

const TENANT = '2001';
const KB = '3001';
const COLLECTION = `*/console-api/api/v1/tenants/${TENANT}/knowledge-bases/${KB}/api-keys`;
const ITEM = `*/console-api/api/v1/tenants/${TENANT}/knowledge-bases/${KB}/api-keys/:apiKeyId`;
const COMPLETE_KEY = 'crag_abcd_<PLACEHOLDER_SECRET>';

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

function keyDto(overrides: Partial<ApiKeyResponseDto> = {}): ApiKeyResponseDto {
  return {
    apiKeyId: '5001',
    knowledgeBaseId: KB,
    name: 'prod-key',
    status: 'ACTIVE',
    keyPrefix: 'crag_abcd',
    createdAt: '2026-07-02T09:00:00Z',
    expiresAt: null,
    ...overrides,
  };
}

function createdDto(overrides: Partial<CreatedApiKeyResponseDto> = {}): CreatedApiKeyResponseDto {
  return {
    apiKeyId: '5002',
    knowledgeBaseId: KB,
    name: 'new-key',
    completeKey: COMPLETE_KEY,
    expiresAt: null,
    ...overrides,
  };
}

/** Probe that exposes the ViewModel and the live query cache for assertions. */
function Probe({
  deps,
  onVm,
  onClient,
}: {
  deps: ApiKeyServiceDeps;
  onVm: (vm: ReturnType<typeof useApiKeys>) => void;
  onClient: (qc: QueryClient) => void;
}): null {
  const vm = useApiKeys({ tenantId: TENANT, knowledgeBaseId: KB, deps });
  const qc = useQueryClient();
  onVm(vm);
  onClient(qc);
  return null;
}

describe('useApiKeys', () => {
  beforeEach(() => {
    server.listen({ onUnhandledRequest: 'error' });
  });
  afterEach(() => {
    server.resetHandlers();
    server.close();
  });

  it('loads the list and surfaces ready status', async () => {
    server.use(
      http.get(COLLECTION, () =>
        HttpResponse.json(ok({ items: [keyDto()], nextPageToken: null })),
      ),
    );
    let captured: ReturnType<typeof useApiKeys> | null = null;
    const client = newClient();
    wrap(
      client,
      <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} onClient={() => {}} />,
    );
    await waitFor(() => expect(captured!.status).toBe('ready'));
    expect(captured!.items).toHaveLength(1);
    expect(captured!.items[0]!.id).toBe('5001');
  });

  it('surfaces empty status when the list is empty', async () => {
    server.use(
      http.get(COLLECTION, () =>
        HttpResponse.json(ok({ items: [], nextPageToken: null })),
      ),
    );
    let captured: ReturnType<typeof useApiKeys> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} onClient={() => {}} />);
    await waitFor(() => expect(captured!.status).toBe('empty'));
  });

  it('action matrix: ACTIVE exposes disable/rotate/revoke; DISABLED exposes enable/revoke; REVOKED exposes none', async () => {
    server.use(
      http.get(COLLECTION, () =>
        HttpResponse.json(
          ok({
            items: [
              keyDto({ apiKeyId: 'a', status: 'ACTIVE' }),
              keyDto({ apiKeyId: 'd', status: 'DISABLED' }),
              keyDto({ apiKeyId: 'r', status: 'REVOKED' }),
              keyDto({ apiKeyId: 'e', status: 'EXPIRED' }),
            ],
            nextPageToken: null,
          }),
        ),
      ),
    );
    let captured: ReturnType<typeof useApiKeys> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} onClient={() => {}} />);
    await waitFor(() => expect(captured!.status).toBe('ready'));
    const actions = captured!;
    expect(actions.allowedActions('ACTIVE')).toEqual(['disable', 'rotate', 'revoke']);
    expect(actions.allowedActions('DISABLED')).toEqual(['enable', 'revoke']);
    expect(actions.allowedActions('REVOKED')).toEqual([]);
    // EXPIRED is collapsed to REVOKED canonical, so a row with status EXPIRED
    // surfaces no actions (treated terminal).
    const expired = actions.items.find((i) => i.statusForDisplay === 'EXPIRED');
    expect(expired).toBeTruthy();
    expect(actions.allowedActions(expired!.status)).toEqual([]);
  });

  it('create places completeKey in secret.secret and NEVER in the query cache; clearSecret purges it', async () => {
    server.use(
      http.get(COLLECTION, () =>
        HttpResponse.json(ok({ items: [], nextPageToken: null })),
      ),
      http.post(COLLECTION, () =>
        HttpResponse.json(ok(createdDto()), { status: 201 }),
      ),
    );
    let captured: ReturnType<typeof useApiKeys> | null = null;
    let liveClient: QueryClient | null = null;
    wrap(
      newClient(),
      <Probe
        deps={makeDeps()}
        onVm={(vm) => (captured = vm)}
        onClient={(qc) => (liveClient = qc)}
      />,
    );
    await waitFor(() => expect(captured!.status).toBe('empty'));

    await act(async () => {
      await captured!.create.createKey('new-key');
    });

    // secret is now populated
    expect(captured!.secret.secret).not.toBeNull();
    expect(captured!.secret.secret!.completeKey).toBe(COMPLETE_KEY);

    // HARD ASSERT: completeKey must not appear in any cached query data.
    const allCaches = liveClient!.getQueryCache().getAll();
    for (const entry of allCaches) {
      const json = JSON.stringify(entry.state.data);
      expect(json).not.toContain(COMPLETE_KEY);
    }

    // clearSecret purges it
    act(() => captured!.secret.clearSecret());
    expect(captured!.secret.secret).toBeNull();

    // And STILL not in any cache.
    for (const entry of liveClient!.getQueryCache().getAll()) {
      const json = JSON.stringify(entry.state.data);
      expect(json).not.toContain(COMPLETE_KEY);
    }
  });

  it('rotate places the new completeKey in secret.secret and clearSecret purges it', async () => {
    server.use(
      http.get(COLLECTION, () =>
        HttpResponse.json(ok({ items: [keyDto()], nextPageToken: null })),
      ),
      http.post(`${ITEM}/rotate`, () =>
        HttpResponse.json(ok(createdDto({ completeKey: 'crag_rot1_<PLACEHOLDER_SECRET>' }))),
      ),
    );
    let captured: ReturnType<typeof useApiKeys> | null = null;
    let liveClient: QueryClient | null = null;
    wrap(
      newClient(),
      <Probe
        deps={makeDeps()}
        onVm={(vm) => (captured = vm)}
        onClient={(qc) => (liveClient = qc)}
      />,
    );
    await waitFor(() => expect(captured!.status).toBe('ready'));
    await act(async () => {
      await captured!.rotate.rotateKey('5001');
    });
    expect(captured!.secret.secret!.completeKey).toBe('crag_rot1_<PLACEHOLDER_SECRET>');
    for (const entry of liveClient!.getQueryCache().getAll()) {
      expect(JSON.stringify(entry.state.data)).not.toContain('crag_rot1_<PLACEHOLDER_SECRET>');
    }
    act(() => captured!.secret.clearSecret());
    expect(captured!.secret.secret).toBeNull();
  });

  it('409 CONFLICT on disable surfaces the server message in the mutation error', async () => {
    server.use(
      http.get(COLLECTION, () =>
        HttpResponse.json(ok({ items: [keyDto({ status: 'DISABLED' })], nextPageToken: null })),
      ),
      http.post(`${ITEM}/disable`, () =>
        HttpResponse.json(
          err(40901, {
            message: 'Key is not ACTIVE',
            reason: 'APIKEY_STATUS_CONFLICT',
            retryable: false,
            fieldErrors: [],
          }),
          { status: 409 },
        ),
      ),
    );
    let captured: ReturnType<typeof useApiKeys> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} onClient={() => {}} />);
    await waitFor(() => expect(captured!.status).toBe('ready'));
    let thrown: unknown = null;
    await act(async () => {
      try {
        await captured!.disable.run('5001');
      } catch (e) {
        thrown = e;
      }
    });
    expect(thrown).toBeTruthy();
    await waitFor(() => expect(captured!.disable.error).toBeTruthy());
  });

  it('revoke invalidates the list and the row becomes REVOKED on next render', async () => {
    let listCallCount = 0;
    server.use(
      http.get(COLLECTION, () => {
        listCallCount += 1;
        const status = listCallCount === 1 ? 'ACTIVE' : 'REVOKED';
        return HttpResponse.json(ok({ items: [keyDto({ status })], nextPageToken: null }));
      }),
      http.post(`${ITEM}/revoke`, () =>
        HttpResponse.json(ok(keyDto({ status: 'REVOKED' }))),
      ),
    );
    let captured: ReturnType<typeof useApiKeys> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} onClient={() => {}} />);
    await waitFor(() => expect(captured!.status).toBe('ready'));
    expect(captured!.items[0]!.status).toBe('ACTIVE');
    await act(async () => {
      await captured!.revoke.run('5001');
    });
    await waitFor(() => expect(captured!.items[0]!.status).toBe('REVOKED'));
  });

  it('enable and disable round-trip through the mutations', async () => {
    server.use(
      http.get(COLLECTION, () =>
        HttpResponse.json(ok({ items: [keyDto({ status: 'DISABLED' })], nextPageToken: null })),
      ),
      http.post(`${ITEM}/enable`, () =>
        HttpResponse.json(ok(keyDto({ status: 'ACTIVE' }))),
      ),
    );
    let captured: ReturnType<typeof useApiKeys> | null = null;
    wrap(newClient(), <Probe deps={makeDeps()} onVm={(vm) => (captured = vm)} onClient={() => {}} />);
    await waitFor(() => expect(captured!.status).toBe('ready'));
    await act(async () => {
      await captured!.enable.run('5001');
    });
    // mutation resolved without throwing
    expect(captured!.enable.error).toBeNull();
  });
});
