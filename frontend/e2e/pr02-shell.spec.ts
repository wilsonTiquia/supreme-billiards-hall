import { test, expect, OWNER, COUNTER } from './fixtures';
import type { Locator, Page } from '@playwright/test';

const adminRoutes = [
  ['Dashboard', '/dashboard'], ['Reports', '/admin/reports'], ['Products', '/admin/products'],
  ['Stock', '/admin/stock'], ['Sales', '/admin/sales'], ['Audit', '/admin/audit'],
  ['Categories', '/admin/categories'], ['Tables', '/admin/tables'],
  ['Customer types', '/admin/customer-types'], ['Expense categories', '/admin/expense-categories'],
  ['Vouchers', '/admin/vouchers'], ['Staff', '/admin/staff'], ['Settings', '/admin/settings'],
];
const floorRoutes = [['Floor', '/floor'], ['Quick sale', '/quick-sale'], ['Expenses', '/expenses'],
  ['Unsettled', '/unsettled'], ['End of day', '/end-of-day']];
const mainNav = (page: Page) => page.getByRole('navigation', { name: 'Main', exact: true });
const strip = (page: Page) => page.getByRole('banner', { name: 'Current business day' });

async function inViewport(control: Locator, height: number) {
  const box = await control.boundingBox();
  expect(box).not.toBeNull();
  expect(box!.y).toBeGreaterThanOrEqual(0);
  expect(box!.y + box!.height).toBeLessThanOrEqual(height);
  // Drawer transforms can introduce sub-pixel floating-point noise.
  expect(Math.round(box!.height * 100) / 100).toBeGreaterThanOrEqual(44);
  expect(Math.round(box!.width * 100) / 100).toBeGreaterThanOrEqual(44);
}

for (const width of [1280, 400]) {
  test(`all owner destinations remain reachable on a short ${width}px screen`, async ({ page, signIn }) => {
    test.setTimeout(120_000);
    await page.setViewportSize({ width, height: 540 });
    await signIn(OWNER);
    for (const compact of width === 1280 ? [false, true] : [false]) {
      if (compact) await page.getByLabel('Collapse the menu').click();
      for (const routes of [adminRoutes, floorRoutes]) {
        if (width === 400) await page.getByLabel('Open the menu').click();
        await mainNav(page).getByRole('group', { name: 'Workspace' }).getByRole('link', { name: routes === adminRoutes ? 'Admin' : 'Floor', exact: true }).click();
        if (width === 400) await expect(page.getByLabel('Open the menu')).toHaveAttribute('aria-expanded', 'false');
        for (const [label, path] of routes) {
          if (width === 400) await page.getByLabel('Open the menu').click();
          const nav = mainNav(page);
          await inViewport(nav.getByRole('link', { name: /Your account/ }), 540);
          await inViewport(nav.getByRole('button', { name: /Switch to .* theme/ }), 540);
          const link = nav.getByRole('link', { name: label, exact: true }).last();
          await link.scrollIntoViewIfNeeded();
          await inViewport(link, 540);
          await link.click();
          await expect(page).toHaveURL(url => url.pathname === path);
          if (width === 400) await expect(page.getByLabel('Open the menu')).toHaveAttribute('aria-expanded', 'false');
          if (path === '/floor') await expect(strip(page)).toBeVisible();
          else await expect(strip(page)).toHaveCount(0);
        }
      }
      if (width === 400) await page.getByLabel('Open the menu').click();
      await mainNav(page).getByRole('group', { name: 'Workspace' }).getByRole('link', { name: 'Admin', exact: true }).click();
    }
  });
}

