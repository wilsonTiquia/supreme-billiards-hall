import { test, expect, OWNER, COUNTER, apiAs, apiGet, apiPost } from './fixtures';
import type { Expense, ExpenseCategory, Settings } from '../src/api/types';

for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
  test(`expense category keyboard selection, save and void; settings save at ${width}px ${theme}`, async ({ page, signIn, hall }) => {
    void hall;
    await page.setViewportSize({ width, height: 900 });
    const owner = await apiAs(OWNER);
    const name = `PR15 ${width} ${theme}`;
    const category = await apiPost<ExpenseCategory>(owner, '/api/v1/expense-categories', { name });
    const alternate = await apiPost<ExpenseCategory>(owner, '/api/v1/expense-categories', { name: `${name} alternate` });
    await signIn(COUNTER);
    await page.evaluate(t => { localStorage.setItem('supreme.theme.pos', t); localStorage.setItem('supreme.theme.admin', t); }, theme);
    await page.goto('/expenses');
    const record = page.getByRole('button', { name: 'Record expense' });
    await page.getByLabel('Amount', { exact: true }).fill('125.50');
    await expect(record).toBeDisabled();
    const radio = page.getByRole('radio', { name, exact: true });
    await radio.focus();
    await page.keyboard.press('Space');
    await expect(radio).toBeChecked();
    await page.keyboard.press('ArrowRight');
    await expect(page.getByRole('radio', { name: alternate.name, exact: true })).toBeChecked();
    await page.keyboard.press('ArrowLeft');
    await expect(radio).toBeChecked();
    await page.getByLabel('Note (optional)').fill(`${name} drawer`);
    await record.click();
    const drawerRow = page.getByRole('listitem').filter({ hasText: `${name} drawer` });
    await expect(drawerRow).toContainText('₱125.50');
    await page.getByLabel('Amount', { exact: true }).fill('74.50');
    await page.getByLabel('Paid from the cash drawer').uncheck();
    await page.getByLabel('Note (optional)').fill(`${name} owner`);
    await record.click();
    const ownerRow = page.getByRole('listitem').filter({ hasText: `${name} owner` });
    await expect(ownerRow).toContainText('Not from the drawer');
    await expect(page.locator('dl')).toContainText('₱200.00');
    await expect(page.locator('dl')).toContainText('₱125.50');
    await drawerRow.getByRole('button', { name: 'Void', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Void expense', exact: true })).toBeDisabled();
    await page.getByLabel('Reason', { exact: true }).fill('Entered twice');
    await page.getByRole('button', { name: 'Void expense', exact: true }).click();
    await expect(drawerRow).toContainText('Voided — Entered twice');
    await expect(page.locator('dl')).toContainText('₱74.50');
    await expect(page.locator('dl')).toContainText('₱0.00');
    const stored = (await apiGet<Expense[]>(owner, '/api/v1/expenses')).filter(e => e.expenseCategoryId === category.id);
    expect(stored).toHaveLength(2);
    expect(stored.find(e => e.paidFromDrawer)).toMatchObject({ amount: 125.5, voided: true, voidReason: 'Entered twice' });
    const kept = stored.find(e => !e.paidFromDrawer)!;
    expect(kept).toMatchObject({ amount: 74.5, voided: false });
    await page.reload();
    await expect(drawerRow).toContainText('Voided — Entered twice');
    await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
    await apiPost(owner, `/api/v1/expenses/${kept.id}/void`, { reason: 'Test cleanup' });

    await signIn(OWNER);
    await page.goto('/admin/settings');
    const prior = await apiGet<Settings>(owner, '/api/v1/settings');
    const save = page.getByRole('button', { name: 'Save', exact: true });
    await expect(save).toBeDisabled();
    await page.getByLabel('Float', { exact: true }).fill('invalid');
    await expect(save).toBeDisabled();
    await page.getByLabel('Float', { exact: true }).fill('1234.50');
    await page.getByLabel('Play it after a payment').setChecked(!prior.checkoutAnimation);
    await save.click();
    await expect(page.getByText('Saved. Tonight’s close-out will use it.')).toBeVisible();
    expect(await apiGet<Settings>(owner, '/api/v1/settings')).toMatchObject({ standardCashFloat: 1234.5, checkoutAnimation: !prior.checkoutAnimation });
    await page.reload();
    await expect(page.getByLabel('Float', { exact: true })).toHaveValue('1,234.50');
    await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
    expect((await owner.put('/api/v1/settings', { data: prior })).ok()).toBeTruthy();
    await owner.dispose();
  });
}

test('category loading, empty and error states do not allow recording', async ({ page, signIn }) => {
  await signIn(OWNER);
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/v1/expense-categories', async route => {
    await gate;
    await route.fulfill({ json: { success: true, data: [] } });
  });
  await page.route('**/api/v1/expenses', route => route.fulfill({ json: { success: true, data: [] } }));
  await page.goto('/expenses');
  await expect(page.getByText('Loading categories…')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Record expense' })).toBeDisabled();
  release();
  await expect(page.getByText('No expense categories yet.')).toBeVisible();
  await expect(page.getByText('Nothing paid out yet tonight.')).toBeVisible();
  await page.unroute('**/api/v1/expense-categories');
  await page.route('**/api/v1/expense-categories', route => route.fulfill({ status: 503, json: { success: false, message: 'Categories unavailable. Try again.' } }));
  await page.reload();
  await expect(page.getByText('Categories unavailable. Try again.')).toBeVisible();
  await expect(page.getByText('No expense categories yet.')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Record expense' })).toBeDisabled();
});

test('expense and settings pending saves and server errors stay visible', async ({ page, signIn }) => {
  await page.setViewportSize({ width: 400, height: 900 });
  await signIn(OWNER);
  await page.route('**/api/v1/expense-categories', route => route.fulfill({ json: { success: true, data: [{ id: 'test-category', name: 'Delivery' }] } }));
  let release!: () => void;
  let gate = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/v1/expenses', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: { success: true, data: [] } });
    await gate;
    await route.fulfill({ status: 409, json: { success: false, message: 'This business day is closed.' } });
  });
  await page.goto('/expenses');
  await page.getByRole('radio', { name: 'Delivery', exact: true }).check();
  await page.getByLabel('Amount', { exact: true }).fill('120');
  const record = page.getByRole('button', { name: 'Record expense' });
  await record.click();
  await expect(record).toBeDisabled();
  await expect(record).toHaveAttribute('aria-busy', 'true');
  release();
  await expect(page.getByText('This business day is closed.')).toBeVisible();
  await expect(page.getByText('Nothing paid out yet tonight.')).toBeVisible();
  gate = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/v1/settings', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: { success: true, data: { standardCashFloat: 1000, checkoutAnimation: true } } });
    await gate;
    await route.fulfill({ status: 409, json: { success: false, message: 'Settings could not be saved.' } });
  });
  await page.goto('/admin/settings');
  await page.getByLabel('Float', { exact: true }).fill('1500');
  const save = page.getByRole('button', { name: 'Save', exact: true });
  await save.click();
  await expect(save).toBeDisabled();
  await expect(save).toHaveAttribute('aria-busy', 'true');
  release();
  await expect(page.getByText('Settings could not be saved.')).toBeVisible();
  await expect(save).toBeEnabled();
  await expect(page.getByLabel('Float', { exact: true })).toHaveValue('1,500.00');
});

test('many long categories and expense notes wrap and remain reachable', async ({ page, signIn }) => {
  const names = Array.from({ length: 30 }, (_, i) => `Category ${i + 1} — repairs, deliveries and maintenance for the billiard hall`);
  await page.route('**/api/v1/expense-categories', r => r.fulfill({ json: { success: true, data: names.map((name, i) => ({ id: `category-${i}`, name })) } }));
  await page.route('**/api/v1/expenses', r => r.fulfill({ json: { success: true, data: names.map((name, i) => ({ id: `expense-${i}`, categoryName: name, amount: 120, paidFromDrawer: true, note: 'LongNote'.repeat(25), incurredAt: '2026-10-09T12:30:00Z', voided: false })) } }));
  await signIn(OWNER);
  for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(t => localStorage.setItem('supreme.theme.pos', t), theme);
    await page.goto('/expenses');
    await page.getByRole('radio', { name: names[29], exact: true }).check();
    await expect(page.getByRole('radio', { name: names[29], exact: true })).toBeChecked();
    await page.getByRole('listitem').last().getByRole('button', { name: 'Void' }).scrollIntoViewIfNeeded();
    await expect(page.getByRole('listitem').last().getByRole('button', { name: 'Void' })).toBeInViewport();
    await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
  }
});
