import { test, expect, OWNER } from './fixtures';
import { listFixtures } from './pr01-fixtures';

test('capture pagination at both widths and themes', async ({ page, signIn }) => {
  test.setTimeout(120_000);
  test.skip(!process.env.PR01_CAPTURE, 'Opt-in evidence capture');
  await listFixtures(page);
  await signIn(OWNER);
  for (const width of [400, 1280]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: 800 });
    await page.evaluate(t => localStorage.setItem('supreme.theme.admin', t), theme);
    for (const screen of ['sales', 'audit', 'reports']) {
      await page.goto(screen === 'reports' ? '/admin/reports?from=2026-08-01&to=2026-08-31' : `/admin/${screen}?date=2026-09-29`);
      const pager = screen === 'reports' ? page.getByRole('navigation', { name: 'Report pages' })
        : page.getByText('Page 1 of 3', { exact: false });
      await expect(pager).toBeVisible();
      await pager.scrollIntoViewIfNeeded();
      await page.screenshot({ animations: 'disabled', path: `../docs/qa/pr01/${process.env.PR01_CAPTURE}-${screen}-${theme}-${width}.png` });
    }
  }
});
