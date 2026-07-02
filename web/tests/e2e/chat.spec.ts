/**
 * 22.7 Chat E2E regression.
 *
 * Covers the independent knowledge-retrieval chat on both desktop and mobile:
 *  - Console login is required to reach /app/chat (ProtectedRoute), but the
 *    chat feature itself uses the Open API + a user-entered API Key that is
 *    independent of the Console session.
 *  - Initial state: the API Key input and the transcript are EMPTY. The empty
 *    state is shown.
 *  - Entering a key + question + Send: the answer and sources render.
 *  - Failed query (502): the failed assistant message is RETAINED with an
 *    explicit Retry button; retry succeeds.
 *  - Invalid key (401): surfaces an authentication error.
 *  - STORAGE SCAN: after a successful query we assert that NO string matching
 *    the complete-key pattern leaks into localStorage or sessionStorage. The
 *    key is page-memory only (plan_22 §22.7 hard rule).
 *  - REFRESH CLEARS STATE: after reload the key input and transcript are both
 *    empty (React state resets on navigation/reload).
 *
 * The Console API is mocked at the network layer with `page.route`. No real
 * backend is required. Placeholders are used for any secret material.
 *
 * DETERMINISM (22.6 lesson): Ant Design Modal/Drawer/overlay interactions are
 * flaky on mobile when CSS transitions/animations intercept clicks. We disable
 * all transitions/animations app-wide in beforeEach via addStyleTag so every
 * interaction is instant.
 */

import { test, expect, type Page } from '@playwright/test';

const PLACEHOLDER_JWT = 'placeholder.access.jwt';
const PLACEHOLDER_JWT_2 = 'placeholder.access.jwt.refreshed';

// Deterministic complete-key placeholder. NEVER a realistic-looking secret.
const COMPLETE_KEY = 'crag_abcd_<PLACEHOLDER_SECRET>';

// Regex that matches any complete-key-shaped string. Used for the storage scan.
const FULL_KEY_PATTERN = /crag_[a-zA-Z0-9]{4}_[A-Za-z0-9_<]/;

/** Console auth + tenant mocks so the ProtectedRoute lets us reach /app/chat. */
async function mockConsoleAuth(page: Page): Promise<void> {
  await page.route('**/console-api/api/v1/auth/login', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        code: 0,
        result: {
          accessToken: PLACEHOLDER_JWT,
          accessExpiresAt: '2026-07-02T12:00:00Z',
          user: { userId: '1001', nickname: 'alice' },
          defaultTenant: null,
        },
      }),
    }),
  );
  await page.route('**/console-api/api/v1/auth/refresh', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        code: 0,
        result: {
          accessToken: PLACEHOLDER_JWT_2,
          accessExpiresAt: '2026-07-02T12:30:00Z',
          user: { userId: '1001', nickname: 'alice' },
          defaultTenant: null,
        },
      }),
    }),
  );
  await page.route('**/console-api/api/v1/auth/me', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        code: 0,
        result: { userId: '1001', nickname: 'alice' },
      }),
    }),
  );
  await page.route('**/console-api/api/v1/tenants', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        code: 0,
        result: {
          items: [{ tenantId: '2001', name: 'alice 的默认租户', role: 'OWNER' }],
          nextPageToken: '',
        },
      }),
    }),
  );
  await page.route('**/console-api/api/v1/auth/logout', (route) =>
    route.fulfill({ status: 204, body: '' }),
  );
}

interface OpenMockState {
  /** HTTP status to return for the next query. */
  status: 200 | 401 | 502;
  /** Answer body for status 200. */
  answer: string;
  /** Sources body for status 200. */
  sources: ReadonlyArray<{ reference: string; documentId: string; excerpt: string }>;
}

/** Mock the Open Query endpoint. Tracks the captured request body + auth header. */
async function mockOpenApi(page: Page, state: OpenMockState): Promise<void> {
  await page.route('**/open-api/api/v1/query', async (route) => {
    if (state.status === 200) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          code: 0,
          result: { answer: state.answer, sources: state.sources },
        }),
      });
    }
    if (state.status === 401) {
      return route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({
          success: false,
          code: 40102,
          result: {
            message: 'Authentication failed',
            traceId: 'e2e-trace',
            reason: 'INVALID_API_KEY',
            retryable: false,
            fieldErrors: [],
          },
        }),
      });
    }
    // 502 LLM_UNAVAILABLE
    return route.fulfill({
      status: 502,
      contentType: 'application/json',
      body: JSON.stringify({
        success: false,
        code: 50201,
        result: {
          message: 'LLM unavailable',
          traceId: 'e2e-trace',
          reason: 'LLM_UNAVAILABLE',
          retryable: true,
          fieldErrors: [],
        },
      }),
    });
  });
}

