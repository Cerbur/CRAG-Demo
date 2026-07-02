import { test, expect, type Page } from '@playwright/test';

/**
 * 22.4 Knowledge E2E regression.
 *
 * Covers:
 *  - Authenticated list renders rows from the (mocked) tenant KB collection.
 *  - Pagination Previous/Next advances via pageToken.
 *  - Create modal opens and submitting a name navigates to the new KB's detail
 *    route EVEN when the server returns 201 with apiKeyReady=false (partial
 *    success → detail).
 *  - Detail Overview shows name/id/status, including the partial-success
 *    warning when apiKeyReady=false.
 *  - Polling converges to apiKeyReady=true after the mock flips the flag, and
 *    the warning disappears.
 *  - Desktop and mobile viewports render without layout errors.
 *
 * The Console API is mocked at the network layer with `page.route`. No real
 * backend is required. Placeholders are used for any secret material.
 */

const PLACEHOLDER_JWT = 'placeholder.access.jwt';
const PLACEHOLDER_JWT_2 = 'placeholder.access.jwt.refreshed';

const KB_ROW_1 = {
  knowledgeBaseId: '3001',
  tenantId: '2001',
  name: '产品文档',
  apiKeyReady: true,
  createdAt: '2026-07-02T09:00:00Z',
  updatedAt: '2026-07-02T09:00:00Z',
};

const KB_ROW_2 = {
  knowledgeBaseId: '3002',
  tenantId: '2001',
  name: '研发笔记',
  apiKeyReady: true,
  createdAt: '2026-07-02T10:00:00Z',
  updatedAt: '2026-07-02T10:00:00Z',
};

const KB_PARTIAL = {
  knowledgeBaseId: '3003',
  tenantId: '2001',
  name: '部分成功库',
  apiKeyReady: false,
  createdAt: '2026-07-02T11:00:00Z',
  updatedAt: '2026-07-02T11:00:00Z',
};

interface KbMockState {
  /** When true, GET /{id} returns apiKeyReady=true; otherwise false. */
  partialReady: boolean;
}

/**
 * Install Console API mocks. Default: login succeeds, /me and /tenants return
 * the alice OWNER session, KB list returns two ready KBs on page 1 and an empty
 * page 2 (driven by pageToken). Create returns the partial-success KB. Detail
 * GET flips to ready once `state.partialReady` is set.
 */
async function mockConsoleApi(
  page: Page,
  state: KbMockState = { partialReady: false },
): Promise<void> {
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

  // KB collection: list (pageToken-aware) + create. The trailing ** matches
  // the query string (pageToken/pageSize) appended by the list ViewModel.
  // Registered FIRST so the more-specific item route registered next wins for
  // /knowledge-bases/{id} requests (Playwright runs the most-recently-added
  // matching route first).
  await page.route('**/console-api/api/v1/tenants/2001/knowledge-bases**', (route) => {
    const method = route.request().method();
    if (method === 'GET') {
      const url = new URL(route.request().url());
      const token = url.searchParams.get('pageToken') ?? '';
      const items = token === '' ? [KB_ROW_1, KB_ROW_2] : [];
      const nextPageToken = token === '' ? 'page2' : '';
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ success: true, code: 0, result: { items, nextPageToken } }),
      });
    }
    // POST create → 201 partial success.
    return route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, code: 0, result: KB_PARTIAL }),
    });
  });

  // KB item detail: 3001 and 3002 always ready; 3003 flips per state.
  // Registered AFTER the collection route so it takes precedence for
  // /knowledge-bases/{id} requests.
  await page.route(/\/console-api\/api\/v1\/tenants\/2001\/knowledge-bases\/\d+/, (route) => {
    const url = route.request().url();
    const match = /knowledge-bases\/(\d+)(?:\?|$)/.exec(url);
    const id = match?.[1] ?? '';
    const base =
      id === '3001'
        ? KB_ROW_1
        : id === '3002'
          ? KB_ROW_2
          : id === '3003'
            ? { ...KB_PARTIAL, apiKeyReady: state.partialReady }
            : KB_ROW_1;
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, code: 0, result: base }),
    });
  });
}

/** Click a button whose visible label may have Ant Design's CJK space inserted. */
async function clickButton(page: Page, text: string): Promise<void> {
  // Insert optional whitespace between each char so "新建" matches "新 建".
  const pattern = new RegExp(text.split('').join('\\s*'));
  const button = page.getByRole('button').filter({ hasText: pattern });
  await button.first().click();
}

