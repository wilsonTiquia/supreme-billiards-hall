import { mkdir } from 'node:fs/promises';
import { test, expect, OWNER, COUNTER, apiAs, apiPost, backdateSession } from './fixtures';
import type { SetupItem, PoolTable, CustomerType, VoucherBatch, Session } from '../src/api/types';

const screenshots = '../docs/qa/setup';
const kinds = ['categories', 'tables', 'customer-types', 'expense-categories', 'vouchers', 'staff'] as const;
const labels = ['Categories', 'Pool tables', 'Customer types', 'Expense categories', 'Vouchers', 'Staff'];

test('setup lifecycle, hourly details and audit disclosure in both sizes and themes', async ({ page, signIn, hall }) => {
  test.setTimeout(180_000);
  void hall;
  await mkdir(screenshots, { recursive: true });
  const owner = await apiAs(OWNER);
  const counter = await apiAs(COUNTER);
  const create = async (kind: typeof kinds[number], name: string) => {
    const path = kind === 'vouchers' ? 'voucher-batches' : kind === 'staff' ? 'users' : kind;
    const data = kind === 'tables' ? { name, ratePerHour: 200, isActive: true }
      : kind === 'vouchers' ? { note: name, hours: 2, quantity: 3, expiresOn: '2027-12-31' }
      : kind === 'staff' ? { username: name.toLowerCase().replaceAll(' ', '-'), fullName: name, role: 'EMPLOYEE', temporaryPassword: 'temporary-pass-1' }
      : { name };
    return apiPost<{ id: string }>(owner, `/api/v1/${path}`, data);
  };
  const unused = new Map<string, string>();
  for (const kind of kinds) {
    unused.set(kind, (await create(kind, `Unused ${kind}`)).id);
    const retired = await create(kind, `Archived ${kind}`);
    await apiPost(owner, `/api/v1/setup/${kind}/${retired.id}/archive`, {});
  }
  // Real activity references the table, customer type, counter, voucher and expense category.
  const table = await apiPost<PoolTable>(owner, '/api/v1/tables', { name: 'Tournament table', ratePerHour: 200 });
  const customer = await apiPost<CustomerType>(owner, '/api/v1/customer-types', { name: 'League players' });
  const session = await apiPost<Session>(counter, '/api/v1/sessions', { tableId: table.id, customerTypeId: customer.id });
  backdateSession(session.id, 90);
  await apiPost(counter, `/api/v1/sessions/${session.id}/close`, {});
  const voucher = await apiPost<VoucherBatch>(owner, '/api/v1/voucher-batches', { note: 'League prizes', hours: 2, quantity: 3, expiresOn: '2027-12-31' });
  await apiPost(counter, `/api/v1/bills/${session.billId}/voucher`, { code: voucher.codes![0]!.code });
  const expense = await apiPost<{ id: string }>(owner, '/api/v1/expense-categories', { name: 'Maintenance' });
  await apiPost(counter, '/api/v1/expenses', { expenseCategoryId: expense.id, amount: 350, paidFromDrawer: true, note: 'Table brush and chalk' });
  await owner.dispose();
  await counter.dispose();

  for (const width of [400, 1280]) {
    for (const theme of ['light', 'dark']) {
      await page.setViewportSize({ width, height: width === 400 ? 800 : 720 });
      await page.addInitScript((value) => localStorage.setItem('supreme.theme.admin', value), theme);
      await signIn(OWNER);
      for (let i = 0; i < kinds.length; i++) {
        const kind = kinds[i]!;
        await page.goto(`/admin/${kind}`);
        await expect(page.getByRole('heading', { name: labels[i], exact: true })).toBeVisible();
        await expect(page.getByRole('button', { name: 'Checking…' })).toHaveCount(0);
        await page.getByLabel(/Show archived/).check();
        await expect(page.getByRole('button', { name: 'Restore', exact: true }).first()).toBeVisible();
        await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
        await page.screenshot({ path: `${screenshots}/${kind}-${theme}-${width}.png`, fullPage: true, animations: 'disabled' });
        if (kind === 'tables') {
          const row = page.getByRole('listitem').filter({ hasText: 'Tournament table' });
          await expect(row).toContainText('₱200.00 / hour');
          await expect(row.getByText(/Billed by the minute/)).not.toBeVisible();
          await row.getByLabel('Rate details for Tournament table').click();
          await expect(row.getByText(/Billed by the minute at ₱3.3333/)).toBeVisible();
          await row.scrollIntoViewIfNeeded();
          await page.screenshot({ path: `${screenshots}/rate-details-${theme}-${width}.png`, animations: 'disabled' });
        }
      }
      await page.goto('/admin/audit');
      await expect(page.getByRole('columnheader', { name: 'When', exact: true })).toBeVisible();
      await expect(page.getByRole('columnheader', { name: 'Before', exact: true })).toHaveCount(0);
      const detail = page.getByRole('button', { name: /Details for/ }).first();
      await detail.click();
      await expect(page.getByRole('button', { name: /Hide for/ }).first()).toHaveAttribute('aria-expanded', 'true');
      await expect(page.getByRole('columnheader', { name: 'Before', exact: true })).toBeVisible();
      await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
      await page.screenshot({ path: `${screenshots}/audit-${theme}-${width}.png`, animations: 'disabled', fullPage: false });
      await page.getByRole('button', { name: /Hide for/ }).first().click();
      await expect(page.getByRole('columnheader', { name: 'Before', exact: true })).toHaveCount(0);
    }
  }

  for (const kind of kinds) {
    await page.goto(`/admin/${kind}`);
    const row = page.getByRole(kind === 'staff' ? 'row' : 'listitem').filter({ hasText: `Unused ${kind}` });
    await row.getByRole('button', { name: 'Delete', exact: true }).click();
    await expect(page.getByRole('dialog')).toContainText('cannot be undone');
    await page.getByRole('button', { name: 'Delete permanently', exact: true }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(row).toHaveCount(0);
    const remaining = (await (await page.request.get(`/api/v1/setup/${kind}`)).json()).data as SetupItem[];
    expect(remaining.some((item) => item.id === unused.get(kind))).toBe(false);
    await page.getByLabel(/Show archived/).check();
    const archived = page.getByRole('listitem').filter({ hasText: `Archived ${kind}` });
    await archived.getByRole('button', { name: 'Restore', exact: true }).click();
    await expect(page.getByRole(kind === 'staff' ? 'row' : 'listitem').filter({ hasText: `Archived ${kind}` }).getByRole('button', { name: 'Delete', exact: true })).toBeVisible();
  }
  await page.goto('/admin/customer-types');
  const used = page.getByRole('listitem').filter({ hasText: 'League players' });
  await used.getByRole('button', { name: 'Archive', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Archive', exact: true }).click();
  await expect(used).toHaveCount(0);
  await page.getByLabel(/Show archived/).check();
  await used.getByRole('button', { name: 'Restore', exact: true }).click();
  await expect(used.getByRole('button', { name: 'Archive', exact: true })).toBeVisible();
});
