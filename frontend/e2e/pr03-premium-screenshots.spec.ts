import { test, expect, OWNER } from './fixtures';

test('capture table classification administration in both themes and widths', async ({ page, signIn }) => {
  test.skip(!process.env.PR03_PREMIUM_CAPTURE, 'Opt-in screenshot evidence');
  await signIn(OWNER);
  for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(t => localStorage.setItem('supreme.theme.admin', t), theme);
    await page.goto('/admin/tables');
    const row = page.getByRole('listitem').filter({ hasText: 'Table 1' });
    await expect(row).toBeVisible();
    await page.screenshot({ path: `../docs/qa/pr03/${process.env.PR03_PREMIUM_CAPTURE}-tables-${theme}-${width}.png`, fullPage: true });
    await row.getByRole('button', { name: 'Edit', exact: true }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    if (process.env.PR03_PREMIUM_CAPTURE === 'after') await expect(page.getByLabel('Premium table', { exact: true })).toBeChecked();
    await page.screenshot({ path: `../docs/qa/pr03/${process.env.PR03_PREMIUM_CAPTURE}-table-edit-${theme}-${width}.png` });
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  }
});
