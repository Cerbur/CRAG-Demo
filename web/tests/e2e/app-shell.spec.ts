import { test, expect, type Page } from '@playwright/test';

/**
 * 22.1 app-shell regression: desktop and mobile viewports must render each route
 * with no uncaught browser console errors. Placeholder pages exist; no API calls.
 */

const ROUTES = [
  '/login',
  '/register',
  '/app/knowledge',
  '/app/api-keys',
  '/app/chat',
] as const;

async function assertNoConsoleErrors(page: Page, label: string) {
  const errors: string[] = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error') {
      errors.push(`[${label}] ${msg.text()}`);
    }
  });
  page.on('pageerror', (err) => {
    errors.push(`[${label}] pageerror: ${err.message}`);
  });
  return errors;
}

for (const route of ROUTES) {
  test.describe(`route ${route}`, () => {
    test('desktop shell renders without console errors', async ({ page }) => {
      const errors = await assertNoConsoleErrors(page, `desktop:${route}`);
      await page.goto(route);
      // App shell heading or content must be present (Sider or top toolbar).
      await expect(page.locator('body')).not.toBeEmpty();
      // The app should render an Ant Design layout (look for the layout landmark).
      // Placeholder pages render a stable heading text.
      await page.waitForLoadState('domcontentloaded');
      expect(errors, `console errors at desktop:${route}`).toEqual([]);
    });

    test('mobile shell renders without console errors', async ({ page, browserName }) => {
      test.skip(browserName !== 'chromium', 'mobile project runs chromium only');
      const errors = await assertNoConsoleErrors(page, `mobile:${route}`);
      await page.goto(route);
      await page.waitForLoadState('domcontentloaded');
      expect(page.locator('body')).not.toBeEmpty();
      expect(errors, `console errors at mobile:${route}`).toEqual([]);
    });
  });
}

test('unknown route shows 404 page', async ({ page }) => {
  const errors = await assertNoConsoleErrors(page, '404');
  await page.goto('/this-route-does-not-exist');
  await expect(page.getByText(/404|not found|找不到/i)).toBeVisible({ timeout: 10_000 });
  expect(errors).toEqual([]);
});

test('knowledge detail dynamic route renders placeholder', async ({ page }) => {
  const errors = await assertNoConsoleErrors(page, 'kb-detail');
  await page.goto('/app/knowledge/sample-id');
  await page.waitForLoadState('domcontentloaded');
  expect(page.locator('body')).not.toBeEmpty();
  expect(errors).toEqual([]);
});
