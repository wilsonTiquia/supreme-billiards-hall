import { test, expect, OWNER } from './fixtures';
import { listFixtures } from './pr01-fixtures';

test('Sales uses zero-based URL/API pages, preserves date and traverses exact rows', async ({ page, signIn }) => {
  await listFixtures(page);
  await signIn(OWNER);
  await page.goto('/admin/sales?date=2026-09-29');
  const nav = page.getByRole('navigation', { name: 'Sales pages' });
  await expect(nav.getByRole('button', { name: 'Previous' })).toBeDisabled();
  await expect(page.locator('tbody tr')).toHaveCount(50);
  await expect(page.locator('tbody tr').first()).toContainText('#101');
  const request = page.waitForRequest(r => r.url().includes('/bills?') && new URL(r.url()).searchParams.get('page') === '1');
  await nav.getByRole('button', { name: 'Next' }).click();
  expect(new URL((await request).url()).searchParams.get('businessDate')).toBe('2026-09-29');
  await expect(page).toHaveURL(/date=2026-09-29&page=1$/);
  await expect(page.locator('tbody tr').first()).toContainText('#51');
  await expect(nav).toContainText('Page 2 of 3');
  await nav.getByRole('button', { name: 'Next' }).click();
  await expect(page.locator('tbody tr')).toHaveCount(1);
  await expect(page.locator('tbody tr')).toContainText('#1');
  await expect(nav.getByRole('button', { name: 'Next' })).toBeDisabled();
  await nav.getByRole('button', { name: 'Previous' }).click();
  await expect(nav).toContainText('Page 2 of 3');
  await page.reload();
  await expect(page.locator('tbody tr').first()).toContainText('#51');
  await expect(page.locator('a button')).toHaveCount(0);
  await page.getByLabel('Business day', { exact: true }).fill('2026-09-28');
  await expect(page).toHaveURL(/date=2026-09-28&page=0$/);
});

test('Audit keeps filters and newest-first results while changing zero-based pages', async ({ page, signIn }) => {
  await listFixtures(page);
  await page.route('**/api/v1/audit/filters', route => route.fulfill({ json: { success: true, data: {
    actions: [{ action: 'PRODUCT_UPDATED', label: 'Product updated' }], actors: [{ id: 'owner-id', name: 'Owner' }],
  } } }));
  await signIn(OWNER);
  await page.goto('/admin/audit');
  await page.getByLabel('What happened').selectOption('PRODUCT_UPDATED');
  await page.getByLabel('Who', { exact: true }).selectOption('owner-id');
  const nav = page.getByRole('navigation', { name: 'Audit pages' });
  await expect(page.locator('tbody tr').first()).toContainText('Product 51');
  await expect(nav.getByRole('button', { name: 'Previous' })).toBeDisabled();
  const request = page.waitForRequest(r => r.url().includes('/audit/feed?') && new URL(r.url()).searchParams.get('page') === '1');
  await nav.getByRole('button', { name: 'Next' }).click();
  const query = new URL((await request).url()).searchParams;
  expect(query.get('action')).toBe('PRODUCT_UPDATED');
  expect(query.get('actor')).toBe('owner-id');
  expect(query.get('size')).toBe('25');
  await expect(page.locator('tbody tr').first()).toContainText('Product 26');
  await nav.getByRole('button', { name: 'Next' }).click();
  await expect(page.locator('tbody tr')).toHaveCount(1);
  await expect(page.locator('tbody tr')).toContainText('Product 1');
  await expect(nav.getByRole('button', { name: 'Next' })).toBeDisabled();
  await page.getByLabel('What happened').selectOption('');
  await expect(nav).toContainText('Page 1 of 3');
});

test('Reports retains all rows, sort, filter, grouping and export independently of pages', async ({ page, signIn }) => {
  await signIn(OWNER);
  await page.goto('/admin/reports?from=2026-08-01&to=2026-08-31');
  const nav = page.getByRole('navigation', { name: 'Report pages' });
  const table = page.getByRole('table', { name: 'sales report from' });
  await expect(nav).toContainText('1–15 of 31 rows');
  await expect(nav.getByRole('button', { name: 'Previous' })).toBeDisabled();
  const dates: string[] = [];
  for (let n = 1; n <= 3; n++) {
    await expect(nav).toContainText(`Page ${n} of 3`);
    dates.push(...await table.locator('tbody tr td:first-child').allTextContents());
    if (n < 3) await nav.getByRole('button', { name: 'Next' }).click();
  }
  expect(dates).toEqual(Array.from({ length: 31 }, (_, i) => `2026-08-${String(i + 1).padStart(2, '0')}`));
  await expect(nav.getByRole('button', { name: 'Next' })).toBeDisabled();
  await nav.getByRole('button', { name: 'Previous' }).click();
  await page.getByRole('button', { name: 'Export', exact: false }).click();
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'CSV · sales (31 rows)' }).click();
  const stream = await (await download).createReadStream();
  let csv = ''; for await (const chunk of stream!) csv += chunk.toString();
  expect(csv.trim().split('\r\n')).toHaveLength(32);
  await page.getByLabel('Sort report').selectOption('name');
  await expect(nav).toContainText('Page 1 of 3');
  await nav.getByRole('button', { name: 'Next' }).click();
  await page.getByLabel('Filter sales').fill('2026-08-31');
  await expect(nav).toContainText('1–1 of 1 rows (31 before filtering)');
  await expect(table.locator('tbody tr')).toHaveCount(1);
  await page.getByLabel('Filter sales').fill('no-match');
  await expect(page.getByText('No matching rows', { exact: true })).toBeVisible();
  await expect(nav.getByRole('button', { name: 'Next' })).toBeDisabled();
  await page.getByLabel('Filter sales').fill('');
  await page.getByLabel('Group sales by').selectOption('month');
  await expect(nav).toContainText('1–1 of 1 rows');
  await expect(table.locator('tbody tr')).toHaveCount(1);
});

test('loading and fetch failures do not offer stale pagination; report refresh disables controls', async ({ page, signIn }) => {
  await signIn(OWNER);
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/v1/bills?*', async route => {
    await gate;
    await route.fulfill({ status: 400, json: { success: false, message: 'Sales unavailable for test' } });
  });
  await page.goto('/admin/sales?date=2026-09-29');
  await expect(page.getByText('Loading sales…')).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Sales pages' })).toHaveCount(0);
  release();
  await expect(page.getByText('Sales unavailable for test')).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Sales pages' })).toHaveCount(0);
  await page.goto('/admin/reports?from=2026-08-01&to=2026-08-31');
  const nav = page.getByRole('navigation', { name: 'Report pages' });
  await expect(nav).toBeVisible();
  const refreshGate = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/v1/reports/period?*', async route => { await refreshGate; await route.continue(); });
  await page.getByRole('button', { name: 'Refresh' }).click();
  await expect(nav.getByRole('button', { name: 'Previous' })).toBeDisabled();
  await expect(nav.getByRole('button', { name: 'Next' })).toBeDisabled();
  release();
  await expect(nav.getByRole('button', { name: 'Next' })).toBeEnabled();
});
