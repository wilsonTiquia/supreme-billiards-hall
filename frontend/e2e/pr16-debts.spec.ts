import { test, expect, OWNER, COUNTER, apiAs, apiGet, apiPost, openTable, backdateSession } from './fixtures';
import { debtFixtures, debts, nights } from './pr16-fixtures';
import type { Bill, Session, UnpaidBill, CashCount } from '../src/api/types';

for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
  test(`grouped debts and earlier-night navigation at ${width}px ${theme}`, async ({ page, signIn }) => {
    await debtFixtures(page);
    await signIn(OWNER);
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(t => localStorage.setItem('supreme.theme.pos', t), theme);
    await page.goto('/unsettled');
    const dates = page.locator('main h3 time');
    await expect(dates).toHaveText(['Friday, October 9, 2026', 'Thursday, October 8, 2026', 'Saturday, September 26, 2026', 'Friday, September 25, 2026', 'Saturday, August 1, 2026']);
    const sameNight = page.getByRole('region', { name: 'Friday, September 25, 2026' });
    await expect(sameNight.getByRole('listitem')).toHaveCount(2);
    await expect(sameNight.getByRole('listitem').first()).toContainText('Ate Liza’s group - pays next week');
    await expect(page.getByLabel('13 days outstanding', { exact: true })).toHaveClass(/text-text-dim/);
    await expect(page.getByLabel('14 days outstanding', { exact: true }).first()).toHaveClass(/text-danger/);
    await expect(page.getByLabel('0 days outstanding', { exact: true })).toHaveText('Tonight');
    await expect(page.getByRole('link', { name: 'Receipt #4463', exact: true })).toHaveAttribute('href', '/receipt/debt-today');
    await expect(page.getByRole('listitem').filter({ hasText: 'Jun and Marco' }).getByRole('link', { name: 'Settle' })).toHaveAttribute('href', '/checkout/debt-today');
    await page.getByLabel('Who', { exact: true }).fill('pays next week');
    await page.getByLabel('Played from').fill('2026-09-25');
    await page.getByLabel('Played to').fill('2026-09-26');
    await expect(page.locator('main li')).toHaveCount(2);
    await expect(page.locator('main')).toContainText('₱826.00');
    await page.getByLabel('Who', { exact: true }).fill('No such person');
    await expect(page.getByText('No unpaid bill matches those filters.').last()).toBeVisible();
    await expect(page.locator('main h3')).toHaveCount(0);
    await expect(page.getByText('Nothing is owed.', { exact: true })).toHaveCount(0);

    await page.goto('/end-of-day');
    const toggle = page.getByRole('button', { name: /20 earlier nights/ });
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await toggle.focus();
    await page.keyboard.press('Enter');
    await expect(toggle).toHaveAttribute('aria-expanded', 'true');
    const tags = page.locator(`#${await toggle.getAttribute('aria-controls')}`.replaceAll(':', '\\:')).getByRole('link');
    await expect(tags).toHaveCount(20);
    await expect(tags.first()).toHaveText('Sep 1, 20261 bill');
    for (let i = 0; i < nights.length; i++) await expect(tags.nth(i)).toHaveAttribute('href', `/end-of-day?date=${nights[i].businessDate}`);
    await tags.last().click();
    await expect(page).toHaveURL(/date=2026-09-20$/);
    await expect(page.getByText('Sunday, September 20, 2026', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /19 earlier nights/ })).toHaveAttribute('aria-expanded', 'false');
    await page.getByRole('link', { name: 'Back to tonight' }).click();
    await expect(page).toHaveURL(/\/end-of-day$/);
    await expect(page.getByLabel('14 days outstanding').first()).toHaveClass(/text-danger/);
    await page.getByRole('link', { name: 'All 6 unpaid bills' }).click();
    await expect(page).toHaveURL(/\/unsettled$/);
    await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
  });
}

