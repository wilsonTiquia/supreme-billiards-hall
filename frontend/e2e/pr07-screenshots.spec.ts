import { test, expect, OWNER } from './fixtures';
import { catalogFixtures, catalogProducts } from './pr06-fixtures';

test('capture single-product stock and edit in both themes and widths', async ({ page, signIn }) => {
  test.skip(!process.env.PR07_CAPTURE, 'Opt-in before/after evidence');
  test.setTimeout(120_000);
  await catalogFixtures(page);
  await page.route('**/api/v1/products?*', route => route.fulfill({ json: { success: true,
    data: catalogProducts.map(p => ({ ...p, defaultPurchaseCost: p.id === 'other-1' ? null : 62.5123 })),
  } }));
  await signIn(OWNER);
  for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(t => localStorage.setItem('supreme.theme.admin', t), theme);
    await page.goto('/admin/products');
    const row = page.getByRole('row').filter({ hasText: 'San Miguel Pale Pilsen' });
    await row.getByRole('button', { name: 'Add stock', exact: true }).click();
    await page.getByRole('dialog').getByLabel('Quantity', { exact: true }).fill('24');
    const prefix = `../docs/qa/pr07/${process.env.PR07_CAPTURE}-${theme}-${width}`;
    await page.screenshot({ path: `${prefix}-stock.png`, animations: 'disabled' });
    await page.getByRole('button', { name: 'Close', exact: true }).click();
    await row.getByRole('button', { name: 'Actions for San Miguel Pale Pilsen', exact: true }).click();
    await page.getByRole('menuitem', { name: 'Edit', exact: true }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.screenshot({ path: `${prefix}-edit.png`, animations: 'disabled' });
    await page.getByRole('button', { name: 'Close', exact: true }).click();
    if (process.env.PR07_CAPTURE === 'after') {
      const longRow = page.getByRole('row').filter({ hasText: 'Celebration bucket' });
      await longRow.getByRole('button', { name: 'Add stock', exact: true }).click();
      await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
      await expect(page.getByRole('dialog').getByRole('button', { name: 'Record delivery' })).toBeInViewport();
      await page.screenshot({ path: `${prefix}-long-name.png`, animations: 'disabled' });
      await page.keyboard.press('Escape');
      await expect(page.getByRole('dialog')).toHaveCount(0);
    }
  }
});
