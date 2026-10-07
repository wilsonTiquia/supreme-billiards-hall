import { test, expect, OWNER, apiGet, apiPost } from './fixtures';
import type { ProductAdmin, StockMovement } from '../src/api/types';
import type { Locator } from '@playwright/test';

async function choose(panel: Locator, name: string, label = 'Product') {
  await panel.getByRole('combobox', { name: label, exact: true }).fill(name);
  await panel.getByRole('option').filter({ hasText: name }).click();
}

test('tabs preserve drafts, record all three transactions and refresh quantities and low stock', async ({ page, signIn }) => {
  await signIn(OWNER);
  const a = await apiPost<ProductAdmin>(page.request, '/api/v1/products', { name: 'PR08 water', sellingPrice: 25 });
  const b = await apiPost<ProductAdmin>(page.request, '/api/v1/products', { name: 'PR08 snack', sellingPrice: 40 });
  await page.goto('/admin/stock');
  const panel = page.getByRole('tabpanel');
  const low = page.getByRole('complementary', { name: 'Low or negative stock' });
  await expect(low).toContainText(a.name);
  await choose(panel, a.name, 'Line 1');
  await panel.getByLabel('Quantity', { exact: true }).fill('12.125');
  await panel.getByLabel('Unit cost').fill('10.1234');
  await panel.getByRole('button', { name: 'Add a line' }).click();
  await choose(panel, b.name, 'Line 2');
  await panel.getByLabel('Quantity', { exact: true }).nth(1).fill('14');
  await panel.getByLabel('Unit cost').nth(1).fill('20');

  const delivery = page.getByRole('tab', { name: 'Delivery', exact: true });
  await delivery.focus();
  await page.keyboard.press('ArrowRight');
  await expect(page.getByRole('tab', { name: 'Count', exact: true })).toBeFocused();
  await expect(panel).toContainText('Correct a count');
  await choose(panel, a.name);
  await panel.getByLabel('Actual quantity').fill('2.125');
  await panel.getByLabel('Reason').fill('Shelf count');
  await page.getByRole('tab', { name: 'Count', exact: true }).focus();
  await page.keyboard.press('End');
  await expect(page.getByRole('tab', { name: 'Giveaway', exact: true })).toBeFocused();
  await choose(panel, a.name);
  await panel.getByLabel('Quantity', { exact: true }).fill('3');
  await panel.getByLabel('Reason').fill('Staff drink');
  await page.getByRole('tab', { name: 'Giveaway', exact: true }).focus();
  await page.keyboard.press('ArrowRight');
  await expect(delivery).toBeFocused();
  await expect(panel.getByLabel('Quantity', { exact: true }).first()).toHaveValue('12.125');
  await expect(panel.getByLabel('Unit cost').first()).toHaveValue('10.1234');
  expect(await apiGet<StockMovement[]>(page.request, `/api/v1/products/${a.id}/movements`)).toHaveLength(0);
  await panel.getByRole('button', { name: 'Record delivery' }).click();
  await expect(panel.getByRole('status')).toContainText('Delivery recorded');
  await expect(low).not.toContainText(a.name);
  await expect(low).not.toContainText(b.name);

  await page.getByRole('tab', { name: 'Count', exact: true }).click();
  await expect(panel.getByLabel('Actual quantity')).toHaveValue('2.125');
  await expect(panel.getByLabel('Reason')).toHaveValue('Shelf count');
  await expect(panel).toContainText('System currently shows 12.125');
  await expect(panel.getByRole('status')).toHaveCount(0);
  await panel.getByRole('button', { name: 'Record correction' }).click();
  await expect(panel.getByRole('status')).toContainText('Correction recorded');
  await expect(low.getByRole('listitem').filter({ hasText: a.name })).toContainText('2.125');

  await page.getByRole('tab', { name: 'Giveaway', exact: true }).click();
  await expect(panel.getByLabel('Quantity', { exact: true })).toHaveValue('3');
  await expect(panel.getByLabel('Reason')).toHaveValue('Staff drink');
  await panel.getByRole('button', { name: 'Record give-away' }).click();
  await expect(panel.getByRole('status')).toContainText('Give-away recorded');
  await expect(low.getByRole('listitem').filter({ hasText: a.name })).toContainText('-0.875');
  const movements = await apiGet<StockMovement[]>(page.request, `/api/v1/products/${a.id}/movements`);
  expect(movements).toHaveLength(3);
  expect(movements).toEqual(expect.arrayContaining([
    expect.objectContaining({ reason: 'DELIVERY', quantityDelta: 12.125, unitCost: 10.1234 }),
    expect.objectContaining({ reason: 'CORRECTION', quantityDelta: -10, qtyAfter: 2.125 }),
    expect.objectContaining({ reason: 'STAFF_COMP', quantityDelta: -3, qtyAfter: -0.875 }),
  ]));
  const other = await apiGet<StockMovement[]>(page.request, `/api/v1/products/${b.id}/movements`);
  expect(other).toEqual([expect.objectContaining({ reason: 'DELIVERY', quantityDelta: 14, unitCost: 20 })]);
  await page.getByRole('tab', { name: 'Count', exact: true }).click();
  await panel.getByLabel('Actual quantity').fill('-0.875');
  await panel.getByLabel('Reason').fill('Same count');
  await panel.getByRole('button', { name: 'Record correction' }).click();
  await expect(panel.getByRole('alert')).toBeVisible();
  await expect(panel.getByRole('status')).toHaveCount(0);
  expect(await apiGet<StockMovement[]>(page.request, `/api/v1/products/${a.id}/movements`)).toHaveLength(3);
});

