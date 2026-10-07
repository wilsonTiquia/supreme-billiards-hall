import { test, expect, OWNER, COUNTER, apiGet, apiPost } from './fixtures';
import { receiptFixtures, snapshot } from './pr09-fixtures';
import type { Payment, Receipt, ProductAdmin } from '../src/api/types';

const listUrl = '/admin/sales?date=2026-09-29&page=1';

test('several receipts preserve the exact list, focus and scroll through Close, Escape and browser history', async ({ page, signIn }) => {
  await receiptFixtures(page);
  await signIn(OWNER);
  await page.goto(listUrl);
  const link = page.getByRole('link', { name: 'Receipt', exact: true }).nth(12);
  await link.scrollIntoViewIfNeeded();
  const scroll = await page.evaluate<number>('window.scrollY');
  await link.click();
  const dialog = page.getByRole('dialog', { name: 'Sales receipt' });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Close', exact: true })).toBeFocused();
  await expect(dialog.getByRole('button', { name: 'Print', exact: true })).toBeEnabled();
  await expect(dialog).toContainText('Receipt #39');
  await page.keyboard.press('Shift+Tab');
  await expect(dialog.getByRole('button', { name: 'Print', exact: true })).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(dialog.getByRole('button', { name: 'Close', exact: true })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(page).toHaveURL(listUrl);
  await expect(link).toBeFocused();
  expect(await page.evaluate<number>('window.scrollY')).toBe(scroll);
  await expect(page.getByLabel('Business day', { exact: true })).toHaveValue('2026-09-29');
  await expect(page.getByText('Page 2 of 3')).toBeVisible();
  await link.click();
  await page.goBack();
  await expect(dialog).toHaveCount(0);
  await expect(link).toBeFocused();
  await page.goForward();
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  await expect(page).toHaveURL(listUrl);
  await page.getByRole('link', { name: 'Receipt', exact: true }).nth(13).click();
  await expect(page).toHaveURL(/\/admin\/sales\/receipt\/bill-1-13\?date=2026-09-29&page=1/);
  await expect(dialog).toContainText('Receipt #38');
  await expect(dialog).not.toContainText('Receipt #39');
  // The same Sales list remains mounted behind the native modal.
  await expect(page.locator('main input[type=date]')).toHaveValue('2026-09-29');
  await page.mouse.click(2, 2);
  await expect(dialog).toHaveCount(0);
});

test('loading and failure remain dismissible; retry renders the stored snapshot and notes', async ({ page, signIn }) => {
  await receiptFixtures(page);
  let release!: () => void;
  const held = new Promise<void>(resolve => { release = resolve; });
  let fail = true;
  await page.route('**/api/v1/bills/*/receipt', async route => {
    if (fail) { await held; await route.fulfill({ status: 503, json: { success: false, message: 'Receipt temporarily unavailable.' } }); }
    else await route.fulfill({ json: { success: true, data: snapshot } });
  });
  await signIn(OWNER);
  await page.goto(listUrl);
  await page.getByRole('link', { name: 'Receipt', exact: true }).first().click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText('Loading the receipt…')).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Print' })).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(page).toHaveURL(listUrl);
  await page.getByRole('link', { name: 'Receipt', exact: true }).first().click();
  release();
  await expect(dialog.getByRole('alert')).toHaveText('Receipt temporarily unavailable.');
  fail = false;
  await dialog.getByRole('button', { name: 'Retry' }).click();
  await expect(dialog.getByText('2 × San Miguel Pale Pilsen')).toBeVisible();
  await expect(dialog.getByText('Unpaid', { exact: true })).toBeVisible();
  await expect(dialog.getByText('Settled later', { exact: true })).toBeVisible();
  await expect(dialog.getByText('SB-7K4-M2Q · 0.25 h of table time')).toBeVisible();
  await expect(dialog.getByText('Regular customer')).toBeVisible();
  await expect(dialog.getByText('Marco and friends', { exact: false })).toBeVisible();
  await page.keyboard.press('Escape');
});

test('cold modal and standalone URLs work; print contains one complete receipt and no list, notes or controls', async ({ page, signIn }, testInfo) => {
  await receiptFixtures(page);
  await signIn(OWNER);
  for (const url of ['/admin/sales/receipt/bill-1-0?date=2026-09-29&page=1', '/receipt/bill-1-0']) {
    await page.goto(url);
    await expect(page.getByText('Not an official receipt')).toBeVisible();
    await page.evaluate("window.print = () => { document.body.dataset.printCalled = 'yes'; }");
    await page.getByRole('button', { name: 'Print', exact: true }).click();
    await expect(page.locator('body')).toHaveAttribute('data-print-called', 'yes');
    await page.emulateMedia({ media: 'print' });
    await expect(page.getByText('SUPREME BILLIARD HALL', { exact: true })).toBeVisible();
    await expect(page.getByText('Not an official receipt')).toBeVisible();
    await expect(page.locator('.receipt-document')).toHaveCount(1);
    await expect(page.getByRole('heading', { name: 'Sales', exact: true })).not.toBeVisible();
    await expect(page.getByRole('heading', { name: 'Notes', exact: true })).not.toBeVisible();
    await expect(page.getByRole('button', { name: 'Print', exact: true })).not.toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Main' })).not.toBeVisible();
    const pdf = await page.pdf({ path: testInfo.outputPath(url.startsWith('/admin') ? 'modal.pdf' : 'standalone.pdf') });
    expect(pdf.byteLength).toBeGreaterThan(5000);
    await page.emulateMedia({ media: 'screen' });
    if (url.startsWith('/admin')) {
      await page.getByRole('button', { name: 'Close', exact: true }).click();
      await expect(page).toHaveURL(listUrl);
    } else await expect(page.getByRole('link', { name: 'Back to the floor' })).toHaveAttribute('href', '/floor');
  }
});