async function loginAndGotoKnowledge(page: Page, state?: KbMockState): Promise<void> {
  await mockConsoleApi(page, state);
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('alice');
  await page.getByPlaceholder('密码').fill('password123456');
  await page
    .getByRole('button')
    .filter({ hasText: /登\s*录/ })
    .click();
  await expect(page).toHaveURL(/\/app\/knowledge$/, { timeout: 10_000 });
}

test.describe('knowledge management', () => {
  test('desktop: list renders rows and pagination advances', async ({ page }) => {
    await loginAndGotoKnowledge(page);

    // Two rows visible on page 1.
    await expect(page.getByText('产品文档')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('研发笔记')).toBeVisible();

    // Next button enabled, Previous disabled on page 1.
    const next = page.getByRole('button').filter({ hasText: /下\s*一\s*页/ });
    await expect(next).toBeEnabled();
    const prev = page.getByRole('button').filter({ hasText: /上\s*一\s*页/ });
    await expect(prev).toBeDisabled();

    // Click Next → empty page 2.
    await next.click();
    await page.waitForURL('**/app/knowledge');
    // Page 2 is empty; the empty state CTA shows.
    await expect(page.getByText('还没有知识库')).toBeVisible({ timeout: 5_000 });
    await expect(prev).toBeEnabled();
    await expect(next).toBeDisabled();

    // Back to page 1.
    await prev.click();
    await expect(page.getByText('产品文档')).toBeVisible({ timeout: 5_000 });
  });

  test('desktop: create modal partial-success navigates to detail', async ({ page }) => {
    const state: KbMockState = { partialReady: false };
    await loginAndGotoKnowledge(page, state);

    await clickButton(page, '新建');
    await expect(page.getByPlaceholder('知识库名称')).toBeVisible({ timeout: 5_000 });
    await page.getByPlaceholder('知识库名称').fill('部分成功库');
    await clickButton(page, '创建');

    // Navigated to the new KB detail even though apiKeyReady=false.
    await expect(page).toHaveURL(/\/app\/knowledge\/3003$/, { timeout: 10_000 });
    await expect(page.getByText('API Key 尚未就绪')).toBeVisible({ timeout: 10_000 });
    // The name appears both in the page heading and the Overview Descriptions
    // item; assert on the heading to avoid strict-mode violations.
    await expect(page.getByRole('heading', { name: '部分成功库' })).toBeVisible();
    await expect(page.getByText('API Key 待就绪')).toBeVisible();

    // Flip the mock so the next poll reports ready.
    state.partialReady = true;
    // Polling should converge and the warning should disappear.
    await expect(page.getByText('API Key 就绪')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('API Key 尚未就绪')).toBeHidden({ timeout: 5_000 });
  });

  test('desktop: detail back button returns to list', async ({ page }) => {
    await loginAndGotoKnowledge(page);

    // Click the first row name to open detail.
    await page.getByText('产品文档').first().click();
    await expect(page).toHaveURL(/\/app\/knowledge\/3001$/, { timeout: 10_000 });
    await expect(page.getByText('API Key 就绪')).toBeVisible({ timeout: 10_000 });

    await clickButton(page, '返回');
    await expect(page).toHaveURL(/\/app\/knowledge$/, { timeout: 5_000 });
  });

  test('mobile: list renders cards without horizontal scroll', async ({ page, browserName }) => {
    test.skip(browserName !== 'chromium', 'mobile project runs chromium only');
    await loginAndGotoKnowledge(page);

    await expect(page.getByText('产品文档')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('研发笔记')).toBeVisible();
    // Assert the page does not overflow horizontally.
    const overflowX = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflowX, 'mobile must not require horizontal scroll').toBeLessThanOrEqual(0);
  });

  test('mobile: create modal and partial-success detail work', async ({ page, browserName }) => {
    test.skip(browserName !== 'chromium', 'mobile project runs chromium only');
    const state: KbMockState = { partialReady: false };
    await loginAndGotoKnowledge(page, state);

    await clickButton(page, '新建');
    await page.getByPlaceholder('知识库名称').fill('部分成功库');
    await clickButton(page, '创建');

    await expect(page).toHaveURL(/\/app\/knowledge\/3003$/, { timeout: 10_000 });
    await expect(page.getByText('API Key 尚未就绪')).toBeVisible({ timeout: 10_000 });

    state.partialReady = true;
    await expect(page.getByText('API Key 就绪')).toBeVisible({ timeout: 10_000 });
  });
});
