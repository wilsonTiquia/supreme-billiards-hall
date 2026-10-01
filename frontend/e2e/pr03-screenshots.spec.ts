import { test, expect, OWNER } from './fixtures';
import { analyticsFixtures } from './pr03-fixtures';

test('capture PR-03 analytics at both widths and themes', async ({ page, signIn }) => {
  test.skip(!process.env.PR03_CAPTURE, 'Opt-in before/after evidence');
  test.setTimeout(120_000);
  await analyticsFixtures(page);
  await signIn(OWNER);
  for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(t => localStorage.setItem('supreme.theme.admin', t), theme);
    for (const screen of ['dashboard', 'reports']) {
      await page.goto(screen === 'dashboard' ? '/dashboard?date=2026-09-25' : '/admin/reports?from=2026-09-01&to=2026-09-30');
      await expect(page.getByText('San Miguel Pale Pilsen — celebration bucket with snacks and extra ice').first()).toBeVisible();
      const path = `../docs/qa/pr03/${process.env.PR03_CAPTURE}-${screen}-${theme}-${width}.png`;
      await page.screenshot({ path, fullPage: true, animations: 'disabled' });
      if (process.env.PR03_CAPTURE === 'after') {
        const chart = page.locator('.analytics-chart').first();
        await chart.getByLabel('Select night').selectOption('0');
        await expect(chart.getByRole('tooltip')).toBeVisible();
        await chart.locator('..').screenshot({ path: `../docs/qa/pr03/after-${screen}-tooltip-${theme}-${width}.png` });
        if (screen === 'dashboard') {
          const hour = page.locator('.analytics-hour-chart');
          await hour.getByLabel('Select hour').selectOption('0');
          await hour.locator('..').screenshot({ path: `../docs/qa/pr03/after-hour-tooltip-${theme}-${width}.png` });
        }
        if (screen === 'reports' && width === 1280 && theme === 'light') {
          await page.emulateMedia({ media: 'print' });
          await chart.locator('..').screenshot({ path: '../docs/qa/pr03/after-report-print.png' });
          await page.emulateMedia({ media: 'screen' });
        }
      }
    }
  }
});
