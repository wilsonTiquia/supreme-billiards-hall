import { mkdir } from 'node:fs/promises';
import { test, expect, OWNER, COUNTER, apiAs, apiGet, apiPost, backdateSession, BEER } from './fixtures';
import type { Page } from '@playwright/test';
import type { FloorView, CustomerType, Session, Bill, Payment } from '../src/api/types';

const screenshots = '../docs/qa/floor';
const photo = { name: 'confirmation.png', mimeType: 'image/png', buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jf1sAAAAASUVORK5CYII=', 'base64') };

async function capture(page: Page, name: string, width: number, theme: string) {
  if (name === 'quick-sale' && width === 1280) {
    await page.getByRole('button', { name: /^Take ₱/ }).scrollIntoViewIfNeeded();
  }
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
  await page.screenshot({ path: `${screenshots}/${name}-${theme}-${width}.png`, fullPage: name !== 'session-products', animations: 'disabled' });
}

async function appearance(page: Page, width: number, theme: string) {
  await page.setViewportSize({ width, height: width === 400 ? 800 : 720 });
  await page.evaluate((value) => localStorage.setItem('supreme.theme.pos', value), theme);
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
}

async function assertFiguresFit(page: Page) {
  const problems = await page.locator('button').filter({ has: page.locator('.figure-table-number') }).evaluateAll((cards) => {
    const errors: string[] = [];
    for (const card of cards) {
      const box = card.getBoundingClientRect();
      const figures = [...card.querySelectorAll('.figure-amount, .figure-timer')];
      for (const figure of figures) {
        const rect = figure.getBoundingClientRect();
        if (rect.left < box.left || rect.right > box.right || rect.bottom > box.bottom || figure.scrollWidth > figure.clientWidth + 1) errors.push(figure.textContent ?? 'clipped');
      }
      if (figures.length === 2) {
        const [a, b] = figures.map((figure) => figure.getBoundingClientRect());
        if (a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom) errors.push('overlap');
      }
    }
    return errors;
  });
  expect(problems).toEqual([]);
}

test('floor, ordering, payment and float layouts in both sizes and themes', async ({ page, signIn, hall }) => {
  test.setTimeout(240_000);
  await mkdir(screenshots, { recursive: true });
  const api = await apiAs(COUNTER);
  const floor = await apiGet<FloorView>(api, '/api/v1/tables');
  const types = await apiGet<CustomerType[]>(api, '/api/v1/customer-types');
  const start = async (n: number, extra = {}) => apiPost<Session>(api, '/api/v1/sessions', {
    tableId: floor.tables.find((t) => t.tableNumber === n)!.id,
    customerTypeId: types.find((t) => t.isDefault)!.id, ...extra,
  });
  const session = await start(3);
  backdateSession(session.id, 286);
  const long = await start(2, { flatAmount: 1234567.89, flatRateReason: 'Responsive QA fixture' });
  backdateSession(long.id, 7439);
  await apiPost(api, `/api/v1/sessions/${long.id}/pause`, {});
  await apiPost(api, `/api/v1/bills/${session.billId}/lines`, { productId: hall.beerId, quantity: 1 });
  await signIn(COUNTER);

  for (const width of [400, 1280]) for (const theme of ['light', 'dark']) {
    await appearance(page, width, theme);
    await page.goto('/floor');
    const card = page.locator('button').filter({ has: page.locator('.figure-table-number', { hasText: /^03$/ }) });
    await expect(card).toBeVisible();
    await assertFiguresFit(page);
    await capture(page, 'floor', width, theme);
    // Two clicks on desktop; three on mobile. The API read verifies one actual new line.
    const before = await apiGet<Bill>(api, `/api/v1/bills/${session.billId}`);
    await card.click();
    await expect(page.getByRole('heading', { name: 'Bill', exact: true })).toBeVisible();
    if (width === 400) {
      await page.getByRole('button', { name: 'Add items', exact: true }).click();
      await expect(page.getByLabel('Search products')).toBeFocused();
      await capture(page, 'session-products', width, theme);
    } else {
      const products = await page.getByRole('heading', { name: 'Add to the bill' }).boundingBox();
      const bill = await page.getByRole('heading', { name: 'Bill', exact: true }).boundingBox();
      expect(products!.x).toBeLessThan(bill!.x);
      await expect(page.getByLabel('Search products')).toBeFocused();
    }
    await page.getByRole('button', { name: new RegExp(BEER.name) }).click();
    await expect.poll(async () => (await apiGet<Bill>(api, `/api/v1/bills/${session.billId}`)).lines.length).toBe(before.lines.length + 1);
    await expect(page.getByRole('dialog')).toHaveCount(0);
    if (width === 400) {
      await expect(page.getByRole('button', { name: 'Add items', exact: true })).toBeFocused();
      await page.getByRole('button', { name: 'Add items', exact: true }).click();
      await page.keyboard.press('Escape');
      await expect(page.getByRole('dialog')).toHaveCount(0);
    }
    await capture(page, 'session', width, theme);
  }

  // Narrow cards at grid/sidebar breakpoints and enlarged browser text must stay readable.
  for (const width of [320, 640, 767, 768, 899, 900, 1024, 1280, 1536]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/floor');
    await expect(page.locator('.figure-amount').first()).toBeVisible();
    await assertFiguresFit(page);
  }
  await page.setViewportSize({ width: 400, height: 800 });
  await page.locator('html').evaluate((el) => { el.style.fontSize = '24px'; });
  await assertFiguresFit(page);
  await page.locator('html').evaluate((el) => { el.style.fontSize = ''; });
  for (const width of [899, 900]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`/sessions/${session.id}`);
    await expect(page.getByRole('heading', { name: 'Bill', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Add items', exact: true })).toHaveCount(width < 900 ? 1 : 0);
    await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
  }
  await page.getByRole('button', { name: 'Close table & check out' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Close & check out', exact: true }).click();
  await page.waitForURL(`/checkout/${session.billId}`);
  for (const width of [400, 1280]) for (const theme of ['light', 'dark']) {
    await appearance(page, width, theme);
    await expect(page.getByRole('heading', { name: 'Take payment' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Back to the floor', exact: true })).toHaveCount(0);
    await expect(page.getByLabel('Payment photo')).toHaveCount(0);
    for (const method of ['GCash', 'Maya']) {
      await page.getByRole('button', { name: method, exact: true }).click();
      await page.getByLabel('Reference number').fill('QA-TABLE-3');
      await page.getByLabel('Payment photo').setInputFiles(photo);
      await expect(page.getByText(photo.name, { exact: true })).toBeVisible();
      await capture(page, `payment-${method.toLowerCase()}`, width, theme);
    }
  }
  await page.getByRole('button', { name: 'GCash', exact: true }).click();
  await page.getByLabel('Reference number').fill('QA-TABLE-3');
  await page.getByLabel('Payment photo').setInputFiles(photo);
  const paidResponse = page.waitForResponse((response) => response.url().endsWith(`/bills/${session.billId}/payment`) && response.request().method() === 'POST');
  await page.getByRole('button', { name: /^Take ₱/ }).click();
  const payment: Payment = (await (await paidResponse).json()).data;
  await page.waitForURL(`/receipt/${session.billId}`);
  await expect(page.locator('main').getByRole('status')).toHaveText('Photo attached.');
  const owner = await apiAs(OWNER);
  const saved = await owner.get(`/api/v1/payments/${payment.id}/photo`);
  expect(saved.ok()).toBe(true);
  expect(await saved.body()).toEqual(photo.buffer);
  for (const width of [400, 1280]) for (const theme of ['light', 'dark']) {
    await appearance(page, width, theme);
    await expect(page.getByText('Photo attached.', { exact: true })).toBeVisible();
    await capture(page, 'receipt', width, theme);
  }
  await apiPost(api, `/api/v1/sessions/${long.id}/close`, {});
  for (const width of [400, 1280]) for (const theme of ['light', 'dark']) {
    await page.goto('/end-of-day');
    await appearance(page, width, theme);
    await expect(page.getByText('Change float (cash in the drawer before opening)', { exact: true })).toBeVisible();
    await capture(page, 'end-of-day', width, theme);
    await page.getByRole('button', { name: 'Tonight’s float was different' }).click();
    await expect(page.getByLabel('Change float (cash in the drawer before opening)')).toBeVisible();
    await capture(page, 'float-override', width, theme);
  }
  await owner.dispose();
  await api.dispose();
});

test('quick sale photo failure keeps the payment and receipt fallback retries only the photo', async ({ page, signIn, hall }) => {
  test.setTimeout(180_000);
  void hall;
  await signIn(COUNTER);
  const payments: Payment[] = [];
  page.on('response', async (response) => {
    if (response.url().endsWith('/api/v1/quick-sales') && response.request().method() === 'POST' && response.ok()) payments.push((await response.json()).data);
  });
  await page.goto('/quick-sale');
  await page.getByRole('button', { name: new RegExp(BEER.name) }).click();
  for (const width of [400, 1280]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: width === 400 ? 800 : 720 });
    // Preserve this in-memory basket while switching theme through the real control.
    if (await page.locator('html').getAttribute('data-theme') !== theme) {
      if (width === 400) await page.getByLabel('Open the menu').click();
      await page.getByRole('button', { name: /Switch to .* theme/ }).click();
      if (width === 400) await page.getByLabel('Close the menu', { exact: true }).last().click();
    }
    await page.getByRole('button', { name: 'Maya', exact: true }).click();
    await page.getByLabel('Reference number').fill('QA-QUICK-MAYA');
    await page.getByLabel('Payment photo').setInputFiles({ ...photo, buffer: Buffer.from('not an image') });
    await capture(page, 'quick-sale', width, theme);
  }
  await page.getByRole('button', { name: /^Take ₱/ }).click();
  await expect(page.getByRole('heading', { name: 'Sold', exact: true })).toBeVisible();
  await expect(page.locator('main').getByRole('status')).toContainText('Payment recorded. Photo could not be attached');
  expect(payments).toHaveLength(1);
  for (const width of [400, 1280]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: width === 400 ? 800 : 720 });
    if (await page.locator('html').getAttribute('data-theme') !== theme) {
      if (width === 400) await page.getByLabel('Open the menu').click();
      await page.getByRole('button', { name: /Switch to .* theme/ }).click();
      if (width === 400) await page.getByLabel('Close the menu', { exact: true }).last().click();
    }
    await capture(page, 'quick-sale-paid', width, theme);
  }
  await page.getByRole('button', { name: 'View receipt' }).click();
  await expect(page.getByLabel('Add photo (optional confirmation)')).toBeEnabled();
  for (const width of [400, 1280]) for (const theme of ['light', 'dark']) {
    await appearance(page, width, theme);
    await expect(page.locator('main').getByRole('status')).toContainText('Payment recorded. Photo could not be attached');
    await capture(page, 'receipt-fallback', width, theme);
  }
  await page.getByLabel('Add photo (optional confirmation)').setInputFiles(photo);
  await expect(page.locator('main').getByRole('status')).toHaveText('Photo attached.');
  expect(payments).toHaveLength(1);
  await page.reload();
  await expect(page.getByLabel('Add photo (optional confirmation)')).toBeDisabled();
  await expect(page.locator('main').getByRole('status')).toHaveText('Photo attached.');
  const owner = await apiAs(OWNER);
  const saved = await owner.get(`/api/v1/payments/${payments[0].id}/photo`);
  expect(saved.ok()).toBe(true);
  expect(await saved.body()).toEqual(photo.buffer);
  await owner.dispose();
});

test('duplicate-reference retry retains the selected photo and the same payment key', async ({ page, signIn, hall }) => {
  const api = await apiAs(COUNTER);
  const customerTypes = await apiGet<CustomerType[]>(api, '/api/v1/customer-types');
  await apiPost(api, '/api/v1/quick-sales', {
    customerTypeId: customerTypes.find((t) => t.isDefault)!.id,
    lines: [{ productId: hall.beerId, quantity: 1 }],
    payment: { method: 'GCASH', amount: 90, referenceNo: 'QA-DUPLICATE', idempotencyKey: crypto.randomUUID(), billVersion: 0 },
  });
  const floor = await apiGet<FloorView>(api, '/api/v1/tables');
  const types = await apiGet<CustomerType[]>(api, '/api/v1/customer-types');
  const session = await apiPost<Session>(api, '/api/v1/sessions', {
    tableId: floor.tables.find((t) => t.tableNumber === 4)!.id,
    customerTypeId: types.find((t) => t.isDefault)!.id,
  });
  await apiPost(api, `/api/v1/bills/${session.billId}/lines`, { productId: hall.beerId, quantity: 1 });
  await apiPost(api, `/api/v1/sessions/${session.id}/close`, {});
  const attempts: Array<{ idempotencyKey: string; duplicateOverride?: boolean; method: string; tendered?: number }> = [];
  let uploads = 0;
  page.on('request', (req) => {
    if (req.method() === 'POST' && req.url().endsWith(`/bills/${session.billId}/payment`)) attempts.push(req.postDataJSON());
    if (req.method() === 'POST' && /\/payments\/[^/]+\/photo$/.test(req.url())) uploads++;
  });
  await signIn(COUNTER);
  await page.goto(`/checkout/${session.billId}`);
  await page.getByRole('button', { name: 'GCash', exact: true }).click();
  await page.getByLabel('Payment photo').setInputFiles(photo);
  await page.getByRole('button', { name: 'Remove photo' }).click();
  await expect(page.getByText(photo.name, { exact: true })).toHaveCount(0);
  await page.getByLabel('Payment photo').setInputFiles(photo);
  await expect(page.getByText(photo.name, { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Cash', exact: true }).click();
  await expect(page.getByLabel('Payment photo')).toHaveCount(0);
  await page.getByRole('button', { name: 'GCash', exact: true }).click();
  await expect(page.getByText(photo.name, { exact: true })).toHaveCount(0);
  await page.getByLabel('Reference number').fill('QA-DUPLICATE');
  await page.getByLabel('Payment photo').setInputFiles(photo);
  await page.getByRole('button', { name: /^Take ₱/ }).click();
  await expect(page.getByRole('button', { name: 'Record anyway' })).toBeVisible();
  await expect(page.getByText(photo.name, { exact: true })).toBeVisible();
  expect(uploads).toBe(0);
  await page.getByRole('button', { name: 'Record anyway' }).click();
  await page.waitForURL(`/receipt/${session.billId}`);
  await expect(page.locator('main').getByRole('status')).toHaveText('Photo attached.');
  expect(attempts).toHaveLength(2);
  expect(attempts[0].idempotencyKey).toBe(attempts[1].idempotencyKey);
  expect(attempts[1].duplicateOverride).toBe(true);
  expect(attempts[1].tendered).toBeUndefined();
  expect(uploads).toBe(1);
  await api.dispose();
});
