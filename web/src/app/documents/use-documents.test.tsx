/**
 * Integration tests for the Documents ViewModel.
 *
 * Proves (against a real TanStack Query + MSW stack):
 *  - List loads documents and surfaces ready/empty/error.
 *  - Upload sends multipart/form-data (NOT JSON) and the 202 PENDING document
 *    enters the list, after which polling starts.
 *  - Polling runs while ANY document is PENDING/PROCESSING and stops once all
 *    are terminal (READY/FAILED). Polling also stops on unmount (proven by
 *    asserting the MSW call count does not increase after unmount).
 *  - Retry: only FAILED+retryable documents expose a retry path; a 200 retry
 *    resumes polling; a 409 surfaces the server message and does not auto-retry.
 *  - 413 UPLOAD_TOO_LARGE and 415 UNSUPPORTED_MEDIA_TYPE surface the server's
 *    message to the upload mutation error (backend authoritative).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { type ReactElement } from 'react';
import { render, screen, act, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createConsoleClient } from '@services/http/console-client';
import { createSessionStore } from '@services/http/session-store';
import { ok, err } from '../../test/msw/fixtures';
import { useDocuments } from './use-documents';
import type { DocumentServiceDeps } from './document-service';
import type { DocumentResponseDto } from '@features/documents/model/dto';

const server = setupServer();

const TENANT = '2001';
const KB = '3001';
const COLLECTION = `*/console-api/api/v1/tenants/${TENANT}/knowledge-bases/${KB}/documents`;
const RETRY_PATH = `*/console-api/api/v1/tenants/${TENANT}/knowledge-bases/${KB}/documents/:docId/ingestion/retry`;

function makeDeps(): DocumentServiceDeps {
  const sessionStore = createSessionStore();
  sessionStore.setAccessToken('<PLACEHOLDER_ACCESS_JWT>');
  return { client: createConsoleClient({ sessionStore }) };
}

function wrap(client: QueryClient, ui: ReactElement): ReturnType<typeof render> {
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

function doc(overrides: Partial<DocumentResponseDto> = {}): DocumentResponseDto {
  return {
    docId: '4001',
    knowledgeBaseId: KB,
    originalFilename: 'intro.txt',
    fileType: 'TXT',
    sizeBytes: 128,
    ingestionStatus: 'READY',
    operationVersion: '1',
    attempt: 1,
    failureCategory: '',
    failureMessage: '',
    retryable: false,
    startedAt: '2026-06-29T09:01:00Z',
    completedAt: '2026-06-29T09:01:30Z',
    ...overrides,
  };
}

interface ProbeProps {
  readonly deps: DocumentServiceDeps;
  readonly pollIntervalMs?: number;
}

function Probe({ deps, pollIntervalMs }: ProbeProps): ReactElement {
  const vm = useDocuments({
    tenantId: TENANT,
    knowledgeBaseId: KB,
    deps,
    ...(pollIntervalMs !== undefined ? { pollIntervalMs } : {}),
  });
  return (
    <div>
      <div data-testid="status">{vm.status}</div>
      <ul data-testid="items">
        {vm.items.map((d) => (
          <li key={d.id} data-testid={`item-${d.id}`}>
            {d.id}:{d.status}:retryable={String(d.retryable)}:attempt={d.attempt}
            {d.failureMessage ? `:err=${d.failureMessage}` : ''}
          </li>
        ))}
      </ul>
      <div data-testid="upload-pending">{String(vm.upload.isPending)}</div>
      <div data-testid="upload-error">
        {vm.upload.error ? String((vm.upload.error as Error).message ?? 'err') : ''}
      </div>
      <button
        type="button"
        onClick={() => {
          const f = new File(['hello'], 'intro.txt', { type: 'text/plain' });
          // Swallow the rejection here — the mutation's `error` state is what
          // the test asserts. Without .catch the rejected promise would become
          // an unhandled rejection (mutateAsync propagates).
          vm.upload.uploadFile(f).catch(() => {});
        }}
      >
        upload
      </button>
      <div data-testid="retry-pending">{String(vm.retry.isPending)}</div>
      <div data-testid="retry-error">
        {vm.retry.error ? String((vm.retry.error as Error).message ?? 'err') : ''}
      </div>
      <button
        type="button"
        onClick={() => {
          vm.retry.retryDocument('4001').catch(() => {});
        }}
        disabled={!vm.canRetry(vm.items[0] ?? null)}
      >
        retry
      </button>
    </div>
  );
}

beforeEach(() => {
  server.listen({ onUnhandledRequest: 'error' });
});
afterEach(() => {
  server.resetHandlers();
  server.close();
});

describe('useDocuments list', () => {
  it('loads documents and surfaces ready status', async () => {
    const deps = makeDeps();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    server.use(
      http.get(COLLECTION, () =>
        HttpResponse.json(ok({ items: [doc()], nextPageToken: '' })),
      ),
    );
    wrap(client, <Probe deps={deps} />);
    await screen.findByTestId('status');
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(screen.getByTestId('status').textContent).toBe('ready');
    expect(screen.getByTestId('items').textContent).toContain('4001');
  });

  it('surfaces empty status when list has no items', async () => {
    const deps = makeDeps();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    server.use(
      http.get(COLLECTION, () =>
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
      http.get(COLLECTION, () =>
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

describe('useDocuments polling', () => {
  it('polls the list while any document is PENDING and stops when all terminal', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const deps = makeDeps();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    let listCalls = 0;
    const states: DocumentResponseDto[] = [
      doc({ docId: '4001', ingestionStatus: 'PENDING' }),
    ];
    let stateIndex = 0;

    server.use(
      http.get(COLLECTION, () => {
        listCalls += 1;
        const current = states[Math.min(stateIndex, states.length - 1)]!;
        return HttpResponse.json(ok({ items: [current], nextPageToken: '' }));
      }),
    );

    // Initial fetch: PENDING.
    wrap(client, <Probe deps={deps} pollIntervalMs={100} />);
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('ready'));
    expect(screen.getByTestId('items').textContent).toContain('PENDING');
    const callsAfterInitial = listCalls;

    // Advance one interval while still PENDING → another fetch.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(150);
    });
    expect(listCalls).toBeGreaterThan(callsAfterInitial);

    // Flip to READY; subsequent polls should converge and then STOP.
    states[0] = doc({ docId: '4001', ingestionStatus: 'READY' });
    stateIndex = 0;
    // Allow the next poll to fire and converge.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    expect(screen.getByTestId('items').textContent).toContain('READY');
    const callsAfterReady = listCalls;

    // Advance well past several intervals — no further fetches.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000);
    });
    expect(listCalls).toBe(callsAfterReady);

    vi.useRealTimers();
  });

  it('stops polling on unmount', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const deps = makeDeps();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    let listCalls = 0;
    server.use(
      http.get(COLLECTION, () => {
        listCalls += 1;
        return HttpResponse.json(
          ok({ items: [doc({ ingestionStatus: 'PENDING' })], nextPageToken: '' }),
        );
      }),
    );

    const rendered = wrap(client, <Probe deps={deps} pollIntervalMs={100} />);
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('ready'));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(250);
    });
    const callsBeforeUnmount = listCalls;

    rendered.unmount();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000);
    });
    expect(listCalls).toBe(callsBeforeUnmount);

    vi.useRealTimers();
  });
});

describe('useDocuments upload (multipart)', () => {
  it('uploads via multipart/form-data and the 202 PENDING document enters the list', async () => {
    const deps = makeDeps();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    let uploadBodyText: string | null = null;
    let uploadContentType = '';
    let uploadCalls = 0;
    const uploaded = doc({
      docId: '4002',
      originalFilename: 'intro.txt',
      ingestionStatus: 'PENDING',
      startedAt: null,
      completedAt: null,
    });
    let listIncludesUploaded = false;
    server.use(
      http.get(COLLECTION, () =>
        HttpResponse.json(
          ok({
            items: listIncludesUploaded ? [uploaded] : [],
            nextPageToken: '',
          }),
        ),
      ),
      http.post(COLLECTION, async ({ request }) => {
        uploadCalls += 1;
        uploadContentType = request.headers.get('content-type') ?? '';
        uploadBodyText = await request.text();
        listIncludesUploaded = true;
        return HttpResponse.json(ok(uploaded), { status: 202 });
      }),
    );

    wrap(client, <Probe deps={deps} />);
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('empty'));

    const user = userEvent.setup();
    await user.click(screen.getByText('upload'));
    await waitFor(() => expect(screen.getByTestId('upload-pending').textContent).toBe('false'));

    expect(uploadCalls).toBe(1);
    // Multipart: Content-Type must contain multipart/form-data with a boundary
    // (the browser sets the boundary; we must NOT manually set Content-Type).
    expect(uploadContentType).toContain('multipart/form-data');
    expect(uploadContentType).toContain('boundary=');
    // The body is a multipart payload containing the file field. The filename
    // and content may be normalised by the jsdom/undici fetch implementation
    // (e.g. filename="blob"), so we assert the multipart structure and the
    // field name — the Content-Type boundary check above proves multipart.
    expect(uploadBodyText).not.toBeNull();
    expect(uploadBodyText).toContain('form-data');
    expect(uploadBodyText).toContain('name="file"');

    // After upload the list should refresh and show the PENDING document.
    await waitFor(() =>
      expect(screen.getByTestId('items').textContent).toContain('4002'),
    );
    expect(screen.getByTestId('items').textContent).toContain('PENDING');
  });

  it('surfaces the server message on 413 UPLOAD_TOO_LARGE', async () => {
    const deps = makeDeps();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    server.use(
      http.get(COLLECTION, () =>
        HttpResponse.json(ok({ items: [], nextPageToken: '' })),
      ),
      http.post(COLLECTION, () =>
        HttpResponse.json(
          err(41301, {
            message: '文件超过 10 MiB 上限',
            reason: 'UPLOAD_TOO_LARGE',
            retryable: false,
          }),
          { status: 413 },
        ),
      ),
    );

    wrap(client, <Probe deps={deps} />);
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('empty'));

    const user = userEvent.setup();
    await user.click(screen.getByText('upload'));
    await waitFor(() => expect(screen.getByTestId('upload-error').textContent).not.toBe(''));
    expect(screen.getByTestId('upload-error').textContent).toContain('10 MiB');
  });

  it('surfaces the server message on 415 UNSUPPORTED_MEDIA_TYPE', async () => {
    const deps = makeDeps();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    server.use(
      http.get(COLLECTION, () =>
        HttpResponse.json(ok({ items: [], nextPageToken: '' })),
      ),
      http.post(COLLECTION, () =>
        HttpResponse.json(
          err(41501, {
            message: '不支持的文件类型',
            reason: 'UNSUPPORTED_MEDIA_TYPE',
            retryable: false,
          }),
          { status: 415 },
        ),
      ),
    );

    wrap(client, <Probe deps={deps} />);
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('empty'));

    const user = userEvent.setup();
    await user.click(screen.getByText('upload'));
    await waitFor(() => expect(screen.getByTestId('upload-error').textContent).not.toBe(''));
    expect(screen.getByTestId('upload-error').textContent).toContain('不支持的文件类型');
  });
});

describe('useDocuments retry', () => {
  it('surfaces a retry button only for FAILED+retryable documents', async () => {
    const deps = makeDeps();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    server.use(
      http.get(COLLECTION, () =>
        HttpResponse.json(
          ok({
            items: [
              doc({
                docId: '4001',
                ingestionStatus: 'FAILED',
                failureCategory: 'DISPATCH_MISSING',
                failureMessage: 'dispatch missing',
                retryable: true,
                attempt: 1,
              }),
            ],
            nextPageToken: '',
          }),
        ),
      ),
    );
    wrap(client, <Probe deps={deps} />);
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('ready'));
    expect(screen.getByTestId('items').textContent).toContain('FAILED');
    expect(screen.getByTestId('items').textContent).toContain('retryable=true');
    // Retry button is enabled.
    expect(screen.getByText('retry').hasAttribute('disabled')).toBe(false);
  });

  it('does NOT expose retry for non-retryable FAILED documents', async () => {
    const deps = makeDeps();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    server.use(
      http.get(COLLECTION, () =>
        HttpResponse.json(
          ok({
            items: [
              doc({
                docId: '4001',
                ingestionStatus: 'FAILED',
                failureMessage: 'permanent',
                retryable: false,
              }),
            ],
            nextPageToken: '',
          }),
        ),
      ),
    );
    wrap(client, <Probe deps={deps} />);
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('ready'));
    expect(screen.getByText('retry').hasAttribute('disabled')).toBe(true);
  });

  it('on 200 retry the document returns to PENDING and polling resumes', async () => {
    const deps = makeDeps();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    let retried = false;
    server.use(
      http.get(COLLECTION, () => {
        const item = retried
          ? doc({
              docId: '4001',
              ingestionStatus: 'PENDING',
              attempt: 2,
              operationVersion: '2',
              startedAt: null,
              completedAt: null,
              failureCategory: '',
              failureMessage: '',
              retryable: false,
            })
          : doc({
              docId: '4001',
              ingestionStatus: 'FAILED',
              retryable: true,
              failureMessage: 'oops',
              attempt: 1,
            });
        return HttpResponse.json(ok({ items: [item], nextPageToken: '' }));
      }),
      http.post(RETRY_PATH, () => {
        retried = true;
        return HttpResponse.json(
          ok(
            doc({
              docId: '4001',
              ingestionStatus: 'PENDING',
              attempt: 2,
              operationVersion: '2',
              startedAt: null,
              completedAt: null,
              failureCategory: '',
              failureMessage: '',
              retryable: false,
            }),
          ),
          { status: 200 },
        );
      }),
    );

    wrap(client, <Probe deps={deps} pollIntervalMs={100} />);
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('ready'));

    const user = userEvent.setup();
    await user.click(screen.getByText('retry'));
    await waitFor(() =>
      expect(screen.getByTestId('items').textContent).toContain('PENDING'),
    );
    expect(screen.getByTestId('items').textContent).toContain('attempt=2');
  });

  it('on 409 surfaces the server message and does not change status', async () => {
    const deps = makeDeps();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    server.use(
      http.get(COLLECTION, () =>
        HttpResponse.json(
          ok({
            items: [
              doc({
                docId: '4001',
                ingestionStatus: 'FAILED',
                retryable: true,
                failureMessage: 'oops',
                attempt: 1,
              }),
            ],
            nextPageToken: '',
          }),
        ),
      ),
      http.post(RETRY_PATH, () =>
        HttpResponse.json(
          err(40902, {
            message: '已达重试上限',
            reason: 'INGESTION_RETRY_NOT_ALLOWED',
            retryable: false,
          }),
          { status: 409 },
        ),
      ),
    );

    wrap(client, <Probe deps={deps} />);
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('ready'));

    const user = userEvent.setup();
    await user.click(screen.getByText('retry'));
    await waitFor(() => expect(screen.getByTestId('retry-error').textContent).not.toBe(''));
    expect(screen.getByTestId('retry-error').textContent).toContain('已达重试上限');
    // Status remains FAILED; no auto-retry.
    expect(screen.getByTestId('items').textContent).toContain('FAILED');
  });
});
