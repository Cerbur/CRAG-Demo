import { test, expect, type Page, type Route } from '@playwright/test';

/**
 * 22.3 auth E2E regression.
 *
 * Covers:
 *  - anonymous visit to a protected route redirects to /login.
 *  - successful login lands on /app/knowledge.
 *  - failed login (invalid credentials) stays on /login with a form error.
 *  - page reload restores the session via the refresh cookie (bootstrap).
 *  - logout clears local state and returns to /login.
 *  - localStorage / sessionStorage contain NO token at any point.
 *
 * The Console API is mocked at the network layer with `page.route`. No real
 * backend is required. Placeholders are used for any secret material.
 */

const PLACEHOLDER_JWT = 'placeholder.access.jwt';
const PLACEHOLDER_JWT_2 = 'placeholder.access.jwt.refreshed';

function assertNoTokenInStorage(page: Page): Promise<void> {
  return page.evaluate(() => {
    const keys = [
      ...Object.keys(localStorage),
      ...Object.keys(sessionStorage),
    ];
    for (const k of keys) {
      const v = localStorage.getItem(k) ?? sessionStorage.getItem(k) ?? '';
      expect(v, `storage key "${k}" must not contain a token`).not.toMatch(/jwt|token|bearer|password/i);
    }
  });
}

interface MockOptions {
  readonly loginStatus?: 200 | 401;
  readonly meFirstStatus?: 200 | 401;
  readonly tenantsItems?: ReadonlyArray<{ tenantId: string; name: string; role: string }>;
}

/**
 * Install Console API mocks. By default: register/login/refresh succeed with
 * the placeholder JWT; /me returns alice; /tenants returns one OWNER tenant.
 */
async function mockConsoleApi(page: Page, options: MockOptions = {}): Promise<void> {
  const loginStatus = options.loginStatus ?? 200;
  const meFirstStatus = options.meFirstStatus ?? 200;
  const tenantsItems = options.tenantsItems ?? [
    { tenantId: '2001', name: 'alice 的默认租户', role: 'OWNER' },
  ];

  // Per-mock invocation counters (closed over by the handler). The first /me
  // call can return 401 to force the refresh path; subsequent calls return 200.
  const counters = { me: 0 };

  await page.route('**/console-api/api/v1/auth/**', (route) => handleAuth(route));
  await page.route('**/console-api/api/v1/tenants', (route) => {
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, code: 0, result: { items: tenantsItems, nextPageToken: '' } }),
    });
  });

  function handleAuth(route: Route): Promise<void> {
    const url = route.request().url();
    const method = route.request().method();

    if (url.includes('/auth/login') && method === 'POST') {
      if (loginStatus === 200) {
        return route.fulfill({
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
        });
      }
      return route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({
          success: false,
          code: 40102,
          result: {
            message: 'Invalid credentials',
            traceId: 'e2e-trace',
            reason: 'INVALID_CREDENTIALS',
            retryable: false,
            fieldErrors: [],
          },
        }),
      });
    }

    if (url.includes('/auth/register') && method === 'POST') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          code: 0,
          result: {
            accessToken: PLACEHOLDER_JWT,
            accessExpiresAt: '2026-07-02T12:00:00Z',
            user: { userId: '1001', nickname: 'alice' },
            defaultTenant: { tenantId: '2001', name: 'alice 的默认租户', role: 'OWNER' },
          },
        }),
      });
    }

    if (url.includes('/auth/refresh') && method === 'POST') {
      return route.fulfill({
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
      });
    }

    if (url.includes('/auth/logout') && method === 'POST') {
      return route.fulfill({ status: 204, body: '' });
    }

    if (url.includes('/auth/me') && method === 'GET') {
      counters.me += 1;
      if (counters.me === 1 && meFirstStatus === 401) {
        return route.fulfill({
          status: 401,
          contentType: 'application/json',
          body: JSON.stringify({
            success: false,
            code: 40101,
            result: {
              message: 'Unauthenticated',
              traceId: 'e2e-trace',
              reason: 'UNAUTHENTICATED',
              retryable: false,
              fieldErrors: [],
            },
          }),
        });
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          code: 0,
          result: { userId: '1001', nickname: 'alice' },
        }),
      });
    }

    return route.continue();
  }
}