/** Login then navigate to /app/chat. */
async function loginAndGotoChat(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('alice');
  await page.getByPlaceholder('密码').fill('password123456');
  await page
    .getByRole('button')
    .filter({ hasText: /登\s*录/ })
    .click();
  await expect(page).toHaveURL(/\/app\/knowledge$/, { timeout: 10_000 });
  await page.goto('/app/chat');
  await expect(page).toHaveURL(/\/app\/chat$/);
}

/** Assert NO complete-key-shaped string leaks into web storage. */
async function assertNoCompleteKeyInStorage(page: Page): Promise<void> {
  const leaked = await page.evaluate((patternSrc) => {
    const pattern = new RegExp(patternSrc);
    const found: string[] = [];
    for (let i = 0; i < localStorage.length; i += 1) {
      const k = localStorage.key(i)!;
      const v = localStorage.getItem(k) ?? '';
      if (pattern.test(k) || pattern.test(v)) found.push(`ls:${k}`);
    }
    for (let i = 0; i < sessionStorage.length; i += 1) {
      const k = sessionStorage.key(i)!;
      const v = sessionStorage.getItem(k) ?? '';
      if (pattern.test(k) || pattern.test(v)) found.push(`ss:${k}`);
    }
    return found;
  }, FULL_KEY_PATTERN.source);
  expect(leaked, `complete key leaked into storage: ${leaked.join(', ')}`).toEqual([]);
}

