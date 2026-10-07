import { test, expect, OWNER, apiGet, apiPost } from './fixtures';
import type { ProductAdmin, StockMovement } from '../src/api/types';
import type { Page } from '@playwright/test';

async function product(page: Page, name: string, cost?: number) {
  return apiPost<ProductAdmin>(page.request, '/api/v1/products', {
    name, sellingPrice: 90, defaultPurchaseCost: cost,
    confirmZeroDefaultCost: cost === 0, openingStock: { quantity: 10, unitCost: 10 },
  });
}
async function fresh(page: Page, id: string) {
  return (await apiGet<ProductAdmin[]>(page.request, '/api/v1/products?activeOnly=false')).find(p => p.id === id)!;
}
async function openStock(page: Page, name: string) {
  await page.goto('/admin/products');
  await page.getByRole('row').filter({ hasText: name }).getByRole('button', { name: 'Add stock', exact: true }).click();
  return page.getByRole('dialog');
}

test('unset stays blank, zero needs confirmation on every save, and clearing zero blocks stock', async ({ page, signIn }) => {
  await signIn(OWNER);
  await page.setViewportSize({ width: 1280, height: 1100 });
  const p = await product(page, 'PR07 unknown');
  let dialog = await openStock(page, p.name);
  await expect(dialog.getByText('Not set', { exact: true })).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Record delivery' })).toBeDisabled();
  await dialog.getByRole('button', { name: 'Edit product', exact: true }).click();
  await expect(dialog.getByLabel('Default purchase cost', { exact: true })).toHaveValue('');
  await dialog.getByLabel('Default purchase cost', { exact: true }).fill('0');
  await expect(dialog.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
  const free = dialog.getByRole('checkbox', { name: /genuinely free/ });
  await free.check();
  await dialog.getByLabel('Default purchase cost', { exact: true }).fill('1');
  await dialog.getByLabel('Default purchase cost', { exact: true }).fill('0');
  await expect(free).not.toBeChecked();
  await free.check();
  await page.screenshot({ path: '../docs/qa/pr07/after-zero-confirmation.png' });
  await dialog.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  expect((await fresh(page, p.id)).defaultPurchaseCost).toBe(0);
  dialog = await openStock(page, p.name);
  await expect(dialog).toContainText('Free stock');
  await dialog.getByLabel('Quantity', { exact: true }).fill('2.5');
  await dialog.getByRole('button', { name: 'Record delivery' }).click();
  await expect(dialog).toHaveCount(0);
  expect(await fresh(page, p.id)).toMatchObject({ defaultPurchaseCost: 0, qtyOnHand: 12.5, avgCost: 8 });
  dialog = await openStock(page, p.name);
  await dialog.getByRole('button', { name: 'Edit product', exact: true }).click();
  await expect(dialog.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
  await dialog.getByLabel('Default purchase cost', { exact: true }).fill('');
  await dialog.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  expect((await fresh(page, p.id)).defaultPurchaseCost).toBeNull();
  dialog = await openStock(page, p.name);
  await expect(dialog.getByRole('button', { name: 'Record delivery' })).toBeDisabled();
  await page.screenshot({ path: '../docs/qa/pr07/after-unset.png' });
});

test('fractional stepper, invalid input, pending duplicate submits and dismissal record one delivery', async ({ page, signIn }) => {
  await signIn(OWNER);
  const p = await product(page, 'PR07 fractional', 20);
  const dialog = await openStock(page, p.name);
  await expect(dialog.getByRole('combobox')).toHaveCount(0);
  await expect(dialog.getByRole('button', { name: 'Add a line' })).toHaveCount(0);
  const quantity = dialog.getByLabel('Quantity', { exact: true });
  await expect(quantity).toBeFocused();
  for (const invalid of ['', '0', '-1', '0.0001', '1.0001', '1000000000']) {
    await quantity.fill(invalid);
    await expect(dialog.getByRole('button', { name: 'Record delivery' })).toBeDisabled();
  }
  await quantity.fill('1.125');
  await dialog.getByRole('button', { name: 'Increase quantity' }).click();
  await expect(quantity).toHaveValue('2.125');
  await dialog.getByRole('button', { name: 'Decrease quantity' }).click();
  await expect(quantity).toHaveValue('1.125');
  let submits = 0;
  let release!: () => void;
  const held = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/v1/stock/product-deliveries', async route => {
    submits++;
    expect(route.request().postDataJSON()).toEqual({ productId: p.id, quantity: 1.125, expectedDefaultPurchaseCost: 20 });
    await held;
    await route.continue();
  });
  await dialog.locator('form').evaluate(form => {
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  });
  await expect(dialog.getByRole('button', { name: 'Record delivery' })).toBeDisabled();
  await expect(quantity).toBeDisabled();
  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  await expect(page.getByRole('row').filter({ hasText: p.name }).getByRole('button', { name: 'Add stock' })).toBeDisabled();
  release();
  await expect(page.getByRole('status').filter({ hasText: 'Delivery recorded' })).toBeVisible();
  expect(submits).toBe(1);
  expect(await fresh(page, p.id)).toMatchObject({ qtyOnHand: 11.125, avgCost: 11.0112 });
  const movements = await apiGet<StockMovement[]>(page.request, `/api/v1/products/${p.id}/movements`);
  expect(movements).toHaveLength(2);
  expect(movements.some(m => m.quantityDelta === 1.125 && m.unitCost === 20)).toBe(true);
});

