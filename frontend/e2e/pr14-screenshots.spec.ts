import { test, expect, OWNER, apiAs, apiGet, apiPost, backdateSession, BEER } from './fixtures';
import type { CustomerType, FloorView, Product, Session } from '../src/api/types';

// Read-only catalogue fixtures make image, long-label and dense-grid evidence reproducible.
// The session and its bill are real records on the guarded scratch backend.
test('capture session and quick sale in both themes and widths', async ({ page, signIn, hall }) => {
  test.skip(!process.env.PR14_CAPTURE, 'Opt-in before/after evidence');
  test.setTimeout(120_000);
  const api = await apiAs(OWNER);
  const floor = await apiGet<FloorView>(api, '/api/v1/tables');
  const types = await apiGet<CustomerType[]>(api, '/api/v1/customer-types');
  const session = await apiPost<Session>(api, '/api/v1/sessions', {
    tableId: floor.tables.find(t => t.tableNumber === 7)!.id,
    customerTypeId: types.find(t => t.isDefault)!.id,
  });
  backdateSession(session.id, 12);
  await apiPost(api, `/api/v1/bills/${session.billId}/lines`, { productId: hall.beerId, quantity: 1 });
  const products = await apiGet<Product[]>(api, '/api/v1/products?activeOnly=true');
  const beer = products.find(p => p.id === hall.beerId)!;
  const food = products.find(p => p.id === hall.sisigId)!;
  const names = ['Bottled water', 'Bottled water 1.5L', 'Coke', 'San Miguel Pale Pilsen', 'Fresh calamansi juice with honey and extra ice', 'Sisig'];
  const catalog = Array.from({ length: 30 }, (_, i) => ({
    ...(i % 2 ? food : beer), id: `visual-${i}`, name: names[i % names.length] + (i >= 6 ? ` ${i + 1}` : ''),
    imageSha256: i % 3 === 0 ? 'visual' : null, sellingPrice: [25, 60, 40, 90, 75, 180][i % 6], qtyOnHand: i === 4 ? 0 : 24,
  }));
  await page.route('**/api/v1/products?activeOnly=true', r => r.fulfill({ json: { success: true, data: catalog } }));
  await page.route('**/api/v1/products/visual-*/image?*', r => r.fulfill({ contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="240" height="180" viewBox="0 0 240 180"><rect width="240" height="180" fill="#d4e6dd"/><rect x="102" y="26" width="36" height="18" rx="4" fill="#1e6044"/><rect x="90" y="43" width="60" height="116" rx="18" fill="#fafafa"/><rect x="90" y="83" width="60" height="43" fill="#2a7757"/><text x="120" y="109" fill="white" font-family="sans-serif" font-size="13" text-anchor="middle">WATER</text></svg>' }));
  await signIn(OWNER);
  for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(t => localStorage.setItem('supreme.theme.pos', t), theme);
    const prefix = `../docs/qa/pr14/${process.env.PR14_CAPTURE}-${theme}-${width}`;
    await page.goto(`/sessions/${session.id}`);
    await expect(page.getByRole('heading', { name: 'Bill', exact: true })).toBeVisible();
    await expect(page.getByText(BEER.name, { exact: true }).last()).toBeVisible();
    await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
    await page.screenshot({ path: `${prefix}-session.png`, fullPage: true, animations: 'disabled' });
    if (width === 400) {
      await page.getByRole('button', { name: 'Add items', exact: true }).click();
      await expect(page.getByLabel('Search products')).toBeFocused();
      await page.screenshot({ path: `${prefix}-session-products.png`, animations: 'disabled' });
      await page.keyboard.press('Escape');
    }
    await page.goto('/quick-sale');
    await expect(page.getByRole('button', { name: /Bottled water 1.5L/ }).first()).toBeVisible();
    await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
    await page.screenshot({ path: `${prefix}-quick-sale.png`, animations: 'disabled' });
    if (process.env.PR14_CAPTURE === 'after') {
      await page.getByRole('button', { name: /Sisig 30/ }).scrollIntoViewIfNeeded();
      await expect(page.getByRole('button', { name: /Sisig 30/ })).toBeInViewport();
      await page.getByLabel('Search products').fill('Fresh calamansi');
      await expect(page.getByRole('button', { name: /Fresh calamansi/ })).toHaveCount(5);
      await page.getByRole('button', { name: /Fresh calamansi/ }).first().scrollIntoViewIfNeeded();
      await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
      await page.screenshot({ path: `${prefix}-long-label.png`, animations: 'disabled' });
    }
  }
  await apiPost(api, `/api/v1/sessions/${session.id}/close`, {});
  await api.dispose();
});
