import { test, expect, OWNER } from './fixtures';
import { batches, codesFor, voucherFixtures } from './pr12-fixtures';

test('capture voucher cards and codes in both themes and widths', async ({ page, signIn }) => {
  test.skip(!process.env.PR12_CAPTURE, 'Opt-in before/after evidence');
  await voucherFixtures(page);
  await signIn(OWNER);
  for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(t => localStorage.setItem('supreme.theme.admin', t), theme);
    await page.goto('/admin/vouchers');
    await expect(page.getByText('Facebook giveaway', { exact: false })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Checking…', exact: true })).toHaveCount(0);
    const prefix = `../docs/qa/pr12/${process.env.PR12_CAPTURE}-${theme}-${width}`;
    await page.screenshot({ path: `${prefix}-batches.png`, fullPage: true, animations: 'disabled' });
    await page.getByRole('button', { name: 'Codes', exact: true }).first().click();
    await expect(page.getByText('SB-000-ABC', { exact: true })).toBeVisible();
    await page.screenshot({ path: `${prefix}-codes.png`, animations: 'disabled' });
    if (process.env.PR12_CAPTURE === 'after') {
      await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
      await page.keyboard.press('Escape');
      await page.getByRole('button', { name: 'Codes', exact: true }).nth(3).click();
      await expect(page.getByRole('dialog').getByText('Expired', { exact: true })).toHaveCount(2);
      await page.screenshot({ path: `${prefix}-expired-codes.png`, animations: 'disabled' });
      await page.emulateMedia({ media: 'print' });
      await page.screenshot({ path: `${prefix}-print.png`, fullPage: true });
      await page.emulateMedia({ media: 'screen' });
      await page.keyboard.press('Escape');
      await page.route('**/api/v1/voucher-batches', route => {
        if (route.request().method() !== 'POST') return route.fallback();
        const batch = { ...batches[0], quantity: 3, outstanding: 3, redeemed: 0, issued: 3 };
        return route.fulfill({ json: { success: true, data: { ...batch, codes: codesFor(batch) } } });
      });
      await page.getByRole('button', { name: 'New batch' }).click();
      await page.getByLabel('How many').fill('3');
      await page.getByLabel('Expires on').fill('2026-11-14');
      await page.getByRole('button', { name: 'Generate', exact: true }).click();
      await expect(page.getByRole('dialog', { name: '3 codes generated' })).toBeVisible();
      await expect(page.getByRole('dialog').getByText('Outstanding', { exact: true })).toHaveCount(3);
      await page.screenshot({ path: `${prefix}-generated-codes.png`, animations: 'disabled' });
    }
  }
});
