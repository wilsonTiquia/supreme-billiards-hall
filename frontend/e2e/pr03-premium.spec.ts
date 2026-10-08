import { test, expect, OWNER, apiAs, apiGet, apiPost } from './fixtures';
import { analyticsFixtures } from './pr03-fixtures';
import type { FloorView, PoolTable } from '../src/api/types';

const idFor = (number: number) => `019a0000-0000-7000-8000-${String(number).padStart(12, '0')}`;

test('table administration reads back classification without changing either rate mode', async ({ page, signIn }) => {
  const owner = await apiAs(OWNER);
  try {
    const initial = await apiGet<FloorView>(owner, '/api/v1/tables');
    for (let n = 1; n <= 7; n++) {
      const table = initial.tables.find(t => t.id === idFor(n))!;
      expect(table.isPremium).toBe(n <= 3);
      expect(table.ratePerMinute).toBe(n <= 4 ? 4 : 5);
    }
    const hourly = await apiPost<PoolTable>(owner, '/api/v1/tables', { name: 'Hourly classification QA', ratePerHour: 200 });
    expect(hourly.isPremium).toBe(false);
    await signIn(OWNER);
    await page.goto('/admin/tables');
    const seeded = page.getByRole('listitem').filter({ hasText: 'Table 1' });
    const hourlyRow = page.getByRole('listitem').filter({ hasText: 'Hourly classification QA' });
    for (const row of [seeded, hourlyRow]) {
      await row.getByRole('button', { name: /^Actions for/ }).click();
      await page.getByRole('menuitem', { name: 'Edit', exact: true }).click();
      const premium = page.getByLabel('Premium table', { exact: true });
      const wasPremium = await premium.isChecked();
      await premium.setChecked(!wasPremium);
      const saved = page.waitForResponse(r => r.request().method() === 'PUT' && /\/api\/v1\/tables\/[^/]+$/.test(new URL(r.url()).pathname));
      await page.getByRole('button', { name: 'Save', exact: true }).click();
      expect((await saved).status()).toBe(200);
      await page.reload();
      await row.getByRole('button', { name: /^Actions for/ }).click();
      await page.getByRole('menuitem', { name: 'Edit', exact: true }).click();
      await expect(premium).toBeChecked({ checked: !wasPremium });
      await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    }
    const floor = await apiGet<FloorView>(owner, '/api/v1/tables');
    const first = floor.tables.find(t => t.id === idFor(1))!;
    expect(first.isPremium).toBe(false);
    expect([first.ratePerMinute, first.ratePerHour, first.effectiveRatePerHour]).toEqual([4, null, 240]);
    const changed = floor.tables.find(t => t.id === hourly.id)!;
    expect(changed.isPremium).toBe(true);
    expect([changed.ratePerMinute, changed.ratePerHour, changed.effectiveRatePerHour]).toEqual([3.3333, 200, 199.998]);
    await page.getByRole('button', { name: 'New table', exact: true }).click();
    await expect(page.getByLabel('Premium table', { exact: true })).not.toBeChecked();
    await page.getByLabel('Name', { exact: true }).fill('UI Standard by default');
    await page.getByLabel('Rate per minute', { exact: true }).fill('6');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByRole('listitem').filter({ hasText: 'UI Standard by default' })).toContainText('Standard');
    // Restore the seeded classification so subsequent screenshot/report checks see the backfill.
    const restore = await owner.put(`/api/v1/tables/${first.id}`, { data: { name: first.name, tableNumber: first.tableNumber, ratePerMinute: first.ratePerMinute, isActive: first.isActive, isPremium: true } });
    expect(restore.ok()).toBe(true);
    await owner.delete(`/api/v1/tables/${hourly.id}`);
    const created = (await apiGet<FloorView>(owner, '/api/v1/tables')).tables.find(t => t.name === 'UI Standard by default')!;
    await owner.delete(`/api/v1/tables/${created.id}`);
  } finally { await owner.dispose(); }
});

test('dashboard and report stars follow explicit metadata despite misleading names or rates', async ({ page, signIn }) => {
  await analyticsFixtures(page);
  await signIn(OWNER);
  for (const path of ['/dashboard?date=2026-09-25', '/admin/reports?from=2026-09-01&to=2026-09-30']) {
    await page.goto(path);
    await expect(page.locator('.analytics-table-tiles')).toBeVisible();
    for (let n = 1; n <= 7; n++) {
      const name = n < 5 ? `Table ${n}` : `Table ${n} (Premium)`;
      const tile = page.locator('.analytics-table-tiles li').filter({ has: page.getByRole('heading', { name, exact: n > 3 }) });
      await expect(tile.getByRole('img', { name: 'Premium table' })).toHaveCount(n <= 3 ? 1 : 0);
    }
    await expect(page.getByText('★ Premium', { exact: true })).toBeVisible();
  }
  // Feed contradictory current metadata: the same name and amount must gain/lose the star.
  await page.route('**/api/v1/reports/daily?*', async route => {
    const response = await route.fetch();
    const payload = await response.json();
    payload.data.tableUtilisation = [{ tableId: idFor(5), tableName: 'Table 5 (Premium)', isPremium: false, occupiedMinutes: 60, utilisationPercent: 5.3 }];
    await route.fulfill({ response, json: payload });
  });
  await page.goto('/dashboard?date=2026-09-25');
  await expect(page.locator('.analytics-table-tiles li')).toHaveCount(1);
  await expect(page.locator('.analytics-table-tiles').getByRole('img', { name: 'Premium table' })).toHaveCount(0);
  await expect(page.getByText('★ Premium', { exact: true })).toHaveCount(0);
});
