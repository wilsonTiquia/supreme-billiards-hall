import { test, expect, OWNER } from './fixtures';

// Read-only API responses keep before/after screenshots comparable; no trading data.
export const categoryNames = ['Deliveries', 'Utilities', 'Cleaning supplies', 'Repairs and maintenance'];

test('capture Expenses and Settings in both themes and widths', async ({ page, signIn }) => {
  test.skip(!process.env.PR15_CAPTURE, 'Opt-in before/after evidence');
  await page.route('**/api/v1/expense-categories', r => r.fulfill({ json: { success: true, data: categoryNames.map((name, i) => ({ id: `category-${i}`, name })) } }));
  await page.route('**/api/v1/expenses', r => r.fulfill({ json: { success: true, data: [
    { id: 'expense-1', categoryName: 'Deliveries', amount: 850, paidFromDrawer: true, note: 'Drinking water delivery', incurredAt: '2026-10-09T12:30:00Z', recordedByUsername: 'Front Counter', voided: false },
    { id: 'expense-2', categoryName: 'Utilities', amount: 2400, paidFromDrawer: false, note: 'Electricity paid by owner', incurredAt: '2026-10-09T12:00:00Z', recordedByUsername: 'Owner', voided: false },
    { id: 'expense-3', categoryName: 'Cleaning supplies', amount: 120, paidFromDrawer: true, note: 'Duplicate entry', incurredAt: '2026-10-09T11:00:00Z', recordedByUsername: 'Front Counter', voided: true, voidReason: 'Already recorded yesterday' },
  ] } }));
  await page.route('**/api/v1/settings', r => r.fulfill({ json: { success: true, data: { standardCashFloat: 1000, checkoutAnimation: true } } }));
  await signIn(OWNER);
  for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(t => { localStorage.setItem('supreme.theme.pos', t); localStorage.setItem('supreme.theme.admin', t); }, theme);
    for (const [route, screen] of [['/expenses', 'expenses'], ['/admin/settings', 'settings']]) {
      await page.goto(route);
      if (screen === 'expenses') {
        await expect(page.getByText('Drinking water delivery', { exact: false })).toBeVisible();
        await page.getByLabel('Amount', { exact: true }).fill('250');
        if (process.env.PR15_CAPTURE === 'before') await page.getByLabel('What for').selectOption('category-2');
        else await page.getByRole('radio', { name: 'Cleaning supplies', exact: true }).check();
      } else await expect(page.getByLabel('Float', { exact: true })).toHaveValue('1,000.00');
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
      await page.screenshot({ path: `../docs/qa/pr15/${process.env.PR15_CAPTURE}-${theme}-${width}-${screen}.png`, fullPage: true, animations: 'disabled' });
    }
  }
});
