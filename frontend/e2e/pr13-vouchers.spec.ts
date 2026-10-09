import { test, expect, OWNER } from './fixtures';
import { batches, voucherFixtures } from './pr12-fixtures';

const emptyMessage = 'No visible voucher batches. Generate a new batch or check Show archived.';

test('archive, cancel and restore persist independently through reloads', async ({ page, signIn }) => {
  await signIn(OWNER);
  const response = await page.request.post('/api/v1/voucher-batches', { data: {
    hours: 2, quantity: 3, expiresOn: '2030-12-31', note: 'PR13 lifecycle draw',
  } });
  expect(response.ok()).toBe(true);
  await page.goto('/admin/vouchers');
  const batch = page.getByRole('listitem').filter({ has: page.getByRole('heading', { name: 'PR13 lifecycle draw', exact: true }) });
  const menu = () => batch.getByRole('button', { name: 'Actions for PR13 lifecycle draw' });
  await menu().focus();
  await page.keyboard.press('ArrowDown');
  await expect(page.getByRole('menuitem', { name: 'Archive', exact: true })).toBeFocused();
  await page.keyboard.press('Enter');
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('Valid codes still work');
  await page.keyboard.press('Escape');
  await expect(menu()).toBeFocused();
  await menu().click();
  await page.getByRole('menuitem', { name: 'Archive', exact: true }).click();
  await dialog.getByRole('button', { name: 'Archive', exact: true }).click();
  await expect(batch).toHaveCount(0);
  await page.reload();
  await expect(batch).toHaveCount(0);
  await page.getByLabel('Show archived', { exact: true }).check();
  await expect(batch).toContainText('Archived');
  await expect(batch.locator('dd')).toHaveText(['3', '0', '0']);
  await batch.getByRole('button', { name: 'Codes', exact: true }).click();
  await expect(dialog.getByText('Outstanding', { exact: true })).toHaveCount(3);
  await page.keyboard.press('Escape');
  await menu().click();
  await page.getByRole('menuitem', { name: 'Cancel unused codes', exact: true }).click();
  await expect(dialog).toContainText('permanently stop working');
  await expect(dialog).toContainText('Restoring the batch will not undo cancellation');
  await dialog.getByRole('button', { name: 'Keep unchanged' }).click();
  await expect(batch.locator('dd')).toHaveText(['3', '0', '0']);
  await menu().click();
  await page.getByRole('menuitem', { name: 'Cancel unused codes', exact: true }).click();
  await dialog.getByRole('button', { name: 'Cancel unused codes permanently' }).click();
  await expect(batch.locator('dd')).toHaveText(['0', '0', '0', '3']);
  await menu().click();
  await expect(page.getByRole('menuitem', { name: /Cancel unused codes/ })).toHaveAttribute('aria-disabled', 'true');
  await page.getByRole('menuitem', { name: 'Restore', exact: true }).click();
  await expect(dialog).toContainText('No disabled codes will be reactivated');
  await dialog.getByRole('button', { name: 'Restore', exact: true }).click();
  await page.reload();
  await expect(batch.locator('dd')).toHaveText(['0', '0', '0', '3']);
  await expect(batch).not.toContainText('Archived');
  await batch.getByRole('button', { name: 'Codes', exact: true }).click();
  await expect(dialog.getByText('Cancelled · cannot be redeemed', { exact: true })).toHaveCount(3);
});

test('failed cancellation stays reviewable and pending confirmation cannot submit twice', async ({ page, signIn }) => {
  await voucherFixtures(page);
  await signIn(OWNER);
  let finish: (() => void) | undefined;
  const held = new Promise<void>(resolve => { finish = resolve; });
  let calls = 0;
  await page.route('**/api/v1/voucher-batches/partial/cancel', async route => {
    calls++;
    await held;
    await route.fulfill({ status: 409, json: { success: false, message: 'Cancellation refused for this test.' } });
  });
  await page.goto('/admin/vouchers');
  await page.getByRole('button', { name: 'Actions for Facebook giveaway' }).click();
  await page.getByRole('menuitem', { name: 'Cancel unused codes', exact: true }).click();
  const dialog = page.getByRole('dialog');
  const submit = dialog.getByRole('button', { name: 'Cancel unused codes permanently' });
  await submit.click();
  await expect(submit).toBeDisabled();
  await expect(dialog.getByRole('button', { name: 'Keep unchanged' })).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeVisible();
  finish!();
  await expect(dialog).toContainText('Cancellation refused for this test.');
  await expect(submit).toBeEnabled();
  expect(calls).toBe(1);
  await dialog.getByRole('button', { name: 'Keep unchanged' }).click();
  await expect(page.getByRole('listitem').first().locator('dd')).toHaveText(['6', '6', '0']);
});

test('archived query has loading, empty and error states without stale active cards', async ({ page, signIn }) => {
  await voucherFixtures(page);
  await signIn(OWNER);
  let finish: (() => void) | undefined;
  const held = new Promise<void>(resolve => { finish = resolve; });
  await page.route('**/api/v1/voucher-batches?includeArchived=true', async route => {
    await held;
    await route.fulfill({ status: 503, json: { success: false, message: 'Archived batches unavailable.' } });
  });
  await page.goto('/admin/vouchers');
  await expect(page.getByText(batches[0].note!, { exact: true })).toBeVisible();
  await page.getByLabel('Show archived', { exact: true }).check();
  await expect(page.getByText('Loading voucher batches…')).toBeVisible();
  await expect(page.getByRole('list', { name: 'Voucher batches' })).toHaveCount(0);
  finish!();
  await expect(page.getByText('Archived batches unavailable.')).toBeVisible();
  await page.route('**/api/v1/voucher-batches*', route => route.fulfill({ json: { success: true, data: [] } }));
  await page.reload();
  await expect(page.getByText(emptyMessage)).toBeVisible();
  await page.getByLabel('Show archived', { exact: true }).check();
  await expect(page.getByText('No voucher batches.', { exact: true })).toBeVisible();
});