test.describe('auth flow', () => {
  test('anonymous visit to protected route redirects to /login', async ({ page }) => {
    // No refresh cookie → /me 401 → refresh fails (no cookie handler returns 401
    // via the unhandled route default). We mock /me to 401 and /refresh to 401.
    await page.route('**/console-api/api/v1/auth/me', (route) => {
      return route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({
          success: false,
          code: 40101,
          result: {
            message: 'Unauthenticated',
            traceId: 'e2e',
            reason: 'UNAUTHENTICATED',
            retryable: false,
            fieldErrors: [],
          },
        }),
      });
    });
    await page.route('**/console-api/api/v1/auth/refresh', (route) =>
      route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({
          success: false,
          code: 40101,
          result: {
            message: 'Unauthenticated',
            traceId: 'e2e',
            reason: 'UNAUTHENTICATED',
            retryable: false,
            fieldErrors: [],
          },
        }),
      }),
    );

    await page.goto('/app/knowledge');
    await expect(page).toHaveURL(/\/login$/);
    await assertNoTokenInStorage(page);
  });

  test('successful login lands on /app/knowledge and shows account menu', async ({ page }) => {
    await mockConsoleApi(page);
    await page.goto('/login');

    await page.getByPlaceholder('用户名').fill('alice');
    await page.getByPlaceholder('密码').fill('password123456');
    await page
      .getByRole('button')
      .filter({ hasText: /登\s*录/ })
      .click();

    await expect(page).toHaveURL(/\/app\/knowledge$/, { timeout: 10_000 });
    await expect(page.getByText('alice')).toBeVisible({ timeout: 10_000 });
    await assertNoTokenInStorage(page);
  });

  test('failed login stays on /login with a form error', async ({ page }) => {
    await mockConsoleApi(page, { loginStatus: 401 });
    await page.goto('/login');

    await page.getByPlaceholder('用户名').fill('alice');
    await page.getByPlaceholder('密码').fill('wrong');
    await page
      .getByRole('button')
      .filter({ hasText: /登\s*录/ })
      .click();

    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByText('Authentication failed')).toBeVisible({ timeout: 10_000 });
    await assertNoTokenInStorage(page);
  });

  test('page reload restores the session via the refresh cookie', async ({ page }) => {
    // First /me returns 401 → refresh returns 200 → /me replayed with 200.
    await mockConsoleApi(page, { meFirstStatus: 401 });
    await page.goto('/login');

    await page.getByPlaceholder('用户名').fill('alice');
    await page.getByPlaceholder('密码').fill('password123456');
    await page
      .getByRole('button')
      .filter({ hasText: /登\s*录/ })
      .click();
    await expect(page).toHaveURL(/\/app\/knowledge$/, { timeout: 10_000 });

    // Reload — bootstrap should hit /me (401) → refresh → /me (200) and stay.
    await page.reload();
    await expect(page).toHaveURL(/\/app\/knowledge$/, { timeout: 10_000 });
    await expect(page.getByText('alice')).toBeVisible({ timeout: 10_000 });
    await assertNoTokenInStorage(page);
  });

  test('logout clears the session and returns to /login', async ({ page }) => {
    await mockConsoleApi(page);
    await page.goto('/login');
    await page.getByPlaceholder('用户名').fill('alice');
    await page.getByPlaceholder('密码').fill('password123456');
    await page
      .getByRole('button')
      .filter({ hasText: /登\s*录/ })
      .click();
    await expect(page).toHaveURL(/\/app\/knowledge$/, { timeout: 10_000 });

    await page.getByRole('button', { name: /账户菜单/ }).click();
    await page.getByText('退出登录').click();

    await expect(page).toHaveURL(/\/login$/, { timeout: 10_000 });
    await assertNoTokenInStorage(page);
  });
});