test('changed cost and server errors retain quantity and never silently record a stale price', async ({ page, signIn }) => {
  await signIn(OWNER);
  const p = await product(page, 'PR07 changing cost', 20);
  let dialog = await openStock(page, p.name);
  const response = await page.request.put(`/api/v1/products/${p.id}`, { data: { name: p.name, sellingPrice: 90, defaultPurchaseCost: 30 } });
  expect(response.ok()).toBe(true);
  await dialog.getByLabel('Quantity', { exact: true }).fill('10');
  await dialog.getByRole('button', { name: 'Record delivery' }).click();
  await expect(dialog.getByRole('alert')).toContainText('Default purchase cost changed');
  await expect(dialog.getByLabel('Quantity', { exact: true })).toHaveValue('10');
  expect((await fresh(page, p.id)).qtyOnHand).toBe(10);
  await page.screenshot({ path: '../docs/qa/pr07/after-stale-cost.png' });
  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  dialog = await openStock(page, p.name);
  await expect(dialog).toContainText('₱30.00');
  await dialog.getByLabel('Quantity', { exact: true }).fill('10');
  await page.route('**/api/v1/stock/product-deliveries', route => route.fulfill({ status: 503, json: { success: false, message: 'Delivery service unavailable.' } }));
  await dialog.getByRole('button', { name: 'Record delivery' }).click();
  await expect(dialog.getByRole('alert')).toContainText('Delivery service unavailable');
  await expect(dialog.getByLabel('Quantity', { exact: true })).toHaveValue('10');
  await page.unroute('**/api/v1/stock/product-deliveries');
  await dialog.getByRole('button', { name: 'Record delivery' }).click();
  await expect(dialog).toHaveCount(0);
  expect(await fresh(page, p.id)).toMatchObject({ qtyOnHand: 20, avgCost: 20 });
});


test('a dismissed pending delivery reports its failure on the catalog', async ({ page, signIn }) => {
  await signIn(OWNER);
  const p = await product(page, 'PR07 dismissed failure', 20);
  const dialog = await openStock(page, p.name);
  let release!: () => void;
  const held = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/v1/stock/product-deliveries', async route => {
    await held;
    await route.fulfill({ status: 503, json: { success: false, message: 'Delivery service unavailable.' } });
  });
  await dialog.getByRole('button', { name: 'Record delivery' }).click();
  await expect(dialog.getByRole('button', { name: 'Record delivery' })).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(page.getByText('Recording delivery…', { exact: true })).toBeVisible();
  release();
  await expect(page.getByRole('alert')).toContainText('Delivery service unavailable.');
  expect((await fresh(page, p.id)).qtyOnHand).toBe(10);
});
