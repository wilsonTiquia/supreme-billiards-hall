import { mkdir } from 'node:fs/promises';
import { test, expect, OWNER, BEER } from './fixtures';

const screenshots = '../docs/qa/stock';

for (const width of [400, 1280]) {
  for (const theme of ['light', 'dark']) {
    test(`product and stock workflow · ${width}px · ${theme}`, async ({ page, signIn, hall }) => {
      void hall;
      await mkdir(screenshots, { recursive: true });
      await page.setViewportSize({ width, height: width === 400 ? 800 : 720 });
      await page.addInitScript((theme) => localStorage.setItem('supreme.theme.admin', theme), theme);
      await signIn(OWNER);
      await page.goto('/admin/products');
      await expect(page.getByRole('cell', { name: BEER.name, exact: true })).toBeVisible();
      const shot = async (name: string) => {
        await page.screenshot({ path: `${screenshots}/${name}-${theme}-${width}.png`, animations: 'disabled', fullPage: !['opening-stock', 'add-stock', 'edit-product', 'stock-toast'].includes(name) });
      };
      await shot('products');
      await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
      await page.getByRole('button', { name: 'New product', exact: true }).click();
      const dialog = page.getByRole('dialog');
      const productName = `Opening stock ${width} ${theme}`;
      await dialog.getByLabel('Name', { exact: true }).fill(productName);
      await dialog.getByLabel('Selling price').fill('90');
      await dialog.getByLabel('Quantity', { exact: true }).fill('12');
      await dialog.getByRole('button', { name: 'Save', exact: true }).click();
      await expect(dialog).toBeVisible();
      expect(await dialog.getByLabel('Unit cost').evaluate((el) => el.matches(':invalid'))).toBe(true);
      await dialog.getByLabel('Unit cost').fill('62.5');
      await dialog.getByRole('heading').scrollIntoViewIfNeeded();
      await expect(dialog).toBeInViewport({ ratio: 1 });
      await shot('opening-stock');
      await dialog.getByRole('button', { name: 'Save', exact: true }).click();
      await expect(dialog).not.toBeVisible();
      const row = page.getByRole('row').filter({ hasText: productName });
      await expect(row).toContainText('₱62.50');
      await row.getByRole('button', { name: 'Add stock', exact: true }).click();
      await expect(dialog.getByRole('combobox', { name: 'Line 1' })).toHaveValue(productName);
      await dialog.getByLabel('Quantity', { exact: true }).fill('12');
      await dialog.getByLabel('Unit cost').fill('67.5');
      await expect(dialog.getByRole('button', { name: 'Record delivery' })).toBeEnabled();
      await shot('add-stock');
      await dialog.getByRole('button', { name: 'Record delivery' }).click();
      await expect(dialog).not.toBeVisible();
      await expect(row).toContainText('₱65.00');
      const products = await (await page.request.get('/api/v1/products?activeOnly=false')).json();
      const product = products.data.find((p: { name: string }) => p.name === productName);
      expect(product.qtyOnHand).toBe(24);
      expect(product.avgCost).toBe(65);
      const movements = await (await page.request.get(`/api/v1/products/${product.id}/movements`)).json();
      expect(movements.data).toHaveLength(2);
      expect(movements.data.every((m: { reason: string }) => m.reason === 'DELIVERY')).toBe(true);
      await row.getByRole('button', { name: 'Edit', exact: true }).click();
      await expect(dialog.getByText('Opening stock', { exact: true })).toHaveCount(0);
      await shot('edit-product');
      await dialog.getByRole('button', { name: 'Close', exact: true }).click();

      await page.goto('/admin/stock');
      await expect(page.getByRole('heading', { name: 'Receive a delivery' })).toBeVisible();
      await shot('stock');
      for (let i = 0; i < 7; i++) await page.getByRole('button', { name: 'Add a line' }).click();
      const lines = page.getByRole('region', { name: 'Delivery lines' });
      expect(await lines.evaluate((el) => el.scrollHeight > el.clientHeight)).toBe(true);
      await lines.getByRole('combobox', { name: 'Line 8', exact: true }).scrollIntoViewIfNeeded();
      expect(await lines.evaluate((el) => el.scrollTop)).toBeGreaterThan(0);
      await shot('stock-long-delivery');
      await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
      await page.getByRole('heading', { name: 'Low or negative stock' }).scrollIntoViewIfNeeded();
      expect((await page.locator('body').boundingBox())?.y).toBeLessThan(0);

      const giveAway = page.locator('div.rounded-xl').filter({ has: page.getByRole('heading', { name: 'Give away', exact: true }) });
      await giveAway.getByRole('combobox').fill(productName);
      await page.getByRole('option').filter({ hasText: productName }).click();
      await giveAway.getByLabel('Quantity', { exact: true }).fill('1');
      await giveAway.getByLabel('Reason').fill('QA staff drink');
      await giveAway.getByRole('button', { name: 'Record give-away' }).click();
      await expect(page.getByRole('status')).toContainText('Give-away recorded.');
      await shot('stock-toast');
      await page.getByRole('button', { name: 'Dismiss notification' }).click();
      await expect(page.getByRole('status')).toBeEmpty();
    });
  }
}
