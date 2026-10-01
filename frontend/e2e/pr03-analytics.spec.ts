import { test, expect, OWNER } from './fixtures';
import { analyticsFixtures } from './pr03-fixtures';

const reportUrl = '/admin/reports?from=2026-09-01&to=2026-09-30';
const dashboardUrl = '/dashboard?date=2026-09-25';

test('night tooltips preserve exact values and caption, support keyboard, and restore break-even', async ({ page, signIn }) => {
  await analyticsFixtures(page);
  await signIn(OWNER);
  await page.goto(reportUrl);
  const chart = page.locator('.analytics-chart').first();
  const bars = chart.locator('[data-chart-bar]');
  const caption = await chart.locator('.analytics-chart-caption').innerText();
  await bars.first().hover();
  await expect(chart.getByRole('tooltip')).toContainText('₱1,234.56');
  await expect(chart.getByRole('tooltip')).toContainText('1 bill');
  await expect(chart.locator('.analytics-chart-caption')).toHaveText(caption);
  await expect(chart.locator('.analytics-break-even-label')).toBeHidden();
  await expect(chart.locator('line[stroke-dasharray]')).toHaveCSS('visibility', 'visible');
  await page.keyboard.press('Escape');
  await expect(chart.getByRole('tooltip')).toHaveCount(0);
  await expect(chart.locator('.analytics-break-even-label')).toBeVisible();
  await bars.first().focus();
  await expect(chart.getByRole('tooltip')).toContainText('₱1,234.56');
  await page.keyboard.press('ArrowRight');
  await expect(bars.nth(1)).toBeFocused();
  await expect(chart.getByRole('tooltip')).toContainText('2026-09-02');
  await page.keyboard.press('End');
  await expect(bars.last()).toBeFocused();
  await expect(chart.getByRole('tooltip')).toContainText('2026-09-30');
  await page.keyboard.press('Tab');
  await expect(chart.getByRole('tooltip')).toHaveCount(0);
  await expect(chart.locator('.analytics-chart-caption')).toHaveText(caption);
  await chart.getByLabel('Select night').selectOption('5');
  await expect(chart.getByRole('tooltip')).toContainText('₱18,000.75');
  await expect(chart.locator('.analytics-break-even-label')).toBeVisible();
  await page.getByLabel('From', { exact: true }).fill('2026-09-24');
  await expect(bars).toHaveCount(7);
  await expect(chart.locator('[data-chart-bar][tabindex="0"]')).toHaveCount(1);
  await bars.last().focus();
  await expect(chart.getByRole('tooltip')).toContainText('2026-09-30');
});

test('hour tooltip, donut reconciliation, table metrics and top-five products', async ({ page, signIn }) => {
  await analyticsFixtures(page);
  await signIn(OWNER);
  await page.goto(dashboardUrl);
  const chart = page.locator('.analytics-hour-chart');
  const caption = await chart.locator('.analytics-chart-caption').innerText();
  await chart.locator('[data-chart-bar]').first().focus();
  await expect(chart.getByRole('tooltip')).toContainText('10am');
  await expect(chart.getByRole('tooltip')).toContainText('₱1,234.56');
  await page.keyboard.press('ArrowRight');
  await expect(chart.getByRole('tooltip')).toContainText('₱0.00');
  await expect(chart.getByRole('tooltip')).toContainText('0 bills');
  await expect(chart.locator('.analytics-chart-caption')).toHaveText(caption);
  const mix = page.locator('.analytics-sales-mix');
  await expect(mix.locator('circle[stroke-dasharray]')).toHaveCount(2);
  await expect(mix).toContainText('−₱1,264');
  await expect(mix.locator('.analytics-ledger-total')).toContainText('₱19,736');
  await expect(page.locator('.analytics-table-tiles')).toContainText('52.6% in use');
  await expect(page.locator('.analytics-products > li')).toHaveCount(5);
  await expect(page.locator('.analytics-products > li').first()).toContainText('40 units sold');
  await expect(page.locator('.analytics-products')).not.toContainText('Product 6');
  await page.goto(reportUrl);
  await expect(page.locator('.analytics-table-tiles')).toContainText('100.0 h held · ₱200 per hour held');
  await expect(page.locator('.analytics-products > li')).toHaveCount(5);
});