test('loading, error, empty and long-note states preserve all note text', async ({ page, signIn }) => {
  await debtFixtures(page);
  await signIn(OWNER);
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/v1/bills/unpaid', async r => { await gate; await r.fulfill({ json: { success: true, data: [] } }); });
  await page.goto('/unsettled');
  await expect(page.getByText('Loading what is owed…', { exact: true })).toBeVisible();
  release();
  await expect(page.getByText('No bills have been left unpaid.')).toBeVisible();
  await page.route('**/api/v1/bills/unpaid', r => r.fulfill({ status: 503, json: { success: false, message: 'Debts unavailable. Try again.' } }));
  await page.reload();
  await expect(page.getByText('Debts unavailable. Try again.')).toBeVisible();
  await page.route('**/api/v1/bills/unpaid', r => r.fulfill({ json: { success: true, data: [{ ...debts[0], latestNote: { body: 'LongNote'.repeat(70) } }] } }));
  await page.route('**/api/v1/business-day/uncounted', r => r.fulfill({ json: { success: true, data: [] } }));
  for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(t => localStorage.setItem('supreme.theme.pos', t), theme);
    for (const route of ['/unsettled', '/end-of-day']) {
      await page.goto(route);
      await expect(page.getByText('LongNote'.repeat(70), { exact: true })).toBeVisible();
      await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
    }
    await expect(page.getByRole('button', { name: /earlier nights/ })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Go to the unpaid list' })).toBeVisible();
  }
});

