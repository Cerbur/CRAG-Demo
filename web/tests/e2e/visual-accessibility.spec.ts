import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

const VIEWPORTS = [
  { name: 'desktop', width: 1440, height: 900 },
  { name: 'tablet', width: 1024, height: 768 },
  { name: 'mobile', width: 390, height: 844 },
] as const;

async function installMocks(page: Page): Promise<void> {
  await page.route('**/console-api/api/v1/auth/login', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        code: 0,
        result: {
          accessToken: 'placeholder.access.jwt',
          accessExpiresAt: '2026-07-02T12:00:00Z',
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
          items: [{ tenantId: '2001', name: '默认租户', role: 'OWNER' }],
          nextPageToken: '',
        },
      }),
    }),
  );
  await page.route('**/console-api/api/v1/tenants/2001/knowledge-bases**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        code: 0,
        result: {
          items: [
            {
              knowledgeBaseId: '3001',
              tenantId: '2001',
              name: '超长知识库名称用于验证内容不会挤压操作按钮或导致页面横向溢出',
              apiKeyReady: true,
              createdAt: '2026-07-02T09:00:00Z',
              updatedAt: '2026-07-02T09:00:00Z',
            },
          ],
          nextPageToken: '',
        },
      }),
    }),
  );
}

async function login(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('alice');
  await page.getByPlaceholder('密码').fill('password123456');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).toHaveURL(/\/app\/knowledge$/);
  await expect(page.getByText(/超长知识库名称/)).toBeVisible();
}

for (const viewport of VIEWPORTS) {
  test(`${viewport.name} approved layout is non-empty, accessible and stable`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const errors: string[] = [];
    page.on('console', (message) => {
      if (message.type() === 'error') errors.push(message.text());
    });
    page.on('pageerror', (error) => errors.push(error.message));
    await installMocks(page);
    await login(page);
    await page.addStyleTag({
      content: '*,*::before,*::after{animation:none!important;transition:none!important}',
    });

    const body = page.locator('body');
    await expect(body).not.toBeEmpty();
    const dimensions = await body.evaluate((element) => ({
      scrollWidth: element.scrollWidth,
      clientWidth: element.clientWidth,
      scrollHeight: element.scrollHeight,
      clientHeight: element.clientHeight,
    }));
    expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth);
    expect(dimensions.scrollHeight).toBeGreaterThanOrEqual(dimensions.clientHeight);

    const accessibility = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
    expect(accessibility.violations).toEqual([]);
    const screenshot = await page.screenshot({
      path: `test-results/visual/knowledge-${viewport.width}x${viewport.height}.png`,
      fullPage: true,
    });
    expect(screenshot.byteLength).toBeGreaterThan(10_000);
    expect(errors).toEqual([]);
  });
}

test('mobile drawer and modal retain focusable controls and touch targets', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await installMocks(page);
  await login(page);
  await page.addStyleTag({
    content: '*,*::before,*::after{animation:none!important;transition:none!important}',
  });
  await page.getByRole('button', { name: '打开导航' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('button', { name: '打开导航' })).toBeFocused();
  await page.getByRole('button', { name: '新建知识库' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  const buttons = page.locator('.ant-modal').getByRole('button');
  for (let index = 0; index < (await buttons.count()); index += 1) {
    const box = await buttons.nth(index).boundingBox();
    if (box) expect(Math.max(box.width, box.height)).toBeGreaterThanOrEqual(44);
  }
});
