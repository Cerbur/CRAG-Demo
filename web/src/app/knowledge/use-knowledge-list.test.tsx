/**
 * Integration tests for the Knowledge list ViewModel.
 *
 * Proves:
 *  - pageToken paging: Next advances via the returned token; Back returns to
 *    the previous cursor; hasNextPage/hasPreviousPage flags are correct.
 *  - empty / error states are surfaced correctly.
 *
 * Uses a real TanStack Query + MSW stack (no mocks of useQuery itself) so the
 * paging behaviour is exercised end-to-end.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { type ReactElement, useState } from 'react';
import { render, screen, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createConsoleClient } from '@services/http/console-client';
import { createSessionStore } from '@services/http/session-store';
import { ok, err } from '../../test/msw/fixtures';
import { useKnowledgeList } from './use-knowledge-list';
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
});
afterEach(() => {
  server.resetHandlers();
  server.close();
});

/**
 * Probe component that renders the current page items and exposes Next/Back
 * buttons wired to the ViewModel so the test can drive navigation via
 * userEvent (the same path the View uses).
 */
function Probe({ deps }: { deps: KnowledgeServiceDeps }): ReactElement {
  const vm = useKnowledgeList({ tenantId: '2001', deps });
  return (
    <div>
      <ul data-testid="items">
        {vm.items.map((i) => (
          <li key={i.id}>{i.id}</li>
        ))}
      </ul>
      <div data-testid="status">{vm.status}</div>
      <div data-testid="hasNext">{String(vm.hasNextPage)}</div>
      <div data-testid="hasPrev">{String(vm.hasPreviousPage)}</div>
      <button type="button" onClick={() => vm.gotoNextPage()} disabled={!vm.hasNextPage}>
        next
      </button>
      <button type="button" onClick={() => vm.gotoPreviousPage()} disabled={!vm.hasPreviousPage}>
        prev
      </button>
    </div>
  );
}

const kb = (id: string, name: string, nextPageToken: string) =>
  HttpResponse.json(
    ok({
      items: [
        {
          knowledgeBaseId: id,
          tenantId: '2001',
          name,
          apiKeyReady: true,
          createdAt: '2026-07-02T09:00:00Z',
          updatedAt: '2026-07-02T09:00:00Z',
        },
      ],
      nextPageToken,
    }),
  );

describe('useKnowledgeList pageToken paging', () => {
  it('loads first page, advances via Next, returns via Back', async () => {
    const deps = makeDeps();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    server.use(
      http.get('*/console-api/api/v1/tenants/2001/knowledge-bases', ({ request }) => {
        const url = new URL(request.url);
        const token = url.searchParams.get('pageToken') ?? '';
        if (token === '') return kb('3001', 'first', 'page2');
        if (token === 'page2') return kb('3002', 'second', '');
        return HttpResponse.json(ok({ items: [], nextPageToken: '' }));
      }),
    );

    wrap(client, <Probe deps={deps} />);

    // First page.
    await screen.findByTestId('status');
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(screen.getByTestId('status').textContent).toBe('ready');
    expect(screen.getByTestId('items').textContent).toContain('3001');
    expect(screen.getByTestId('hasNext').textContent).toBe('true');
    expect(screen.getByTestId('hasPrev').textContent).toBe('false');

    // Go Next.
    const user = userEvent.setup();
    await user.click(screen.getByText('next'));

    await screen.findByText('3002', undefined, { timeout: 3000 });
    expect(screen.getByTestId('items').textContent).toContain('3002');
    expect(screen.getByTestId('hasNext').textContent).toBe('false');
    expect(screen.getByTestId('hasPrev').textContent).toBe('true');

    // Go Back to first page.
    await user.click(screen.getByText('prev'));
    await screen.findByText('3001', undefined, { timeout: 3000 });
    expect(screen.getByTestId('items').textContent).toContain('3001');
    expect(screen.getByTestId('items').textContent).not.toContain('3002');
    expect(screen.getByTestId('hasNext').textContent).toBe('true');
    expect(screen.getByTestId('hasPrev').textContent).toBe('false');
  });

  it('surfaces empty status when the list has no items', async () => {
    const deps = makeDeps();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    server.use(
      http.get('*/console-api/api/v1/tenants/2001/knowledge-bases', () =>
        HttpResponse.json(ok({ items: [], nextPageToken: '' })),
      ),
    );

    wrap(client, <Probe deps={deps} />);

    await screen.findByTestId('status');
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(screen.getByTestId('status').textContent).toBe('empty');
  });

  it('surfaces error status on 503', async () => {
    const deps = makeDeps();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    server.use(
      http.get('*/console-api/api/v1/tenants/2001/knowledge-bases', () =>
        HttpResponse.json(
          err(50301, { message: 'down', reason: 'DOWNSTREAM_UNAVAILABLE', retryable: true }),
          { status: 503 },
        ),
      ),
    );

    wrap(client, <Probe deps={deps} />);

    await screen.findByTestId('status');
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(screen.getByTestId('status').textContent).toBe('error');
  });
});

// Suppress unused-import warnings for useState (kept for future extension).
void useState;
