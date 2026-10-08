import { test, expect, OWNER, COUNTER, apiAs } from './fixtures';
import { batches, codesFor, voucherFixtures } from './pr12-fixtures';

test('batch counts and statuses stay server authoritative and code indicators cannot mutate vouchers', async ({ page, signIn }) => {
  await voucherFixtures(page);
  await signIn(OWNER);
  const writes: string[] = [];
  page.on('request', r => { if (r.method() !== 'GET' && /voucher/.test(r.url())) writes.push(r.url()); });
  await page.goto('/admin/vouchers');
  const cards = page.getByRole('list', { name: 'Voucher batches' }).getByRole('listitem');
  for (const [i, batch] of batches.entries()) {
    await expect(cards.nth(i).getByRole('heading', { level: 2 })).toHaveText(`${batch.quantity} ${batch.quantity === 1 ? 'coupon' : 'coupons'}`);
    await expect(cards.nth(i).locator('dd')).toHaveText([String(batch.outstanding), String(batch.redeemed), String(batch.expired)]);
  }
  await expect(cards.first()).toContainText('Created by owner on Oct');
  const trigger = cards.first().getByRole('button', { name: 'Codes', exact: true });
  await trigger.focus();
  await page.keyboard.press('Enter');
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('listitem')).toHaveCount(12);
  await expect(dialog.getByText('Redeemed on receipt #2533', { exact: true })).toBeVisible();
  await expect(dialog.getByText('Outstanding', { exact: true })).toHaveCount(6);
  await dialog.locator('.voucher-check').first().click();
  await dialog.locator('.voucher-check').nth(6).click();
  await expect(dialog.getByText('Redeemed on receipt #2533', { exact: true })).toBeVisible();
  await expect(dialog.getByRole('checkbox')).toHaveCount(0);
  expect(writes).toEqual([]);
  await page.keyboard.press('Escape');
  await expect(trigger).toBeFocused();
  await cards.nth(2).getByRole('button', { name: 'Codes', exact: true }).click();
  await expect(dialog.getByText('Redeemed on receipt #2533', { exact: true })).toBeVisible();
  await expect(dialog.getByText('Expired', { exact: true })).toHaveCount(0);
  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  await cards.nth(3).getByRole('button', { name: 'Codes', exact: true }).click();
  await expect(dialog.getByText('Expired', { exact: true })).toHaveCount(2);
  await expect(dialog.getByText('Redeemed on receipt #2533', { exact: true })).toBeVisible();
});

test('500 codes remain scrollable, copy in full and print without background or clipping', async ({ page, signIn, context }) => {
  await voucherFixtures(page);
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await signIn(OWNER);
  await page.goto('/admin/vouchers');
  await page.getByRole('button', { name: 'Codes', exact: true }).nth(1).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('listitem')).toHaveCount(500);
  const scroll = dialog.getByRole('region', { name: 'Voucher codes', exact: true });
  expect(await scroll.evaluate(el => el.scrollHeight > el.clientHeight)).toBe(true);
  await scroll.focus();
  await page.keyboard.press('PageDown');
  await expect.poll(() => scroll.evaluate(el => el.scrollTop)).toBeGreaterThan(0);
  await dialog.getByText('SB-499-ABC', { exact: true }).scrollIntoViewIfNeeded();
  await expect(dialog.getByText('SB-499-ABC', { exact: true })).toBeInViewport();
  await dialog.getByRole('button', { name: 'Copy all codes' }).click();
  await expect(dialog.getByRole('status', { name: 'Copy result' })).toHaveText('All codes copied.');
  expect(await page.evaluate<string>('navigator.clipboard.readText()')).toBe(codesFor(batches[1]).map(c => c.code).join('\n'));
  await page.evaluate("window.printCalled = false; window.print = () => { window.printCalled = true; }");
  await dialog.getByRole('button', { name: 'Print codes' }).click();
  expect(await page.evaluate('window.printCalled')).toBe(true);
  await page.emulateMedia({ media: 'print' });
  await expect(page.locator('#root')).toBeHidden();
  await expect(dialog.getByRole('button', { name: 'Print codes' })).toBeHidden();
  expect(await scroll.evaluate(el => el.scrollHeight === el.clientHeight)).toBe(true);
  expect(await dialog.evaluate(el => el.scrollHeight === el.clientHeight)).toBe(true);
  await expect(dialog.getByText('SB-499-ABC', { exact: true })).toBeVisible();
  await page.emulateMedia({ media: 'screen' });
  await page.evaluate("Object.defineProperty(navigator.clipboard, 'writeText', { configurable: true, value: () => Promise.reject(new Error('Permission denied')) })");
  await dialog.getByRole('button', { name: 'Copy all codes' }).click();
  await expect(dialog.getByRole('status', { name: 'Copy result' })).toHaveText('Could not copy. Select the codes to copy them manually.');
});