test.describe('knowledge chat', () => {
  // Determinism (22.6 lesson): disable all CSS transitions/animations so Ant
  // Design overlay/inline interactions are instant on both viewports.
  test.beforeEach(async ({ page }) => {
    await page.addStyleTag({
      content: `* { transition: none !important; animation: none !important; }`,
    });
  });

  test('initial state: empty key input and empty transcript; refresh clears both', async ({
    page,
  }) => {
    await mockConsoleAuth(page);
    await mockOpenApi(page, { status: 200, answer: 'ok', sources: [] });
    await loginAndGotoChat(page);

    // Empty state visible.
    await expect(page.getByPlaceholder(/crag_/)).toBeVisible();
    await expect(page.getByText(/请输入 API Key/)).toBeVisible({ timeout: 5_000 });

    // Enter a key + ask a question + send.
    await page.getByPlaceholder(/crag_/).fill(COMPLETE_KEY);
    await page.getByPlaceholder('输入问题…').fill('什么是 RAG？');
    await page.getByRole('button', { name: /发\s*送/ }).click();
    await expect(page.getByText('ok')).toBeVisible({ timeout: 10_000 });

    // STORAGE SCAN: no complete key leaked.
    await assertNoCompleteKeyInStorage(page);

    // REFRESH clears the key AND the transcript (page-memory only).
    await page.reload();
    await expect(page).toHaveURL(/\/app\/chat$/);
    await expect(page.getByPlaceholder(/crag_/)).toHaveValue('');
    await expect(page.getByText(/请输入 API Key/)).toBeVisible({ timeout: 5_000 });
    await expect(page.getByText('ok')).toHaveCount(0);
  });

  test('successful query shows answer + sources; body is question-only', async ({ page }) => {
    await mockConsoleAuth(page);
    // Capture the request body + auth header for the shape assertion.
    const captured: { body: unknown; auth: string | null }[] = [];
    await page.route('**/open-api/api/v1/query', async (route) => {
      const req = route.request();
      captured.push({
        body: req.postDataJSON(),
        auth: req.headers()['authorization'] ?? null,
      });
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          code: 0,
          result: {
            answer: 'RAG 是检索增强生成 [S1]。',
            sources: [
              {
                reference: 'S1',
                documentId: '4001',
                excerpt: 'RAG 是一种结合检索与生成的架构。',
              },
            ],
          },
        }),
      });
    });
    await loginAndGotoChat(page);

    await page.getByPlaceholder(/crag_/).fill(COMPLETE_KEY);
    await page.getByPlaceholder('输入问题…').fill('  什么是 RAG？  ');
    await page.getByRole('button', { name: /发\s*送/ }).click();

    // Answer renders.
    await expect(page.getByText(/RAG 是检索增强生成/)).toBeVisible({ timeout: 10_000 });
    // Source reference + document id render.
    await expect(page.getByText('S1').first()).toBeVisible();
    await expect(page.getByText('4001').first()).toBeVisible();
    await expect(page.getByText('RAG 是一种结合检索与生成的架构。')).toBeVisible();

    // Request body is ONLY { question } (trimmed); auth is the in-memory key.
    expect(captured).toHaveLength(1);
    expect(captured[0]!.body).toEqual({ question: '什么是 RAG？' });
    expect(captured[0]!.auth).toBe(`Bearer ${COMPLETE_KEY}`);

    await assertNoCompleteKeyInStorage(page);
  });

  test('no double submit: Send is disabled while a request is in flight', async ({ page }) => {
    await mockConsoleAuth(page);
    let resolveGate!: () => void;
    const gate = new Promise<void>((r) => {
      resolveGate = r;
    });
    let calls = 0;
    await page.route('**/open-api/api/v1/query', async (route) => {
      calls += 1;
      await gate;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          code: 0,
          result: { answer: 'ok', sources: [] },
        }),
      });
    });
    await loginAndGotoChat(page);

    await page.getByPlaceholder(/crag_/).fill(COMPLETE_KEY);
    const question = page.getByPlaceholder('输入问题…');
    const send = page.getByRole('button', { name: /发\s*送/ });

    await question.fill('q1');
    await send.click();
    // While in flight, the Send button shows loading and is disabled.
    await expect(send).toBeDisabled({ timeout: 5_000 });

    // Release the gate.
    resolveGate();
    await expect(page.getByText('ok')).toBeVisible({ timeout: 10_000 });
    expect(calls).toBe(1);
  });

  test('failed query is retained with Retry; retry succeeds', async ({ page }) => {
    await mockConsoleAuth(page);
    let calls = 0;
    await page.route('**/open-api/api/v1/query', async (route) => {
      calls += 1;
      if (calls === 1) {
        await route.fulfill({
          status: 502,
          contentType: 'application/json',
          body: JSON.stringify({
            success: false,
            code: 50201,
            result: {
              message: 'LLM unavailable',
              traceId: 'e2e-trace',
              reason: 'LLM_UNAVAILABLE',
              retryable: true,
              fieldErrors: [],
            },
          }),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          code: 0,
          result: { answer: 'retry ok', sources: [] },
        }),
      });
    });
    await loginAndGotoChat(page);

    await page.getByPlaceholder(/crag_/).fill(COMPLETE_KEY);
    await page.getByPlaceholder('输入问题…').fill('retry me');
    await page.getByRole('button', { name: /发\s*送/ }).click();

    // Failed assistant message + retry button visible.
    await expect(page.getByText(/回答失败/)).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole('button', { name: /重\s*试/ })).toBeVisible();

    // Click retry; the answer appears.
    await page.getByRole('button', { name: /重\s*试/ }).click();
    await expect(page.getByText('retry ok')).toBeVisible({ timeout: 10_000 });
    expect(calls).toBe(2);
  });

  test('invalid key surfaces authentication error', async ({ page }) => {
    await mockConsoleAuth(page);
    await mockOpenApi(page, { status: 401, answer: '', sources: [] });
    await loginAndGotoChat(page);

    await page.getByPlaceholder(/crag_/).fill(COMPLETE_KEY);
    await page.getByPlaceholder('输入问题…').fill('q');
    await page.getByRole('button', { name: /发\s*送/ }).click();

    await expect(page.getByText(/API Key 无效/)).toBeVisible({ timeout: 10_000 });
    // The failed assistant message is retained.
    await expect(page.getByText(/回答失败/)).toBeVisible();
  });

  test('no-results: empty answer + no sources render gracefully', async ({ page }) => {
    await mockConsoleAuth(page);
    await mockOpenApi(page, {
      status: 200,
      answer: '没有找到相关来源。',
      sources: [],
    });
    await loginAndGotoChat(page);

    await page.getByPlaceholder(/crag_/).fill(COMPLETE_KEY);
    await page.getByPlaceholder('输入问题…').fill('unknown topic');
    await page.getByRole('button', { name: /发\s*送/ }).click();

    await expect(page.getByText('没有找到相关来源。')).toBeVisible({ timeout: 10_000 });
    // No sources section rendered (the .chat-sources block is absent).
    await expect(page.locator('.chat-sources')).toHaveCount(0);
  });

  test('clear button purges the key and the transcript', async ({ page }) => {
    await mockConsoleAuth(page);
    await mockOpenApi(page, {
      status: 200,
      answer: 'answer here',
      sources: [],
    });
    await loginAndGotoChat(page);

    await page.getByPlaceholder(/crag_/).fill(COMPLETE_KEY);
    await page.getByPlaceholder('输入问题…').fill('q');
    await page.getByRole('button', { name: /发\s*送/ }).click();
    await expect(page.getByText('answer here')).toBeVisible({ timeout: 10_000 });

    // Click 清除.
    await page.getByRole('button', { name: /清\s*除/ }).click();
    await expect(page.getByPlaceholder(/crag_/)).toHaveValue('');
    await expect(page.getByText('answer here')).toHaveCount(0);
    await expect(page.getByText(/请输入 API Key/)).toBeVisible({ timeout: 5_000 });
  });
});
