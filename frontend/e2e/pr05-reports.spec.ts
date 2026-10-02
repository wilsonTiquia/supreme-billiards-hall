import { test, expect, OWNER } from './fixtures';
import { reportFixtures } from './pr05-fixtures';

const url = '/admin/reports?from=2026-09-01&to=2026-09-27';

test('financial equation shows each server field, comparisons and debt age totals', async ({ page, signIn }) => {
  await reportFixtures(page);
  await signIn(OWNER);
  await page.goto(url);
  const summary = page.getByRole('region', { name: 'Financial summary' });
  for (const [label, amount] of [['Sales', '₱335,666'], ['Product costs', '₱152,574'], ['After product costs', '₱183,092'], ['Operating expenses', '₱79,700'], ['After recorded costs', '₱103,392']]) {
    await expect(summary.getByRole('group', { name: label, exact: true })).toContainText(amount);
  }
  await expect(summary.getByText('▼ 21%', { exact: true })).toBeVisible();
  await expect(summary).toContainText('You made ₱103,392');
  await expect(summary).toContainText('compared to');
  const owed = page.getByRole('button', { name: /^Still owed/, includeHidden: true });
  await owed.focus();
  await page.keyboard.press('Enter');
  await expect(owed).toHaveAttribute('aria-expanded', 'true');
  const table = page.locator('.reports-detail').filter({ has: owed }).getByRole('table');
  await expect(table.getByRole('columnheader')).toHaveText(['Age', 'Bills', 'Amount']);
  await expect(table.locator('tbody tr')).toHaveText(['From this period2₱1,383', '1–4 weeks before it0₱0', 'Older3₱1,450']);
  await expect(table.locator('tfoot tr')).toHaveText('Total5₱2,833');
  await page.keyboard.press('Space');
  await expect(table).not.toBeVisible();
  await page.setViewportSize({ width: 400, height: 900 });
  await page.emulateMedia({ media: 'print' });
  await expect(page.getByRole('button', { name: 'Open the menu' })).not.toBeVisible();
  await expect(table).toBeVisible();
  await expect(page.getByRole('heading', { name: /^Bills and averages/ })).toBeVisible();
  await expect(page.getByRole('heading', { name: /^Every night/ })).toBeVisible();
  await expect(page.getByRole('heading', { name: /^Products/ })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Refresh', exact: true })).not.toBeVisible();
});

for (const variant of ['negative', 'zero-prior', 'empty'] as const) {
  test(`summary handles ${variant} without invalid figures`, async ({ page, signIn }) => {
    await reportFixtures(page, variant);
    await signIn(OWNER);
    await page.goto(url);
    const summary = page.getByRole('region', { name: 'Financial summary' });
    await expect(summary).toBeVisible();
    await expect(summary).not.toContainText(/NaN|Infinity/);
    if (variant === 'negative') {
      await expect(summary).toContainText('You lost ₱16,908');
      await expect(summary.getByRole('group', { name: 'After recorded costs', exact: true })).toContainText('-₱16,908');
    } else if (variant === 'zero-prior') {
      await expect(summary.getByText('No trading in prior period')).toHaveCount(5);
    } else {
      await expect(summary).toContainText('No closed sales or expenses in this period');
      await expect(summary.getByRole('group', { name: 'After recorded costs', exact: true })).toContainText('₱0');
    }
  });
}

test('date edits apply automatically, reject invalid drafts and preserve URL navigation', async ({ page, signIn }) => {
  await reportFixtures(page);
  await signIn(OWNER);
  await page.goto(url);
  await expect(page.getByRole('region', { name: 'Financial summary' })).toBeVisible();
  await page.getByLabel('To', { exact: true }).fill('2026-09-30');
  await expect(page).toHaveURL(/from=2026-09-01&to=2026-09-30/);
  await page.getByLabel('From', { exact: true }).fill('2026-10-01');
  await expect(page.getByRole('status').filter({ hasText: 'last valid range' })).toBeVisible();
  await expect(page).toHaveURL(/from=2026-09-01&to=2026-09-30/);
  await expect(page.getByRole('button', { name: /^Export/ })).toBeDisabled();
  await page.getByLabel('From', { exact: true }).fill('2026-09-02');
  await expect(page).toHaveURL(/from=2026-09-02&to=2026-09-30/);
  await page.goBack();
  await expect(page.getByLabel('From', { exact: true })).toHaveValue('2026-09-01');
  await page.getByRole('button', { name: 'Last month', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Last month', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.reload();
  await expect(page.getByRole('button', { name: 'Last month', exact: true })).toHaveAttribute('aria-pressed', 'true');
});

test('loading and errors remain explicit and refresh retries the selected range', async ({ page, signIn }) => {
  await signIn(OWNER);
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/v1/reports/period?*', async route => {
    await gate;
    await route.fulfill({ status: 400, json: { success: false, message: 'Report unavailable for test' } });
  });
  await page.goto(url);
  await expect(page.getByText('Loading reports…')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Refreshing…', exact: true })).toBeDisabled();
  release();
  await expect(page.getByText('Report unavailable for test')).toBeVisible();
  await expect(page.getByRole('button', { name: /^Export/ })).toBeDisabled();
  await page.unroute('**/api/v1/reports/period?*');
  await reportFixtures(page);
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Financial summary' })).toBeVisible();
  await expect(page).toHaveURL(new RegExp(url.replace('?', '\\?')));
});

test('long ranges remain usable in both themes at desktop and phone widths', async ({ page, signIn }) => {
  await reportFixtures(page);
  await signIn(OWNER);
  for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(t => localStorage.setItem('supreme.theme.admin', t), theme);
    await page.goto('/admin/reports?from=2025-10-01&to=2026-09-30');
    await expect(page.getByRole('region', { name: 'Financial summary' })).toBeVisible();
    expect(await page.locator('body').evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true);
    const refresh = page.getByRole('button', { name: 'Refresh', exact: true });
    const rect = await refresh.boundingBox();
    expect(rect!.width).toBeGreaterThanOrEqual(44);
    expect(rect!.height).toBeGreaterThanOrEqual(44);
    if (width === 1280) {
      const preset = await page.getByRole('button', { name: 'This week', exact: true }).boundingBox();
      expect(Math.abs(preset!.y - rect!.y)).toBeLessThan(2);
      expect(Math.abs(preset!.height - rect!.height)).toBeLessThan(2);
    }
    await page.getByRole('button', { name: /^Bills and averages/ }).click();
    await expect(page.getByText('Kept after cost of goods', { exact: true })).toBeVisible();
  }
});
