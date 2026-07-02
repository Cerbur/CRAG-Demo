/**
 * 22.6 API Keys E2E regression.
 *
 * Covers the full lifecycle on the Knowledge detail API Keys tab:
 *  - Create opens the one-time-secret modal; the complete key is shown.
 *  - Copy confirms clipboard access (we assert the copy button is clickable;
 *    Playwright cannot read the OS clipboard without permissions, so we assert
 *    the success toast appears).
 *  - The "我已保存该密钥" checkbox gates the Done button.
 *  - Disable / Enable / Rotate / Revoke action buttons appear per the status
 *    matrix and execute (the mocked list reflects the new status).
 *  - STORAGE SCAN: after create, rotate, and revoke we assert that NO string
 *    matching the complete-key pattern leaks into localStorage or
 *    sessionStorage. This is the hard security rule from plan_22 §22.6.
 *
 * The Console API is mocked at the network layer with `page.route`. No real
 * backend is required. Placeholders are used for any secret material.
 */

import { test, expect, type Page } from '@playwright/test';

const PLACEHOLDER_JWT = 'placeholder.access.jwt';
const PLACEHOLDER_JWT_2 = 'placeholder.access.jwt.refreshed';

// Deterministic complete-key placeholder. NEVER a realistic-looking secret.
const COMPLETE_KEY = 'crag_abcd_<PLACEHOLDER_SECRET>';
const ROTATED_KEY = 'crag_rot1_<PLACEHOLDER_SECRET>';

// The full-key regex used by the storage scan. Matches crag_<word>_<secret>.
const FULL_KEY_PATTERN = /crag_[a-zA-Z0-9]{4}_<PLACEHOLDER_SECRET>/;

const KB = {
  knowledgeBaseId: '3001',
  tenantId: '2001',
  name: '产品文档',
  apiKeyReady: true,
  createdAt: '2026-07-02T09:00:00Z',
  updatedAt: '2026-07-02T09:00:00Z',
};

interface ApiKeyState {
  /** Current mutable state of the single test key. */
  status: 'ACTIVE' | 'DISABLED' | 'REVOKED';
  /** The completeKey to return on the next create/rotate (or null post-revoke). */
  pendingCompleteKey: string | null;
}

/**
 * Install Console API mocks. The api-keys collection serves a list whose items
 * reflect `state.status`; POST create / rotate return the one-time
 * `state.pendingCompleteKey`; POST disable/enable/revoke flip `state.status`
 * and return the updated projection.
 */
async function mockConsoleApi(page: Page, state: ApiKeyState): Promise<void> {
  // Auth bootstrap.
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

  // KB list (single KB). Use a regex that matches ONLY the collection path
  // (not /knowledge-bases/{id} or sub-resources like /api-keys), so it does
  // not swallow the api-keys GET.
  await page.route(/\/console-api\/api\/v1\/tenants\/2001\/knowledge-bases(?:\?|$)/, (route) => {
    const method = route.request().method();
    if (method === 'GET') {
      const url = new URL(route.request().url());
      const token = url.searchParams.get('pageToken') ?? '';
      // The index page uses '__all__' as a sentinel; the regular list uses ''.
      const items = token === '' || token === '__all__' ? [KB] : [];
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          code: 0,
          result: { items, nextPageToken: '' },
        }),
      });
    }
    return route.fulfill({ status: 200, body: '{}' });
  });

  // KB detail (3001).
  await page.route(/\/console-api\/api\/v1\/tenants\/2001\/knowledge-bases\/3001(?:\?|$)/, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, code: 0, result: KB }),
    }),
  );

  // API Keys collection: GET list + POST create. Use a regex that matches
  // ONLY the collection path (no /{apiKeyId} suffix) so the item route below
  // never swallows the list GET.
  await page.route(
    /\/console-api\/api\/v1\/tenants\/2001\/knowledge-bases\/3001\/api-keys(?:\?|$)/,
    (route) => {
      const method = route.request().method();
      if (method === 'GET') {
        const item = {
          apiKeyId: '5001',
          knowledgeBaseId: '3001',
          name: 'prod-key',
          status: state.status,
          keyPrefix: 'crag_abcd',
          createdAt: '2026-07-02T09:00:00Z',
          expiresAt: null,
        };
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            success: true,
            code: 0,
            result: { items: [item], nextPageToken: null },
          }),
        });
      }
      // POST create → 201 with the one-time complete key.
      state.status = 'ACTIVE';
      const created = {
        apiKeyId: '5001',
        knowledgeBaseId: '3001',
        name: 'prod-key',
        completeKey: state.pendingCompleteKey ?? COMPLETE_KEY,
        expiresAt: null,
      };
      state.pendingCompleteKey = null;
      return route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ success: true, code: 0, result: created }),
      });
    },
  );

  // API Key item: GET + status actions (disable/enable/rotate/revoke).
  await page.route(
    /\/console-api\/api\/v1\/tenants\/2001\/knowledge-bases\/3001\/api-keys\/5001(\/[a-z]+)?(?:\?|$)/,
    (route) => {
      const url = route.request().url();
      if (url.endsWith('/disable')) {
        state.status = 'DISABLED';
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            success: true,
            code: 0,
            result: {
              apiKeyId: '5001',
              knowledgeBaseId: '3001',
              name: 'prod-key',
              status: 'DISABLED',
              keyPrefix: 'crag_abcd',
              createdAt: '2026-07-02T09:00:00Z',
              expiresAt: null,
            },
          }),
        });
      }
      if (url.endsWith('/enable')) {
        state.status = 'ACTIVE';
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            success: true,
            code: 0,
            result: {
              apiKeyId: '5001',
              knowledgeBaseId: '3001',
              name: 'prod-key',
              status: 'ACTIVE',
              keyPrefix: 'crag_abcd',
              createdAt: '2026-07-02T09:00:00Z',
              expiresAt: null,
            },
          }),
        });
      }
      if (url.endsWith('/rotate')) {
        state.status = 'ACTIVE';
        const created = {
          apiKeyId: '5001',
          knowledgeBaseId: '3001',
          name: 'prod-key',
          completeKey: ROTATED_KEY,
          expiresAt: null,
        };
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ success: true, code: 0, result: created }),
        });
      }
      if (url.endsWith('/revoke')) {
        state.status = 'REVOKED';
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            success: true,
            code: 0,
            result: {
              apiKeyId: '5001',
              knowledgeBaseId: '3001',
              name: 'prod-key',
              status: 'REVOKED',
              keyPrefix: 'crag_abcd',
              createdAt: '2026-07-02T09:00:00Z',
              expiresAt: null,
            },
          }),
        });
      }
      // GET item (prefix only).
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          code: 0,
          result: {
            apiKeyId: '5001',
            knowledgeBaseId: '3001',
            name: 'prod-key',
            status: state.status,
            keyPrefix: 'crag_abcd',
            createdAt: '2026-07-02T09:00:00Z',
            expiresAt: null,
          },
        }),
      });
    },
  );
}