test('long receipts and notes, empty notes and legacy no-charge snapshots fit a phone and remain printable', async ({ page, signIn }, testInfo) => {
  await receiptFixtures(page);
  await page.setViewportSize({ width: 400, height: 800 });
  await page.route('**/api/v1/bills/*/receipt', route => route.fulfill({ json: { success: true, data: { ...snapshot, settlement: null, payload: {
    noCharge: true, total: 0,
    lines: Array.from({ length: 65 }, (_, i) => ({ description: `Historical product ${i} ${'longword'.repeat(20)}`, quantity: 1, lineTotal: 0 })),
  } } } }));
  await page.route('**/api/v1/bills/*/notes', route => route.fulfill({ json: { success: true, data: [] } }));
  await signIn(OWNER);
  await page.goto('/admin/sales/receipt/bill-1-0');
  await expect(page.getByText('Nothing to pay', { exact: true })).toBeVisible();
  await page.getByText('No notes were written on this bill.').scrollIntoViewIfNeeded();
  await expect(page.getByText('No notes were written on this bill.')).toBeInViewport();
  expect(await page.getByRole('dialog').evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true);
  await page.emulateMedia({ media: 'print' });
  await expect(page.getByText(/^Historical product 64 /)).toBeVisible();
  expect(await page.getByRole('dialog').evaluate(el => el.ownerDocument.defaultView!.getComputedStyle(el).maxHeight)).toBe('none');
  await page.pdf({ path: testInfo.outputPath('long-receipt.pdf') });
});

test('Floor exposes Sales only to admins and the server still refuses employees', async ({ page, signIn, hall }) => {
  void hall;
  await signIn(OWNER);
  await page.goto('/floor');
  await page.getByRole('link', { name: 'Sales', exact: true }).click();
  await expect(page).toHaveURL('/admin/sales');
  await signIn(COUNTER);
  await page.goto('/floor');
  await expect(page.getByRole('link', { name: 'Sales', exact: true })).toHaveCount(0);
  await page.goto('/admin/sales/receipt/bill-1-0');
  await expect(page).toHaveURL('/floor');
  const response = await page.request.get('/api/v1/bills?businessDate=2026-09-29');
  expect(response.status()).toBe(403);
});

test('real settled receipt keeps its sold name and price after catalog changes, in Sales and standalone', async ({ page, signIn, hall }) => {
  void hall;
  await signIn(OWNER);
  const product = await apiPost<ProductAdmin>(page.request, '/api/v1/products', { name: 'PR09 original snack', sellingPrice: 75 });
  const types = await apiGet<Array<{ id: string; isDefault: boolean }>>(page.request, '/api/v1/customer-types');
  const payment = await apiPost<Payment>(page.request, '/api/v1/quick-sales', {
    customerTypeId: types.find(type => type.isDefault)!.id,
    lines: [{ productId: product.id, quantity: 2 }],
    payment: { method: 'CASH', amount: 150, tendered: 200, billVersion: 0, idempotencyKey: crypto.randomUUID() },
  });
  const bill = { id: payment.billId };
  const before = await apiGet<Receipt>(page.request, `/api/v1/bills/${bill.id}/receipt`);
  const update = await page.request.put(`/api/v1/products/${product.id}`, { data: { name: 'PR09 renamed expensive snack', sellingPrice: 999 } });
  expect(update.ok()).toBe(true);
  await page.goto('/admin/sales');
  await page.getByRole('row').filter({ hasText: `#${before.receiptNo}` }).getByRole('link', { name: 'Receipt' }).click();
  await expect(page.getByRole('dialog')).toContainText('2 × PR09 original snack');
  await expect(page.getByRole('dialog')).toContainText('₱150.00');
  await expect(page.getByRole('dialog')).toContainText('₱50.00');
  await expect(page.getByRole('dialog')).not.toContainText('renamed expensive');
  await page.goto(`/receipt/${bill.id}`);
  await expect(page.locator('.receipt-document')).toContainText('2 × PR09 original snack');
  expect((await apiGet<Receipt>(page.request, `/api/v1/bills/${bill.id}/receipt`)).payload).toEqual(before.payload);
});
