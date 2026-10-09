import { test, expect, OWNER } from './fixtures';
import { batches, codesFor, voucherFixtures } from './pr12-fixtures';

test('capture voucher cards and codes in both themes and widths', async ({ page, signIn }) => {
  test.skip(!process.env.PR13_CAPTURE, 'Opt-in before/after evidence');
  await voucherFixtures(page);
  if (process.env.PR13_CAPTURE === 'after') {
    const archived = { ...batches[1], id: 'archived', archivedAt: '2026-10-09T08:00:00Z' };
    const cancelled = { ...batches[0], id: 'cancelled', note: 'Previously archived giveaway — cancellation preserved', archivedAt: '2026-09-30T08:00:00Z', cancelledAt: '2026-09-30T08:00:00Z', cancelled: 6, outstanding: 0 };
    await page.route('**/api/v1/voucher-batches?includeArchived=true', route => route.fulfill({ json: { success: true, data: [archived, cancelled] } }));
    await page.route('**/api/v1/vouchers?batchId=cancelled', route => route.fulfill({ json: { success: true, data: codesFor(cancelled).map(v => v.status === 'OUTSTANDING' ? { ...v, status: 'CANCELLED' } : v) } }));
  }
  await signIn(OWNER);
  for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(t => localStorage.setItem('supreme.theme.admin', t), theme);
    await page.goto('/admin/vouchers');
    await expect(page.getByText('Facebook giveaway', { exact: false })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Checking…', exact: true })).toHaveCount(0);
    const prefix = `../docs/qa/pr13/${process.env.PR13_CAPTURE}-${theme}-${width}`;
    await page.screenshot({ path: `${prefix}-batches.png`, fullPage: true, animations: 'disabled' });
    if (process.env.PR13_CAPTURE === 'after') {
      await page.getByRole('button', { name: 'Actions for Facebook giveaway' }).click();
      await page.screenshot({ path: `${prefix}-menu.png`, animations: 'disabled' });
      await page.getByRole('menuitem', { name: 'Archive', exact: true }).click();
      await expect(page.getByRole('dialog')).toContainText('Valid codes still work');
      await page.screenshot({ path: `${prefix}-archive.png`, animations: 'disabled' });
      await page.keyboard.press('Escape');
      await page.getByRole('button', { name: 'Actions for Facebook giveaway' }).click();
      await page.getByRole('menuitem', { name: 'Cancel unused codes', exact: true }).click();
      await expect(page.getByRole('dialog')).toContainText('permanently stop working');
      await page.screenshot({ path: `${prefix}-cancel.png`, animations: 'disabled' });
      await page.keyboard.press('Escape');
      await page.getByLabel('Show archived', { exact: true }).check();
      await expect(page.getByText('Previously archived giveaway — cancellation preserved', { exact: true })).toBeVisible();
      await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
      await page.screenshot({ path: `${prefix}-archived.png`, fullPage: true, animations: 'disabled' });
      await page.getByRole('button', { name: /Actions for Tournament prizes/ }).click();
      await page.getByRole('menuitem', { name: 'Restore', exact: true }).click();
      await expect(page.getByRole('dialog')).toContainText('No disabled codes will be reactivated');
      await page.screenshot({ path: `${prefix}-restore.png`, animations: 'disabled' });
      await page.keyboard.press('Escape');
      await page.getByRole('button', { name: 'Codes', exact: true }).nth(1).click();
      await expect(page.getByRole('dialog').getByText('Cancelled · cannot be redeemed')).toHaveCount(6);
      await page.getByRole('dialog').getByText('Cancelled · cannot be redeemed').first().scrollIntoViewIfNeeded();
      await page.screenshot({ path: `${prefix}-cancelled-codes.png`, animations: 'disabled' });
    }
  }
});