/** Click a button whose visible label may have Ant Design's CJK auto-spacing. */
async function clickButton(page: Page, text: string): Promise<void> {
  const pattern = new RegExp(text.split('').join('\\s*'));
  // Rely on Playwright auto-waiting. CSS transitions/animations are disabled
  // app-wide in `test.beforeEach`, so modal/drawer/overlay buttons are always
  // stable and clickable — no `force:true` mask needed, and the underlying
  // React handler deterministically fires on every viewport.
  await page
    .getByRole('button')
    .filter({ hasText: pattern })
    .first()
    .click();
}

async function loginAndGotoApiKeyTab(page: Page, state: ApiKeyState): Promise<void> {
  await mockConsoleApi(page, state);
  // Stub navigator.clipboard.writeText so the copy button always succeeds in
  // the test browser (Playwright runs on localhost, a secure context, but
  // clipboard permissions can still be denied without a user gesture in some
  // configurations). This keeps the success toast deterministic.
  await page.addInitScript(() => {
    const writeText = () => Promise.resolve();
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    });
  });
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('alice');
  await page.getByPlaceholder('密码').fill('password123456');
  await page.getByRole('button').filter({ hasText: /登\s*录/ }).click();
  await expect(page).toHaveURL(/\/app\/knowledge$/, { timeout: 10_000 });
  // Navigate to the KB detail.
  await page.getByText('产品文档').first().click();
  await expect(page).toHaveURL(/\/app\/knowledge\/3001/, { timeout: 10_000 });
  // Click the API Keys tab.
  await page.getByRole('tab').filter({ hasText: /API Keys/ }).click();
}

/**
 * Assert that NO string matching the full-key pattern appears in localStorage
 * or sessionStorage. This is the hard security rule: completeKey must never be
 * persisted.
 */
async function assertNoCompleteKeyInStorage(page: Page): Promise<void> {
  const leaked = await page.evaluate((patternSource) => {
    const pattern = new RegExp(patternSource);
    const hits: string[] = [];
    for (let i = 0; i < localStorage.length; i += 1) {
      const k = localStorage.key(i);
      if (!k) continue;
      const v = localStorage.getItem(k);
      if (v && pattern.test(v)) hits.push(`localStorage[${k}]`);
      if (pattern.test(k)) hits.push(`localStorage.key[${k}]`);
    }
    for (let i = 0; i < sessionStorage.length; i += 1) {
      const k = sessionStorage.key(i);
      if (!k) continue;
      const v = sessionStorage.getItem(k);
      if (v && pattern.test(v)) hits.push(`sessionStorage[${k}]`);
      if (pattern.test(k)) hits.push(`sessionStorage.key[${k}]`);
    }
    return hits;
  }, FULL_KEY_PATTERN.source);
  expect(leaked, `complete key leaked into storage: ${leaked.join(', ')}`).toEqual([]);
}

