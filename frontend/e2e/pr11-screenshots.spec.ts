import { test, expect, OWNER } from './fixtures';
import { maintenanceFixtures } from './pr11-fixtures';

test('capture table and staff maintenance in both themes and widths', async ({ page, signIn }) => {
  test.skip(!process.env.PR11_CAPTURE, 'Opt-in before/after evidence');
  test.setTimeout(120_000);
  await maintenanceFixtures(page);
  await signIn(OWNER);
  for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(t => localStorage.setItem('supreme.theme.admin', t), theme);
    const prefix = `../docs/qa/pr11/${process.env.PR11_CAPTURE}-${theme}-${width}`;
    for (const kind of ['tables', 'staff']) {
      await page.goto(`/admin/${kind}`);
      await expect(kind === 'tables' ? page.getByLabel('Rate details for Practice table', { exact: true }) : page.getByText('Front Counter', { exact: true })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Checking…', exact: true })).toHaveCount(0);
      await page.screenshot({ path: `${prefix}-${kind}.png`, animations: 'disabled', fullPage: true });
      if (process.env.PR11_CAPTURE === 'after') {
        await page.getByRole('button', { name: `Actions for ${kind === 'tables' ? 'Table 1' : 'Front Counter'}`, exact: true }).click();
        await expect(page.getByRole('menuitem', { name: 'Archive', exact: true })).toBeVisible();
        await page.screenshot({ path: `${prefix}-${kind}-menu.png`, animations: 'disabled' });
        await page.keyboard.press('Escape');
      }
      if (kind === 'tables') {
        await page.getByLabel('Rate details for Table 1', { exact: true }).click();
        await expect(page.getByText(/Billed by the minute at/).first()).toBeVisible();
        await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
        await page.screenshot({ path: `${prefix}-rate-help.png`, animations: 'disabled' });
      }
    }
  }
});
