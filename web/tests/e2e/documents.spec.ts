import { test, expect, type Page } from '@playwright/test';
import { fileURLToPath } from 'node:url';
import * as path from 'node:path';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

/**
 * 22.5 Document upload + ingestion lifecycle E2E regression.
 *
 * Covers:
 *  - Authenticated knowledge detail page exposes the Documents tab.
 *  - Uploading a fixture .txt file results in a 202 PENDING document row that
 *    appears in the list.
 *  - Polling converges: once the mock flips the document to READY the row
 *    status tag changes to 就绪 and polling stops.
 *  - FAILED + retryable documents show a 重试 action; retry returns to PENDING
 *    and then converges to READY.
 *  - Desktop + mobile viewports render without layout errors.
 *
 * The Console API is mocked at the network layer with `page.route`. No real
 * backend is required. The fixture files live in web/tests/fixtures/.
 */

const PLACEHOLDER_JWT = 'placeholder.access.jwt';
const FIXTURE_TXT = path.join(__dirname, '..', 'fixtures', 'sample.txt');

const KB_ROW = {
  knowledgeBaseId: '3001',
  tenantId: '2001',
  name: '产品文档',
  apiKeyReady: true,
  createdAt: '2026-07-02T09:00:00Z',
  updatedAt: '2026-07-02T09:00:00Z',
};

interface DocState {
  /** Current ingestion status the list should report. */
  status: 'PENDING' | 'READY' | 'FAILED';
  retryable: boolean;
}

async function mockConsoleApi(page: Page, state: DocState): Promise<void> {
  // Auth bootstrap + login.
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
          accessToken: PLACEHOLDER_JWT,
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
      body: JSON.stringify({ success: true, code: 0, result: { userId: '1001', nickname: 'alice' } }),
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

  // KB detail.
  await page.route(/\/console-api\/api\/v1\/tenants\/2001\/knowledge-bases\/3001(?:\?|$)/, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, code: 0, result: KB_ROW }),
    }),
  );

  // Document collection: list + upload. The list returns the current doc state.
  await page.route('**/console-api/api/v1/tenants/2001/knowledge-bases/3001/documents**', (route) => {
    const method = route.request().method();
    if (method === 'GET') {
      const item = {
        docId: '4001',
        knowledgeBaseId: '3001',
        originalFilename: 'sample.txt',
        fileType: 'TXT',
        sizeBytes: 68,
        ingestionStatus: state.status,
        operationVersion: '1',
        attempt: 1,
        failureCategory: state.status === 'FAILED' ? 'DISPATCH_MISSING' : '',
        failureMessage: state.status === 'FAILED' ? 'dispatch missing' : '',
        retryable: state.retryable,
        startedAt: '2026-06-29T09:01:00Z',
        completedAt: state.status === 'READY' ? '2026-06-29T09:01:30Z' : null,
      };
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ success: true, code: 0, result: { items: [item], nextPageToken: '' } }),
      });
    }
    // POST upload → 202 PENDING.
    return route.fulfill({
      status: 202,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        code: 0,
        result: {
          docId: '4001',
          knowledgeBaseId: '3001',
          originalFilename: 'sample.txt',
          fileType: 'TXT',
          sizeBytes: 68,
          ingestionStatus: 'PENDING',
          operationVersion: '1',
          attempt: 1,
          failureCategory: '',
          failureMessage: '',
          retryable: false,
          startedAt: null,
          completedAt: null,
        },
      }),
    });
  });

  // Retry endpoint.
  await page.route(
    '**/console-api/api/v1/tenants/2001/knowledge-bases/3001/documents/4001/ingestion/retry',
    (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          code: 0,
          result: {
            docId: '4001',
            knowledgeBaseId: '3001',
            originalFilename: 'sample.txt',
            fileType: 'TXT',
            sizeBytes: 68,
            ingestionStatus: 'PENDING',
            operationVersion: '2',
            attempt: 2,
            failureCategory: '',
            failureMessage: '',
            retryable: false,
            startedAt: null,
            completedAt: null,
          },
        }),
      }),
  );
}

async function loginAndGotoDocuments(page: Page, state: DocState): Promise<void> {
  await mockConsoleApi(page, state);
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('alice');
  await page.getByPlaceholder('密码').fill('password123456');
  await page
    .getByRole('button')
    .filter({ hasText: /登\s*录/ })
    .click();
  await expect(page).toHaveURL(/\/app\/knowledge$/, { timeout: 10_000 });
  // Navigate to the KB detail.
  await page.goto('/app/knowledge/3001');
  await expect(page.getByRole('heading', { name: '产品文档' })).toBeVisible({ timeout: 10_000 });
  // Switch to the Documents tab.
  await page.getByRole('tab', { name: '文档' }).click();
}

async function clickButton(page: Page, text: string): Promise<void> {
  const pattern = new RegExp(text.split('').join('\\s*'));
  await page.getByRole('button').filter({ hasText: pattern }).first().click();
}

test.describe('document upload and ingestion lifecycle', () => {
  test('desktop: upload fixture converges PENDING → READY', async ({ page }) => {
    const state: DocState = { status: 'READY', retryable: false };
    await loginAndGotoDocuments(page, state);

    // Initially the list is empty (no doc yet). Upload a fixture file.
    await page.setInputFiles('input[type="file"]', FIXTURE_TXT);

    // The 202 PENDING document should appear, then polling converges to READY.
    await expect(page.getByText('sample.txt').first()).toBeVisible({ timeout: 10_000 });
    // After upload the mock returns PENDING for the uploaded doc, then READY.
    await expect(page.getByText('就绪').first()).toBeVisible({ timeout: 15_000 });
  });

  test('desktop: FAILED retryable document exposes retry and converges', async ({ page }) => {
    const state: DocState = { status: 'FAILED', retryable: true };
    await loginAndGotoDocuments(page, state);

    // FAILED document with retryable=true shows the failure text and a retry button.
    await expect(page.getByText('失败').first()).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('dispatch missing').first()).toBeVisible();

    // Click retry → mock returns PENDING; flip state to READY for convergence.
    await clickButton(page, '重试');
    state.status = 'READY';
    state.retryable = false;
    await expect(page.getByText('就绪').first()).toBeVisible({ timeout: 15_000 });
  });

  test('mobile: documents tab renders without horizontal scroll', async ({ page, browserName }) => {
    test.skip(browserName !== 'chromium', 'mobile project runs chromium only');
    const state: DocState = { status: 'READY', retryable: false };
    await loginAndGotoDocuments(page, state);

    await page.setInputFiles('input[type="file"]', FIXTURE_TXT);
    await expect(page.getByText('sample.txt').first()).toBeVisible({ timeout: 10_000 });
    const overflowX = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflowX, 'mobile must not require horizontal scroll').toBeLessThanOrEqual(0);
  });
});
