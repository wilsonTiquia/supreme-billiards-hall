import { test, expect, OWNER } from './fixtures';

// Run before editing and after implementation against the same seeded scratch hall.
test('capture shell in both themes and viewport sizes', async ({ page, signIn }) => {
  test.skip(!process.env.PR02_CAPTURE, 'Opt-in before/after evidence');
  test.setTimeout(120_000);
  await signIn(OWNER);
  for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: width === 400 ? 800 : 720 });
    await page.evaluate(value => {
      localStorage.setItem('supreme.theme.admin', value);
      localStorage.setItem('supreme.theme.pos', value);
    }, theme);
    for (const screen of ['admin', 'floor']) {
      await page.goto(screen === 'admin' ? '/admin/tables' : '/floor');
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
      await expect(screen === 'admin' ? page.locator('main').getByText('Table 1', { exact: true }) : page.locator('.figure-table-number').first()).toBeVisible();
      const capture = async (variant: string) => page.screenshot({ animations: 'disabled',
        path: `../docs/qa/pr02/${process.env.PR02_CAPTURE}-${variant}-${theme}-${width}.png` });
      await capture(screen);
      if (width === 400) {
        await page.getByLabel('Open the menu').click();
        await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible();
        await capture(`${screen}-drawer`);
        await page.keyboard.press('Escape');
      } else {
        await page.getByLabel('Collapse the menu').click();
        await capture(`${screen}-collapsed`);
        await page.getByLabel('Expand the menu').click();
      }
    }
  }
});