test('drawer traps focus, restores it on dismissal, and keeps account workspace/theme', async ({ page, signIn }) => {
  await page.setViewportSize({ width: 400, height: 540 });
  await signIn(OWNER);
  const opener = page.getByLabel('Open the menu');
  await opener.click();
  const nav = mainNav(page);
  await expect(nav.getByLabel('Close the menu')).toBeFocused();
  await nav.getByRole('link', { name: 'Supreme Billiard Hall' }).focus();
  await page.keyboard.press('Shift+Tab');
  await expect(nav.getByRole('button', { name: /Switch to .* theme/ })).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(nav.getByRole('link', { name: 'Supreme Billiard Hall' })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(opener).toBeFocused();
  await opener.click();
  await page.mouse.click(390, 200);
  await expect(opener).toBeFocused();
  await opener.click();
  const theme = await page.locator('html').getAttribute('data-theme');
  await nav.getByRole('button', { name: /Switch to .* theme/ }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', theme === 'light' ? 'dark' : 'light');
  await nav.getByRole('link', { name: /Your account/ }).click();
  await expect(page).toHaveURL('/account');
  await expect(opener).toHaveAttribute('aria-expanded', 'false');
  await page.reload();
  await opener.click();
  await expect(nav.getByRole('link', { name: 'Admin', exact: true })).toHaveAttribute('aria-current', 'true');
  await expect(page.locator('html')).toHaveAttribute('data-theme', theme === 'light' ? 'dark' : 'light');
  await expect(strip(page)).toHaveCount(0);
});

test('employees retain counter routes and cannot switch to the admin workspace', async ({ page, signIn, hall }) => {
  void hall; // Activate the employee login even when this spec runs independently.
  await signIn(COUNTER);
  for (const width of [1280, 400]) {
    await page.setViewportSize({ width, height: 540 });
    await page.goto('/admin/tables');
    await expect(page).toHaveURL('/floor');
    if (width === 400) await page.getByLabel('Open the menu').click();
    await expect(mainNav(page).getByRole('group', { name: 'Workspace' })).toHaveCount(0);
    await expect(mainNav(page).getByRole('link', { name: 'Reports' })).toHaveCount(0);
    for (const [label] of floorRoutes) await expect(mainNav(page).getByRole('link', { name: label, exact: true })).toBeVisible();
    await mainNav(page).getByRole('link', { name: /Your account/ }).click();
    await expect(page).toHaveURL('/account');
  }
});

for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
  test(`floor strip sticks below overlays and clears focused controls: ${width}px ${theme}`, async ({ page, signIn }) => {
    await page.setViewportSize({ width, height: 720 });
    await signIn(OWNER);
    await page.evaluate(t => localStorage.setItem('supreme.theme.pos', t), theme);
    await page.goto('/floor/');
    await expect(strip(page)).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
    const cards = page.locator('main button').filter({ has: page.locator('.figure-table-number') });
    await expect(cards).toHaveCount(7);
    await cards.last().scrollIntoViewIfNeeded();
    await expect.poll(async () => (await strip(page).boundingBox())!.y).toBe(0);
    const header = (await strip(page).boundingBox())!;
    if (process.env.PR02_CAPTURE) await page.screenshot({ animations: 'disabled',
      path: `../docs/qa/pr02/after-floor-sticky-${theme}-${width}.png` });
    // Native keyboard focus scrolls the card clear of the sticky strip.
    await cards.first().focus();
    await expect.poll(async () => (await cards.first().boundingBox())!.y).toBeGreaterThanOrEqual(header.height);
    await cards.last().click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Close', exact: true }).click();
    if (width === 400) {
      await page.getByLabel('Open the menu').scrollIntoViewIfNeeded();
      await page.getByLabel('Open the menu').click();
      await mainNav(page).getByLabel('Close the menu').click();
    }
    for (const route of ['/sessions/00000000-0000-0000-0000-000000000000', '/checkout/00000000-0000-0000-0000-000000000000', '/receipt/00000000-0000-0000-0000-000000000000', '/account/password']) {
      await page.goto(route);
      await expect(strip(page)).toHaveCount(0);
    }
  });
}

test('navigation works while floor data is loading, empty, or unavailable', async ({ page, signIn }) => {
  await signIn(OWNER);
  let release!: () => void;
  const pending = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/v1/tables', async route => {
    await pending;
    await route.fulfill({ json: { success: true, data: { serverNow: '2026-09-30T15:00:00Z', tables: [] } } });
  });
  await page.goto('/floor');
  await expect(page.getByText('Loading the floor…')).toBeVisible();
  await mainNav(page).getByRole('link', { name: 'Quick sale', exact: true }).click();
  await expect(page).toHaveURL('/quick-sale');
  release();
  await page.goto('/floor');
  await expect(page.locator('.figure-table-number')).toHaveCount(0);
  await expect(strip(page)).toBeVisible();
  await page.unroute('**/api/v1/tables');
  await page.route('**/api/v1/tables', route => route.fulfill({ status: 503,
    json: { success: false, message: 'Floor temporarily unavailable' } }));
  await page.reload();
  await expect(page.getByText('Floor temporarily unavailable')).toBeVisible({ timeout: 20_000 });
  await mainNav(page).getByRole('link', { name: 'Quick sale', exact: true }).click();
  await expect(page).toHaveURL('/quick-sale');
});


test('long account names and a large floor keep compact navigation usable', async ({ page, signIn }) => {
  await signIn(OWNER);
  await page.route('**/api/v1/auth/me', async route => {
    const response = await route.fetch();
    const body = await response.json();
    body.data.fullName = 'Alexandra Maria Dela Cruz — Evening shift supervisor';
    await route.fulfill({ json: body });
  });
  await page.route('**/api/v1/tables', async route => {
    const response = await route.fetch();
    const body = await response.json();
    body.data.tables = Array.from({ length: 40 }, (_, i) => ({ ...body.data.tables[0],
      id: `fixture-table-${i}`, name: `Table ${i + 1}`, tableNumber: i + 1 }));
    await route.fulfill({ json: body });
  });
  await page.setViewportSize({ width: 1280, height: 540 });
  await page.goto('/floor');
  await expect(page.locator('.figure-table-number')).toHaveCount(40);
  await inViewport(mainNav(page).getByRole('link', { name: /Your account: Alexandra/ }), 540);
  await page.getByLabel('Collapse the menu').click();
  const quickSale = mainNav(page).getByRole('link', { name: 'Quick sale', exact: true });
  await quickSale.focus();
  const hint = mainNav(page).locator('span[aria-hidden="true"]').filter({ hasText: /^Quick sale$/ });
  await expect(hint).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(hint).toHaveCount(0);
  await quickSale.hover();
  await expect(hint).toBeVisible();
  await quickSale.click();
  await expect(page).toHaveURL('/quick-sale');
  await expect(strip(page)).toHaveCount(0);
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 1280);
  await mainNav(page).getByRole('group', { name: 'Workspace' }).getByRole('link', { name: 'Admin', exact: true }).click();
  await mainNav(page).getByRole('link', { name: 'Settings', exact: true }).focus();
  await expect(mainNav(page).locator('span[aria-hidden="true"]').filter({ hasText: /^Settings$/ })).toBeVisible();
});
