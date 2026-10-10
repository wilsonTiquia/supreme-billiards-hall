import { test, expect, OWNER } from './fixtures';
import { debtFixtures } from './pr16-fixtures';

test('capture Unsettled and End of day at both widths and themes', async ({ page, signIn }) => {
  test.skip(!process.env.PR16_CAPTURE, 'Opt-in before/after evidence');
  await debtFixtures(page);
  await signIn(OWNER);
  for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(t => localStorage.setItem('supreme.theme.pos', t), theme);
    for (const [route, screen] of [['/unsettled', 'unsettled'], ['/end-of-day', 'end-of-day']]) {
      await page.goto(route);
      await expect(page.getByText('Jun and Marco - pays next week', { exact: true })).toBeVisible();
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
      await page.screenshot({ path: `../docs/qa/pr16/${process.env.PR16_CAPTURE}-${theme}-${width}-${screen}.png`, fullPage: true, animations: 'disabled' });
      if (screen === 'end-of-day' && process.env.PR16_CAPTURE === 'after') {
        await page.getByRole('button', { name: /20 earlier nights/ }).click();
        await page.screenshot({ path: `../docs/qa/pr16/after-${theme}-${width}-earlier-nights.png`, fullPage: true, animations: 'disabled' });
      }
    }
  }
});