test('float edit, reset, pending, errors and saved count never leak into a different night', async ({ page, signIn }) => {
  await debtFixtures(page);
  await signIn(OWNER);
  await page.goto('/end-of-day');
  const float = page.getByLabel('Change float (cash in the drawer before opening)');
  await page.getByRole('button', { name: 'Different', exact: true }).click();
  await expect(float).toHaveValue('1,000.00');
  await float.fill('1250.50');
  await page.getByRole('button', { name: 'Use the usual ₱1,000.00' }).click();
  await expect(float).toHaveCount(0);
  await page.getByRole('button', { name: 'Different', exact: true }).click();
  await float.fill('1250.50');
  await page.getByLabel('Cash counted', { exact: true }).fill('1300');
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  let body: unknown;
  await page.route('**/api/v1/business-day/2026-10-09/cash-count', async r => {
    body = r.request().postDataJSON(); await gate;
    await r.fulfill({ status: 503, json: { success: false, message: 'Count could not be saved. Try again.' } });
  });
  const record = page.getByRole('button', { name: 'Record the count' });
  await record.click();
  await expect(record).toBeDisabled();
  expect(body).toEqual({ countedCash: 1300, openingFloat: 1250.5 });
  release();
  await expect(page.getByText('Count could not be saved. Try again.')).toBeVisible();
  // Retry the same input after failure, then verify successful local state too.
  const saved = { id: 'count', businessDate: '2026-10-09', openingFloat: 1250.5, countedCash: 1300, expectedCash: 1250.5, cashSales: 0, cashExpenses: 0, variance: 49.5, floatOverridden: true, closedAt: null, salesAfterClose: 0, expensesAfterClose: 0 };
  await page.route('**/api/v1/business-day/2026-10-09/cash-count', r => r.fulfill({ json: { success: true, data: saved } }));
  await record.click();
  await expect(page.getByRole('heading', { name: 'Drawer counted' })).toBeVisible();
  await page.getByRole('button', { name: /20 earlier nights/ }).click();
  await page.getByRole('link', { name: 'Sep 1, 2026 1 bill', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Count the drawer', exact: true })).toBeVisible();
  await expect(page.getByLabel('Cash counted', { exact: true })).toHaveValue('');
  await expect(page.getByRole('heading', { name: 'Drawer counted' })).toHaveCount(0);
  await page.route('**/api/v1/business-day/*/close', r => r.fulfill({ status: 409, json: { success: false, message: 'Count the drawer before closing.' } }));
  await page.getByRole('button', { name: /^Close Tuesday/ }).click();
  await expect(page.getByText('Count the drawer before closing.', { exact: true })).toBeVisible();
});

test('counter collects a real frozen debt through the grouped list and opens its receipt', async ({ page, signIn, hall }) => {
  void hall;
  await signIn(COUNTER);
  const api = await apiAs(COUNTER);
  const id = await openTable(page, 6);
  backdateSession(id, 12);
  const session = await apiGet<Session>(api, `/api/v1/sessions/${id}`);
  await apiPost(api, `/api/v1/sessions/${id}/close`, {});
  const bill = await apiGet<Bill>(api, `/api/v1/bills/${session.billId}`);
  const debt = await apiPost<UnpaidBill>(api, `/api/v1/bills/${bill.id}/leave-unpaid`, { billVersion: bill.version, note: 'PR16 customer - pays tonight; keep this whole note' });
  await page.goto('/unsettled');
  const row = page.getByRole('listitem').filter({ hasText: 'PR16 customer' });
  await row.getByRole('link', { name: `Receipt #${debt.receiptNo}` }).click();
  await expect(page).toHaveURL(new RegExp(`/receipt/${bill.id}$`));
  await page.goto('/unsettled');
  await row.getByRole('link', { name: 'Settle', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Collect this debt' })).toBeVisible();
  await page.getByLabel('Tendered').fill(String(debt.totalAmount));
  await page.getByRole('button', { name: /^Take ₱/ }).click();
  await expect(page).toHaveURL(new RegExp(`/receipt/${bill.id}$`));
  expect((await apiGet<Bill>(api, `/api/v1/bills/${bill.id}`)).status).toBe('CLOSED');
  expect((await apiGet<UnpaidBill[]>(api, '/api/v1/bills/unpaid')).some(b => b.id === bill.id)).toBe(false);
  await page.goto('/unsettled');
  await expect(row).toHaveCount(0);
  await api.dispose();
});

test('real scratch cash count, correction and close preserve the selected night', async ({ page, signIn }) => {
  const api = await apiAs(OWNER);
  const date = '2026-08-15';
  await signIn(OWNER);
  await page.goto(`/end-of-day?date=${date}`);
  await page.getByRole('button', { name: /^Close Saturday/ }).click();
  await expect(page.getByRole('status').filter({ hasText: /count/i })).toBeVisible();
  await page.getByRole('button', { name: 'Different', exact: true }).click();
  await page.getByLabel('Change float (cash in the drawer before opening)').fill('750.50');
  await page.getByLabel('Cash counted', { exact: true }).fill('750.50');
  await page.getByRole('button', { name: 'Record the count' }).click();
  await expect(page.getByRole('heading', { name: 'Drawer counted' })).toBeVisible();
  expect(await apiGet<CashCount>(api, `/api/v1/business-day/${date}/cash-count`)).toMatchObject({ openingFloat: 750.5, countedCash: 750.5, variance: 0 });
  await page.getByRole('button', { name: 'Correct the count' }).click();
  await page.getByLabel('Corrected count').fill('755');
  await page.getByRole('button', { name: 'Save correction' }).click();
  await expect(page.getByText('The drawer is over.', { exact: false })).toBeVisible();
  expect(await apiGet<CashCount>(api, `/api/v1/business-day/${date}/cash-count`)).toMatchObject({ openingFloat: 750.5, countedCash: 755, variance: 4.5 });
  await page.getByRole('button', { name: /^Close Saturday/ }).click();
  await expect(page.getByRole('heading', { name: /is closed$/ })).toBeVisible();
  expect((await apiGet<CashCount>(api, `/api/v1/business-day/${date}/cash-count`)).closedAt).not.toBeNull();
  await api.dispose();
});

test('a stale closed night retains its recount flow and submits to that night', async ({ page, signIn }) => {
  await debtFixtures(page);
  const date = '2026-09-01';
  const saved = { id: 'stale-count', businessDate: date, openingFloat: 1000, countedCash: 1100, expectedCash: 1000, cashSales: 0, cashExpenses: 0, variance: 100, floatOverridden: false, countedAt: `${date}T12:00:00Z`, closedAt: `${date}T12:01:00Z`, salesAfterClose: 1, amountAfterClose: 100, cashAfterClose: 100, expensesAfterClose: 0 };
  await page.route(`**/api/v1/business-day/${date}/cash-count`, r => r.fulfill({ json: { success: true, data: saved } }));
  await signIn(OWNER);
  await page.goto(`/end-of-day?date=${date}`);
  await expect(page.getByText('Closed, then traded on.', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: 'Count the drawer again' }).click();
  await page.getByLabel('Cash counted now').fill('1100');
  await page.getByLabel('Why', { exact: true }).fill('Late cash sale');
  let submitted: unknown;
  await page.route(`**/api/v1/business-day/${date}/recount`, r => {
    submitted = r.request().postDataJSON();
    return r.fulfill({ json: { success: true, data: { ...saved, closedAt: null, salesAfterClose: 0, variance: 0, expectedCash: 1100 } } });
  });
  await page.getByRole('button', { name: 'Save the new count' }).click();
  await expect(page.getByText('The drawer balances exactly.')).toBeVisible();
  expect(submitted).toEqual({ countedCash: 1100, note: 'Late cash sale' });
  await expect(page.getByRole('button', { name: /^Close Tuesday/ })).toBeEnabled();
});
