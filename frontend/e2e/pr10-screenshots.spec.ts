import { test, expect, OWNER } from './fixtures';
import { auditFixtures } from './pr10-fixtures';

test('capture audit rows in both themes and widths', async ({ page, signIn }) => {
  test.skip(!process.env.PR10_CAPTURE, 'Opt-in before/after evidence');
  test.setTimeout(120_000);
  await auditFixtures(page);
  await signIn(OWNER);
  for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: width === 400 ? 800 : 900 });
    await page.evaluate(t => localStorage.setItem('supreme.theme.admin', t), theme);
    await page.goto('/admin/audit');
    const table = page.getByRole('table', { name: 'Audit log', exact: true });
    await expect(table).toBeVisible();
    const prefix = `../docs/qa/pr10/${process.env.PR10_CAPTURE}-${theme}-${width}`;
    await page.screenshot({ path: `${prefix}-collapsed.png`, animations: 'disabled' });
    await table.getByRole('button', { name: /Details for Product updated San Miguel/ }).click();
    await expect(table.getByRole('columnheader', { name: 'Before', exact: true })).toBeVisible();
    await page.screenshot({ path: `${prefix}-expanded.png`, animations: 'disabled' });
    if (process.env.PR10_CAPTURE === 'after') {
      await table.getByRole('button', { name: /Details for Product updated Tournament/ }).click();
      await page.getByRole('region', { name: /Product updated Tournament/ }).scrollIntoViewIfNeeded();
      await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
      await page.screenshot({ path: `${prefix}-long-payload.png`, animations: 'disabled' });
    }
  }
});