for (const task of [
  { name: 'Delivery', endpoint: 'deliveries', submit: 'Record delivery' },
  { name: 'Count', endpoint: 'corrections', submit: 'Record correction' },
  { name: 'Giveaway', endpoint: 'comps', submit: 'Record give-away' },
]) test(`${task.name} keeps pending and error feedback with its draft when switching tabs`, async ({ page, signIn }) => {
  await signIn(OWNER);
  const p = await apiPost<ProductAdmin>(page.request, '/api/v1/products', { name: `PR08 pending ${task.name}`, sellingPrice: 25 });
  await page.goto('/admin/stock');
  await page.getByRole('tab', { name: task.name, exact: true }).click();
  const panel = page.getByRole('tabpanel');
  await choose(panel, p.name, task.name === 'Delivery' ? 'Line 1' : 'Product');
  await panel.getByLabel(task.name === 'Count' ? 'Actual quantity' : 'Quantity', { exact: true }).fill('2.125');
  if (task.name === 'Delivery') await panel.getByLabel('Unit cost').fill('12.5');
  else await panel.getByLabel('Reason').fill('Keep this draft');
  let release!: () => void;
  const held = new Promise<void>(resolve => { release = resolve; });
  let requests = 0;
  await page.route(`**/api/v1/stock/${task.endpoint}`, async route => {
    requests++;
    await held;
    await route.fulfill({ status: 503, json: { success: false, message: `${task.name} service unavailable.` } });
  });
  await panel.getByRole('button', { name: task.submit }).click();
  await expect(panel.getByRole('button', { name: task.submit })).toBeDisabled();
  await page.getByRole('tab', { name: task.name === 'Delivery' ? 'Count' : 'Delivery', exact: true }).click();
  release();
  await expect.poll(() => requests).toBe(1);
  await expect(panel.getByRole('alert')).toHaveCount(0);
  await page.getByRole('tab', { name: task.name, exact: true }).click();
  await expect(panel.getByRole('alert')).toContainText(`${task.name} service unavailable`);
  await expect(panel.getByLabel(task.name === 'Count' ? 'Actual quantity' : 'Quantity', { exact: true })).toHaveValue('2.125');
  await page.unroute(`**/api/v1/stock/${task.endpoint}`);
  await panel.getByRole('button', { name: task.submit }).click();
  await expect(panel.getByRole('status')).toContainText('recorded');
  await expect(panel.getByRole('alert')).toHaveCount(0);
  expect(await apiGet<StockMovement[]>(page.request, `/api/v1/products/${p.id}/movements`)).toHaveLength(1);
});

test('loading, empty, failed and dense low-stock states stay separate from the task', async ({ page, signIn }) => {
  await signIn(OWNER);
  let release!: () => void;
  const held = new Promise<void>(resolve => { release = resolve; });
  let state: 'held' | 'empty' | 'dense' = 'held';
  await page.route('**/api/v1/stock/low', async route => {
    if (state === 'held') {
      await held;
      await route.fulfill({ status: 503, json: { success: false, message: 'Stock overview unavailable.' } });
    } else await route.fulfill({ json: { success: true, data: state === 'empty' ? [] : Array.from({ length: 60 }, (_, i) => ({ productId: `${i}`, name: `Long product name with several words ${i}`, qtyOnHand: -i, threshold: 10 })) } });
  });
  await page.goto('/admin/stock');
  const low = page.getByRole('complementary', { name: 'Low or negative stock' });
  await expect(low.getByText('Loading stock…')).toBeVisible();
  await expect(page.getByRole('tabpanel')).toBeVisible();
  release();
  await expect(low.getByRole('alert')).toContainText('Stock overview unavailable');
  await expect(low).not.toContainText('Nothing is running low');
  state = 'empty';
  await low.getByRole('button', { name: 'Retry' }).click();
  await expect(low).toContainText('Nothing is running low');
  state = 'dense';
  await page.reload();
  await expect(low.getByRole('listitem')).toHaveCount(60);
  for (const width of [1280, 400]) {
    await page.setViewportSize({ width, height: 800 });
    await low.getByText('Long product name with several words 59', { exact: true }).scrollIntoViewIfNeeded();
    await expect(low.getByText('Long product name with several words 59', { exact: true })).toBeInViewport();
    await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
  }
});