test.describe('touch and dense charts', () => {
  test.use({ hasTouch: true, viewport: { width: 400, height: 850 } });
  test('touch selection stays inside the viewport and print exposes all dates and hours', async ({ page, signIn }) => {
    await analyticsFixtures(page, 'dense');
    await signIn(OWNER);
    await page.goto(reportUrl);
    const chart = page.locator('.analytics-chart').first();
    await chart.getByLabel('Select night').selectOption('365');
    await expect(chart.getByRole('tooltip')).toContainText('2026-10-01');
    const box = await chart.getByRole('tooltip').boundingBox();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(400);
    await chart.getByLabel('Select night').selectOption('0');
    await expect(chart.getByRole('tooltip')).toContainText('₱1,234.56');
    const chartBox = await chart.boundingBox();
    expect(chartBox!.x + chartBox!.width).toBeLessThanOrEqual(400);
    await page.goto(dashboardUrl);
    const hour = page.locator('.analytics-hour-chart');
    await hour.locator('[data-chart-bar]').first().tap();
    await expect(hour.getByRole('tooltip')).toContainText('₱1,234.56');
    await hour.getByLabel('Select hour').selectOption('18');
    await expect(hour.getByRole('tooltip')).toContainText('4am');
    await expect(hour.getByRole('tooltip')).toContainText('₱500.25');
    await page.goto(reportUrl);
    await chart.getByLabel('Select night').selectOption('0');
    await page.emulateMedia({ media: 'print' });
    await expect(chart.getByRole('tooltip')).toBeHidden();
    await expect(chart.locator('.analytics-break-even-label')).toBeVisible();
    await expect(chart.locator('.analytics-chart-figures')).toBeVisible();
    await expect(chart.locator('.analytics-chart-figures tbody tr')).toHaveCount(366);
    await expect(page.locator('.analytics-hour-chart .analytics-chart-figures')).toBeVisible();
    await expect(page.locator('.analytics-hour-chart .analytics-chart-figures tbody tr')).toHaveCount(19);
  });
});

for (const variant of ['zero', 'single'] as const) {
  test(`${variant} sales render without invalid chart geometry`, async ({ page, signIn }) => {
    await analyticsFixtures(page, variant);
    await signIn(OWNER);
    await page.goto(dashboardUrl);
    await expect(page.locator('.analytics-donut')).toBeVisible();
    await expect(page.locator('.analytics-donut circle[stroke-dasharray]')).toHaveCount(variant === 'zero' ? 0 : 1);
    if (variant === 'zero') {
      await expect(page.getByText('Nothing sold yet.')).toBeVisible();
      await expect(page.getByText('No sales in this period.')).toBeVisible();
      await expect(page.locator('.analytics-products')).toHaveCount(0);
    }
    await page.goto(reportUrl);
    if (variant === 'zero') await expect(page.getByText('No sales in this period.')).toBeVisible();
    else {
      await page.getByLabel('Select night').selectOption('1');
      await expect(page.getByRole('tooltip')).toContainText('₱0.00');
    }
    expect(await page.locator('main').innerHTML()).not.toMatch(/NaN|Infinity/);
  });
}

test('loading and failed reports do not display stale analytics', async ({ page, signIn }) => {
  await signIn(OWNER);
  let release!: () => void;
  const wait = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/v1/reports/period?*', async route => {
    await wait;
    await route.fulfill({ status: 500, json: { success: false, message: 'Report unavailable' } });
  });
  await page.goto(reportUrl);
  await expect(page.getByRole('status', { name: 'Loading reports…' })).toBeVisible();
  release();
  await expect(page.getByText('Report unavailable', { exact: false }).first()).toBeVisible();
  await expect(page.locator('.analytics-table-tiles')).toHaveCount(0);
});
