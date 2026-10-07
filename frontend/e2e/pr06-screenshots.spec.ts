import { test, expect, OWNER } from './fixtures';
import { catalogFixtures, picture } from './pr06-fixtures';

test('capture product catalog and creation at both widths and themes', async ({ page, signIn }) => {
  test.skip(!process.env.PR06_CAPTURE, 'Opt-in before/after evidence');
  test.setTimeout(120_000);
  await catalogFixtures(page);
  await signIn(OWNER);
  for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(t => localStorage.setItem('supreme.theme.admin', t), theme);
    await page.goto('/admin/products');
    await page.getByLabel('Include archived').check();
    await expect(page.getByRole('cell', { name: /Seasonal snack/ })).toBeVisible();
    const prefix = `../docs/qa/pr06/${process.env.PR06_CAPTURE}-${theme}-${width}`;
    await page.screenshot({ path: `${prefix}-catalog.png`, fullPage: true, animations: 'disabled' });
    await page.getByRole('button', { name: 'New product', exact: true }).click();
    await page.getByRole('dialog').getByLabel('Name', { exact: true }).fill('Bottled water 1.5L');
    await page.getByRole('dialog').getByLabel('Selling price').fill('60');
    await page.screenshot({ path: `${prefix}-new.png`, animations: 'disabled' });
    if (process.env.PR06_CAPTURE === 'after') {
      await page.getByLabel('Choose product picture').setInputFiles(picture);
      await expect(page.getByRole('img', { name: 'Selected product picture' })).toBeVisible();
      await page.screenshot({ path: `${prefix}-preview.png`, animations: 'disabled' });
    }
    await page.getByRole('button', { name: 'Close', exact: true }).click();
  }
});
