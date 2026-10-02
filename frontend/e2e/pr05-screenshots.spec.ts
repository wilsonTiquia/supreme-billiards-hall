import { test, expect, OWNER } from './fixtures';
import { reportFixtures } from './pr05-fixtures';

test('capture report summary and expanded detail at both widths and themes', async ({ page, signIn }) => {
  test.skip(!process.env.PR05_CAPTURE, 'Opt-in before/after evidence');
  test.setTimeout(120_000);
  await reportFixtures(page);
  await signIn(OWNER);
  for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(t => localStorage.setItem('supreme.theme.admin', t), theme);
    await page.goto('/admin/reports?from=2026-09-01&to=2026-09-27');
    await expect(page.getByText('San Miguel Pale Pilsen — celebration bucket with snacks and extra ice').first()).toBeVisible();
    for (const title of ['Bills and averages', 'Given away', 'Drawer', 'Still owed']) {
      await page.getByRole('button', { name: new RegExp(`^${title}`) }).click();
    }
    const prefix = `../docs/qa/pr05/${process.env.PR05_CAPTURE}-${theme}-${width}`;
    await page.screenshot({ path: `${prefix}-full.png`, fullPage: true, animations: 'disabled' });
    await page.locator('body').evaluate(el => el.ownerDocument.defaultView!.scrollTo(0, 0));
    await page.screenshot({ path: `${prefix}-summary.png`, animations: 'disabled' });
    if (process.env.PR05_CAPTURE === 'after' && width === 1280 && theme === 'light') {
      await page.emulateMedia({ media: 'print' });
      await page.pdf({ path: '../docs/qa/pr05/after-print.pdf', format: 'A4', margin: { top: '12mm', bottom: '12mm', left: '12mm', right: '12mm' }, printBackground: true });
      await page.emulateMedia({ media: 'screen' });
    }
  }
});
