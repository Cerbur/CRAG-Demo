/**
 * Integration tests for the Knowledge detail ViewModel.
 *
 * Proves:
 *  - Partial-success create navigation: the detail page loads a KB with
 *    apiKeyReady=false WITHOUT treating it as an error, and surfaces
 *    awaitingReadiness=true.
 *  - Readiness polling: while apiKeyReady=false the detail re-fetches; once the
 *    server reports apiKeyReady=true the polling stops.
 *  - Polling stops on unmount: after the consuming component unmounts, the MSW
 *    call count for GET /{id} does NOT increase (TanStack Query unsubscribes
 *    and stops the refetchInterval).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { type ReactElement } from 'react';
import { render } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createConsoleClient } from '@services/http/console-client';
import { createSessionStore } from '@services/http/session-store';
import { ok } from '../../test/msw/fixtures';
import { useKnowledgeDetail } from './use-knowledge-detail';
import type { KnowledgeServiceDeps } from './knowledge-service';

const server = setupServer();

function makeDeps(): KnowledgeServiceDeps {
  const sessionStore = createSessionStore();
  sessionStore.setAccessToken('<PLACEHOLDER_ACCESS_JWT>');
  return { client: createConsoleClient({ sessionStore }) };
}

function wrap(client: QueryClient, ui: ReactElement): ReturnType<typeof render> {
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

beforeEach(() => {
  server.listen({ onUnhandledRequest: 'error' });
  // Speed up the polling interval for the test. vi.useFakeTimers would also
  // freeze React state flushes; using a real (short) interval is simpler.
});
afterEach(() => {
  server.resetHandlers();
  server.close();
});

function Probe({
  deps,
  tenantId,
  knowledgeBaseId,
  pollIntervalMs,
}: {
  deps: KnowledgeServiceDeps;
  tenantId: string;
  knowledgeBaseId: string;
  pollIntervalMs?: number;
}): ReactElement {
  const vm = useKnowledgeDetail(
    pollIntervalMs === undefined
      ? { tenantId, knowledgeBaseId, deps }
      : { tenantId, knowledgeBaseId, deps, pollIntervalMs },
  );
  return (
    <div>
      <div data-testid="status">{vm.status}</div>
      <div data-testid="awaiting">{String(vm.awaitingReadiness)}</div>
      <div data-testid="ready">
        {vm.knowledgeBase ? String(vm.knowledgeBase.apiKeyReady) : 'no-data'}
      </div>
      <div data-testid="name">{vm.knowledgeBase?.name ?? ''}</div>
    </div>
  );
}

const kb = (id: string, apiKeyReady: boolean, name = `KB-${id}`) =>
  HttpResponse.json(
    ok({
      knowledgeBaseId: id,
      tenantId: '2001',
      name,
      apiKeyReady,
      createdAt: '2026-07-02T09:00:00Z',
      updatedAt: '2026-07-02T09:00:00Z',
    }),
  );

describe('useKnowledgeDetail', () => {
  it('loads a partial-success KB (apiKeyReady=false) as ready+awaiting, not error', async () => {
    const deps = makeDeps();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    server.use(
      http.get('*/console-api/api/v1/tenants/2001/knowledge-bases/3001', () =>
        kb('3001', false, 'partial'),
      ),
    );

    wrap(client, <Probe deps={deps} tenantId="2001" knowledgeBaseId="3001" />);

    // First render settles.
    const status = await renderAndAwaitStatus();
    expect(status).toBe('ready');
    const root = document.body;
    expect(root.querySelector('[data-testid="awaiting"]')?.textContent).toBe('true');
    expect(root.querySelector('[data-testid="name"]')?.textContent).toBe('partial');

    async function renderAndAwaitStatus(): Promise<string> {
      // Poll the DOM for the status to settle (avoids tight coupling to fake
      // timers / TanStack internals).
      for (let i = 0; i < 50; i++) {
        const el = document.querySelector('[data-testid="status"]');
        if (el?.textContent && el.textContent !== 'loading') return el.textContent;
        await new Promise((r) => setTimeout(r, 10));
      }
      throw new Error('status never settled');
    }
  });

  it('polls while apiKeyReady=false and stops once the server reports ready', async () => {
    const deps = makeDeps();
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false, refetchIntervalInBackground: false } },
    });

    let getCalls = 0;
    server.use(
      http.get('*/console-api/api/v1/tenants/2001/knowledge-bases/3002', () => {
        getCalls += 1;
        // First two responses not ready; third ready.
        const ready = getCalls >= 3;
        return kb('3002', ready);
      }),
    );

    const { unmount } = wrap(
      client,
      <Probe deps={deps} tenantId="2001" knowledgeBaseId="3002" pollIntervalMs={50} />,
    );

    // Wait until the polling converges to ready.
    await waitForTest(() => getCalls >= 3, 3000);
    // After ready, polling stops — give it a grace window and assert no growth.
    const callsAtReady = getCalls;
    await new Promise((r) => setTimeout(r, 400));
    expect(getCalls, 'polling must stop once apiKeyReady=true').toBe(callsAtReady);

    unmount();
  });

  it('stops polling when the consuming component unmounts', async () => {
    const deps = makeDeps();
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false, refetchIntervalInBackground: false } },
    });

    let getCalls = 0;
    server.use(
      http.get('*/console-api/api/v1/tenants/2001/knowledge-bases/3003', () => {
        getCalls += 1;
        // Always not-ready so polling keeps going indefinitely.
        return kb('3003', false);
      }),
    );

    const { unmount } = wrap(
      client,
      <Probe deps={deps} tenantId="2001" knowledgeBaseId="3003" pollIntervalMs={50} />,
    );

    // Wait for at least the initial fetch + one poll tick.
    await waitForTest(() => getCalls >= 1, 3000);
    const callsBeforeUnmount = getCalls;
    expect(callsBeforeUnmount, 'should have made at least one fetch').toBeGreaterThanOrEqual(1);

    unmount();

    // After unmount, wait long enough that we would have seen several polls if
    // the interval were still running.
    await new Promise((r) => setTimeout(r, 800));
    const callsAfter = getCalls;
    expect(
      callsAfter,
      `polling must stop on unmount; calls went ${callsBeforeUnmount} → ${callsAfter}`,
    ).toBe(callsBeforeUnmount);
  });
});

/** Poll a predicate with a timeout. */
async function waitForTest(pred: () => boolean, timeoutMs: number): Promise<void> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (pred()) return;
    await new Promise((r) => setTimeout(r, 20));
  }
  throw new Error(`waitForTest timed out after ${timeoutMs}ms`);
}

// Silence unused-import for vi if not used elsewhere here.
void vi;