test('long codes and notes fit both themes and widths with keyboard scrolling and modal focus', async ({ page, signIn }) => {
  await voucherFixtures(page);
  const codes = codesFor(batches[0]);
  codes[0].code = 'SB-LONG-CODE-WITH-MANY-SEGMENTS-1234567890-ABCDEFG';
  codes[0].redeemedReceiptNo = null;
  await page.route('**/api/v1/vouchers?*', route => route.fulfill({ json: { success: true, data: codes } }));
  await signIn(OWNER);
  for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(t => localStorage.setItem('supreme.theme.admin', t), theme);
    await page.goto('/admin/vouchers');
    await page.getByRole('button', { name: 'Codes', exact: true }).first().click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('Redeemed · receipt not yet available')).toBeVisible();
    const code = dialog.getByRole('region', { name: `Code ${codes[0].code}`, exact: true });
    expect(await code.evaluate(el => el.ownerDocument.defaultView!.getComputedStyle(el).whiteSpace)).toBe('nowrap');
    await code.focus();
    await page.keyboard.press('End');
    await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
    expect(await dialog.evaluate(el => el.scrollWidth === el.clientWidth)).toBe(true);
    for (let i = 0; i < 20; i++) {
      await page.keyboard.press('Tab');
      expect(await dialog.evaluate(el => el.contains(el.ownerDocument.activeElement) || el.ownerDocument.activeElement === el.ownerDocument.body)).toBe(true);
    }
    await page.keyboard.press('Escape');
    await expect(page.getByRole('button', { name: 'Codes', exact: true }).first()).toBeFocused();
  }
});

test('loading, error and empty states distinguish unavailable data from empty batches', async ({ page, signIn }) => {
  await voucherFixtures(page);
  await signIn(OWNER);
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/v1/voucher-batches', async route => {
    await gate;
    await route.fulfill({ status: 400, json: { success: false, message: 'Voucher batches unavailable' } });
  });
  await page.goto('/admin/vouchers');
  await expect(page.getByText('Loading voucher batches…')).toBeVisible();
  release();
  await expect(page.getByText('Voucher batches unavailable')).toBeVisible();
  await expect(page.getByText('No vouchers have been generated yet.')).toHaveCount(0);
  await page.route('**/api/v1/voucher-batches', route => route.fulfill({ json: { success: true, data: [] } }));
  await page.reload();
  await expect(page.getByText('No vouchers have been generated yet.')).toBeVisible();
  await page.unroute('**/api/v1/voucher-batches');
  await voucherFixtures(page);
  let releaseCodes!: () => void;
  const codeGate = new Promise<void>(resolve => { releaseCodes = resolve; });
  await page.route('**/api/v1/vouchers?*', async route => {
    await codeGate;
    await route.fulfill({ status: 400, json: { success: false, message: 'Codes unavailable' } });
  });
  await page.reload();
  await page.getByRole('button', { name: 'Codes', exact: true }).first().click();
  await expect(page.getByText('Loading codes…')).toBeVisible();
  releaseCodes();
  await expect(page.getByRole('dialog').getByText('Codes unavailable')).toBeVisible();
  await page.keyboard.press('Escape');
  await page.route('**/api/v1/vouchers?*', route => route.fulfill({ json: { success: true, data: [] } }));
  await page.getByRole('button', { name: 'Codes', exact: true }).first().click();
  await expect(page.getByText('No codes in this batch.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Copy all codes' })).toHaveCount(0);
});

test('real generated codes can be copied, reopened and retain server counts', async ({ page, signIn, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await signIn(OWNER);
  await page.goto('/admin/vouchers');
  await page.getByRole('button', { name: 'New batch' }).click();
  await page.getByLabel('How many').fill('3');
  await page.getByLabel('Hours per voucher').fill('1.5');
  await page.getByLabel('Expires on').fill('2099-12-31');
  await page.getByLabel('What this giveaway is (optional)').fill('PR12 real giveaway');
  await page.getByRole('button', { name: 'Generate', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toHaveAttribute('aria-label', '3 codes generated');
  await expect(dialog.getByRole('listitem')).toHaveCount(3);
  await expect(dialog.getByText('Outstanding', { exact: true })).toHaveCount(3);
  const codes = await dialog.locator('code').allTextContents();
  expect(new Set(codes).size).toBe(3);
  await dialog.getByRole('button', { name: 'Copy all codes' }).click();
  await expect(dialog.getByRole('status', { name: 'Copy result' })).toHaveText('All codes copied.');
  expect(await page.evaluate<string>('navigator.clipboard.readText()')).toBe(codes.join('\n'));
  await page.keyboard.press('Escape');
  const card = page.getByRole('listitem').filter({ has: page.getByRole('heading', { name: 'PR12 real giveaway', exact: true }) });
  await expect(card.locator('dd')).toHaveText(['3', '0', '0']);
  await expect(card).toContainText('90 min');
  await card.getByRole('button', { name: 'Codes', exact: true }).click();
  await expect(dialog.locator('code')).toHaveCount(3);
  expect((await dialog.locator('code').allTextContents()).sort()).toEqual([...codes].sort());
});

test('employees cannot read voucher batches or codes through the API or admin route', async ({ page, signIn, hall }) => {
  expect(hall.beerId).toBeTruthy();
  const counter = await apiAs(COUNTER);
  try {
    for (const path of ['/api/v1/voucher-batches', '/api/v1/vouchers']) expect((await counter.get(path)).status()).toBe(403);
  } finally { await counter.dispose(); }
  await signIn(COUNTER);
  const requests: string[] = [];
  page.on('request', r => { if (/\/api\/v1\/voucher/.test(r.url())) requests.push(r.url()); });
  await page.goto('/admin/vouchers');
  await expect(page).toHaveURL(/\/floor$/);
  expect(requests).toEqual([]);
});