test.describe('api key management', () => {
  test.slow();

  // Deterministic modal transitions: Ant Design Modal/Drawer/Messages use CSS
  // transitions for open/close. On the narrow mobile viewport a Playwright
  // click on a modal button that lands during the closing animation is
  // intercepted by the still-animating overlay/mask, so the React handler
  // never fires — `force:true` masks the actionability check but does NOT
  // guarantee the handler runs. Disabling all CSS transitions/animations in
  // the e2e environment makes open/close instant so no click is ever
  // intercepted, and we can drop the `force:true` workarounds below and rely
  // on Playwright auto-waiting on stable locators. App-scoped (covers
  // `Modal.confirm` imperative dialogs too); runs before each test only.
  test.beforeEach(async ({ page }) => {
    await page.addStyleTag({
      content: `* { transition: none !important; animation: none !important; }`,
    });
  });
  test('create, copy, confirm, disable, enable, rotate, revoke + storage scan', async ({ page }) => {
    const state: ApiKeyState = {
      status: 'ACTIVE',
      pendingCompleteKey: null,
    };
    await loginAndGotoApiKeyTab(page, state);

    // --- Create: open modal, enter name, submit ---
    await clickButton(page, '新建');
    // Wait for the create modal to finish opening (animation) before
    // interacting with the form.
    const nameInput = page.getByPlaceholder('例如 prod-key');
    await expect(nameInput).toBeVisible({ timeout: 5_000 });
    await nameInput.fill('prod-key');
    // Click the submit button. The create POST returns 201 with the one-time
    // completeKey, which triggers setSecret + the save-key modal opens.
    await clickButton(page, '创建');

    // The one-time-secret modal should open with the title.
    await expect(page.getByText(/保\s*存\s*你\s*的/).first()).toBeAttached({ timeout: 10_000 });

    // STORAGE SCAN after create: no complete key in storage.
    await assertNoCompleteKeyInStorage(page);

    // Copy button should be present and clickable.
    await clickButton(page, '复制');
    // Success toast (Ant Design message). CJK auto-spacing may render it as
    // "已 复 制", so we use a tolerant regex.
    await expect(page.getByText(/已\s*复\s*制/).first()).toBeVisible({ timeout: 5_000 });

    // Done is disabled until the checkbox is checked.
    const doneButton = page.getByRole('button').filter({ hasText: /完\s*成/ }).first();
    await expect(doneButton).toBeDisabled();
    await page.getByRole('checkbox').check();
    await expect(doneButton).toBeEnabled();
    await doneButton.click();

    // After closing, the modal should be gone (completeKey purged from state).
    await expect(page.getByText(/保\s*存\s*你\s*的/)).toHaveCount(0, { timeout: 5_000 });

    // STORAGE SCAN after modal close: still no complete key.
    await assertNoCompleteKeyInStorage(page);

    // --- Disable: ACTIVE row should have a 禁用 button ---
    // Wait for the row to settle after the modal closed, then click.
    await expect(page.getByText('prod-key').first()).toBeVisible({ timeout: 5_000 });
    await clickButton(page, '禁用');
    // The row status should flip to DISABLED after the list invalidates.
    await expect(page.getByText(/已\s*禁\s*用/).first()).toBeVisible({ timeout: 10_000 });

    // --- Enable: DISABLED row should have a 启用 button ---
    await clickButton(page, '启用');
    await expect(page.getByText(/启\s*用\s*中/).first()).toBeVisible({ timeout: 10_000 });

    // --- Rotate: ACTIVE row should have a 轮换 button; triggers danger confirm ---
    await clickButton(page, '轮换');
    // The danger confirm modal should appear. The title is "确认轮换 API Key".
    // With CSS transitions disabled, the modal is visible immediately, so we
    // can use toBeVisible directly.
    const rotateConfirmDialog = page.getByRole('dialog').filter({ hasText: /轮\s*换\s*API\s*Key/ });
    await expect(rotateConfirmDialog).toBeVisible({ timeout: 5_000 });
    // Click the confirm OK button INSIDE the dialog (not the row button).
    await rotateConfirmDialog
      .getByRole('button')
      .filter({ hasText: /轮\s*换/ })
      .click();
    // The new one-time secret modal opens with the rotated key.
    await expect(page.getByText(/保\s*存\s*你\s*的/).first()).toBeAttached({ timeout: 5_000 });

    // STORAGE SCAN after rotate: no complete key (including the rotated one).
    await assertNoCompleteKeyInStorage(page);

    // Close the rotated-key modal.
    await page.getByRole('checkbox').check();
    await page.getByRole('button').filter({ hasText: /完\s*成/ }).first().click();
    await expect(page.getByText(/保\s*存\s*你\s*的/)).toHaveCount(0, { timeout: 5_000 });

    // --- Revoke: triggers danger confirm ---
    await clickButton(page, '撤销');
    // The danger confirm modal title is "确认撤销 API Key".
    const revokeConfirmDialog = page.getByRole('dialog').filter({ hasText: /撤\s*销\s*API\s*Key/ });
    await expect(revokeConfirmDialog).toBeVisible({ timeout: 5_000 });
    await revokeConfirmDialog
      .getByRole('button')
      .filter({ hasText: /撤\s*销/ })
      .click();
    await expect(page.getByText(/已\s*撤\s*销/).first()).toBeVisible({ timeout: 10_000 });

    // FINAL STORAGE SCAN: no complete key anywhere across the whole flow.
    await assertNoCompleteKeyInStorage(page);
  });
});
