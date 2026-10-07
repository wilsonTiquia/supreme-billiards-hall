import { test, expect, OWNER } from './fixtures';
import { receiptFixtures } from './pr09-fixtures';

test('capture Sales receipt and standalone receipt in both themes and widths', async ({ page, signIn }) => {
  test.skip(!process.env.PR09_CAPTURE, 'Opt-in before/after evidence');
  test.setTimeout(120_000);
  await receiptFixtures(page);
  await signIn(OWNER);
  for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: width === 400 ? 800 : 900 });
    await page.evaluate(t => {
      localStorage.setItem('supreme.theme.admin', t);
      localStorage.setItem('supreme.theme.pos', t);
    }, theme);
    await page.goto('/admin/sales?date=2026-09-29&page=1');
    await page.getByRole('link', { name: 'Receipt', exact: true }).first().click();
    await expect(page.getByText('Not an official receipt')).toBeVisible();
    await expect(page.getByText('Marco and friends', { exact: false })).toBeVisible();
    const prefix = `../docs/qa/pr09/${process.env.PR09_CAPTURE}-${theme}-${width}`;
    await page.screenshot({ path: `${prefix}-sales-receipt.png`, fullPage: process.env.PR09_CAPTURE === 'before', animations: 'disabled' });
    if (process.env.PR09_CAPTURE === 'after') {
      await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
      if (width === 400) {
        await page.getByRole('heading', { name: 'Notes', exact: true }).scrollIntoViewIfNeeded();
        await page.screenshot({ path: `${prefix}-sales-notes.png`, animations: 'disabled' });
      }
      await page.emulateMedia({ media: 'print' });
      await page.screenshot({ path: `${prefix}-print.png`, fullPage: true });
      await page.emulateMedia({ media: 'screen' });
    }
    await page.goto('/receipt/bill-1-0');
    await expect(page.getByText('Not an official receipt')).toBeVisible();
    await page.screenshot({ path: `${prefix}-standalone.png`, fullPage: true, animations: 'disabled' });
  }
});
