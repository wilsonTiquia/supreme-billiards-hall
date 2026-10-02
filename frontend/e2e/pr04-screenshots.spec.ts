import { test, expect, OWNER } from './fixtures';
import { nightCheckFixtures, selectedNight } from './pr04-fixtures';

test('capture PR-04 night check and definitions at both widths and themes', async ({ page, signIn }) => {
  test.skip(!process.env.PR04_CAPTURE, 'Opt-in before/after evidence');
  test.setTimeout(120_000);
  await nightCheckFixtures(page);
  await signIn(OWNER);
  for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(t => localStorage.setItem('supreme.theme.admin', t), theme);
    await page.goto(`/dashboard?date=${selectedNight}`);
    await expect(page.getByText('2 bills need checkout')).toBeVisible();
    const prefix = `../docs/qa/pr04/${process.env.PR04_CAPTURE}-${theme}-${width}`;
    await page.screenshot({ path: `${prefix}-night.png`, animations: 'disabled' });
    await page.getByRole('button', { name: 'How these numbers are worked out' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.screenshot({ path: `${prefix}-definitions.png`, animations: 'disabled' });
    await page.keyboard.press('Escape');
    if (process.env.PR04_CAPTURE === 'after') {
      const checks = page.getByRole('region', { name: 'Night check' });
      await checks.screenshot({ path: `${prefix}-checks.png`, animations: 'disabled' });
      await checks.locator('summary').click();
      await checks.screenshot({ path: `${prefix}-other-checks.png`, animations: 'disabled' });
    }
  }
});

for (const scenario of ['clear', 'error'] as const) {
  test(`capture PR-04 ${scenario} checks`, async ({ page, signIn }) => {
    test.skip(process.env.PR04_CAPTURE !== 'after', 'Opt-in after evidence');
    await nightCheckFixtures(page, scenario);
    await signIn(OWNER);
    await page.goto(`/dashboard?date=${selectedNight}`);
    const checks = page.getByRole('region', { name: 'Night check' });
    await expect(checks.getByRole('heading', { name: scenario === 'clear' ? 'Cash balanced' : 'Some checks are unavailable' })).toBeVisible();
    await checks.screenshot({ path: `../docs/qa/pr04/after-${scenario}.png`, animations: 'disabled' });
  });
}
