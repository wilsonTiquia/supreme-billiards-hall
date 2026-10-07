import { test, expect, OWNER } from './fixtures';
import { catalogFixtures, catalogProducts } from './pr06-fixtures';

test('capture stock workspace in both themes and widths', async ({ page, signIn }) => {
  test.skip(!process.env.PR08_CAPTURE, 'Opt-in before/after evidence');
  test.setTimeout(120_000);
  await catalogFixtures(page);
  await page.route('**/api/v1/stock/low', route => route.fulfill({ json: { success: true, data: [
    { productId: 'beer-2', name: catalogProducts[1].name, qtyOnHand: -2.5, threshold: 10 },
    { productId: 'other-1', name: 'Bottled water', qtyOnHand: 0, threshold: 10 },
    { productId: 'food-1', name: 'Sisig', qtyOnHand: 3, threshold: 5 },
  ] } }));
  await signIn(OWNER);
  for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: width === 400 ? 800 : 900 });
    await page.evaluate(t => localStorage.setItem('supreme.theme.admin', t), theme);
    await page.goto('/admin/stock');
    await expect(page.getByRole('heading', { name: 'Receive a delivery' })).toBeVisible();
    await expect(page.getByText('Bottled water', { exact: true })).toBeVisible();
    const prefix = `../docs/qa/pr08/${process.env.PR08_CAPTURE}-${theme}-${width}`;
    await page.screenshot({ path: `${prefix}-delivery.png`, fullPage: true, animations: 'disabled' });
    if (process.env.PR08_CAPTURE === 'after') {
      for (const task of ['Count', 'Giveaway']) {
        await page.getByRole('tab', { name: task, exact: true }).click();
        await expect(page.getByRole('tabpanel')).toBeVisible();
        await page.screenshot({ path: `${prefix}-${task.toLowerCase()}.png`, fullPage: true, animations: 'disabled' });
      }
      await page.getByRole('tab', { name: 'Delivery', exact: true }).click();
      for (let i = 0; i < 9; i++) await page.getByRole('button', { name: 'Add a line' }).click();
      const lines = page.getByRole('region', { name: 'Delivery lines' });
      await lines.getByRole('combobox', { name: 'Line 10', exact: true }).scrollIntoViewIfNeeded();
      expect(await lines.evaluate(el => el.scrollHeight > el.clientHeight && el.scrollTop > 0)).toBe(true);
      await expect(page.getByRole('button', { name: 'Record delivery' })).toBeInViewport({ ratio: 1 });
      await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
      await page.screenshot({ path: `${prefix}-long-delivery.png`, fullPage: true, animations: 'disabled' });
    }
  }
});
