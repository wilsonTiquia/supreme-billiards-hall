import { test, expect, OWNER } from './fixtures';
import { nightCheckFixtures, selectedNight } from './pr04-fixtures';

const url = `/dashboard?date=${selectedNight}`;

test('urgent checks stay visible; stock expands with keyboard and links retain their night and destination', async ({ page, signIn }) => {
  await nightCheckFixtures(page);
  await signIn(OWNER);
  await page.goto(url);
  const checks = page.getByRole('region', { name: 'Night check' });
  await expect(checks.getByRole('status')).toHaveText('Needs attention');
  await expect(checks.getByRole('heading', { name: 'Not counted yet' })).toBeVisible();
  await expect(checks.getByText('2 bills need checkout')).toBeVisible();
  await expect(checks.getByText('2 other nights need a drawer count')).toBeVisible();
  await expect(checks.getByText(/owed across 5 bills/)).toBeVisible();
  await expect(checks.getByRole('link', { name: 'Count the drawer' }).first()).toHaveAttribute('href', '/end-of-day?date=2026-09-25');
  await expect(checks.getByRole('link', { name: 'Count the drawer' }).last()).toHaveAttribute('href', '/end-of-day?date=2026-09-20');
  await expect(checks.getByRole('link', { name: 'Finish checkout' })).toHaveAttribute('href', '/end-of-day');
  await expect(checks.getByRole('link', { name: 'Chase unpaid' })).toHaveAttribute('href', '/unsettled');
  await expect(checks.getByRole('link', { name: 'View stock' })).toBeHidden();
  await checks.locator('summary').focus();
  await page.keyboard.press('Enter');
  await expect(checks.getByRole('link', { name: 'View stock' })).toBeVisible();
  await expect(checks.locator('li')).toHaveCount(12);
  await expect(checks.locator('li').first()).toContainText('extra ice (-3)');
  await expect(checks.getByRole('link', { name: 'View stock' })).toHaveAttribute('href', '/admin/stock');
  await page.keyboard.press('Space');
  await expect(checks.getByRole('link', { name: 'View stock' })).toBeHidden();
  await expect(page.getByRole('link', { name: 'View report', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Previous night' }).click();
  await expect(page).toHaveURL(/date=2026-09-24/);
  await expect(checks.getByRole('link', { name: 'Count the drawer' }).first()).toHaveAttribute('href', '/end-of-day?date=2026-09-24');
  await checks.getByRole('link', { name: 'Count the drawer' }).first().click();
  await expect(page).toHaveURL(/\/end-of-day\?date=2026-09-24/);
});

for (const scenario of ['clear', 'other', 'running', 'recount', 'stale'] as const) {
  test(`${scenario} night retains the correct drawer message and visible follow-up`, async ({ page, signIn }) => {
    await nightCheckFixtures(page, scenario);
    await signIn(OWNER);
    await page.goto(url);
    const checks = page.getByRole('region', { name: 'Night check' });
    if (scenario === 'clear' || scenario === 'other') {
      await expect(checks.getByRole('status')).toHaveText('No financial follow-up found');
      await expect(checks.getByRole('heading', { name: 'Cash balanced' })).toBeVisible();
      await expect(checks.getByRole('link', { name: 'View drawer count' })).toHaveAttribute('href', `/end-of-day?date=${selectedNight}`);
      await expect(checks.locator('summary')).toHaveCount(scenario === 'other' ? 1 : 0);
      if (scenario === 'other') {
        await checks.locator('summary').click();
        await expect(checks.getByRole('link', { name: 'View stock' })).toBeVisible();
      }
    } else {
      await expect(checks.getByRole('heading', { name: scenario === 'running' ? 'Not counted yet' : /Cash needs recounting/ })).toBeVisible();
      await expect(checks.getByRole('status')).not.toHaveText('No financial follow-up found');
      await expect(checks.getByRole('heading', { name: 'Cash balanced' })).toHaveCount(0);
      await expect(checks.getByRole('link', { name: 'Count the drawer' })).toBeVisible();
    }
  });
}

test('failed checks remain visibly unavailable instead of reporting a clear night', async ({ page, signIn }) => {
  await nightCheckFixtures(page, 'error');
  await signIn(OWNER);
  await page.goto(url);
  const checks = page.getByRole('region', { name: 'Night check' });
  await expect(checks.getByRole('heading', { name: 'Some checks are unavailable' })).toBeVisible();
  await expect(checks.getByRole('heading', { name: 'Cash check unavailable' })).toBeVisible();
  await expect(checks).not.toContainText('No financial follow-up found');
  await expect(checks.getByRole('link', { name: 'Review end of day' })).toHaveAttribute('href', `/end-of-day?date=${selectedNight}`);
});

test('pending checks cannot appear clear before they finish', async ({ page, signIn }) => {
  await nightCheckFixtures(page, 'loading');
  await signIn(OWNER);
  await page.goto(url);
  const checks = page.getByRole('region', { name: 'Night check' });
  await expect(checks.getByText('Checking unpaid bills and earlier drawer counts…')).toBeVisible();
  await expect(checks).not.toContainText('No financial follow-up found');
  await expect(checks.getByRole('status').first()).toHaveText('No financial follow-up found');
});

for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
  test(`definitions and dense checks are usable at ${width}px in ${theme}`, async ({ page, signIn }) => {
    await page.setViewportSize({ width, height: 900 });
    await nightCheckFixtures(page);
    await signIn(OWNER);
    await page.evaluate(t => localStorage.setItem('supreme.theme.admin', t), theme);
    await page.goto(url);
    const trigger = page.getByRole('button', { name: 'How these numbers are worked out' });
    const dialog = page.getByRole('dialog', { name: 'How these numbers are worked out' });
    await trigger.click();
    await expect(dialog).toBeVisible();
    const close = dialog.getByRole('button', { name: 'Close definitions' });
    await expect(close).toBeFocused();
    for (const key of ['Tab', 'Shift+Tab']) {
      await page.keyboard.press(key);
      // Native dialogs may focus their scroll region, but never the background controls.
      await expect.poll(() => dialog.evaluate(el => el.contains(el.ownerDocument.activeElement))).toBe(true);
    }
    const box = (await dialog.boundingBox())!;
    expect(box.x).toBeGreaterThanOrEqual(15);
    expect(box.x + box.width).toBeLessThanOrEqual(width - 15);
    expect(box.y + box.height).toBeLessThanOrEqual(885);
    expect(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true);
    if (width === 1280) {
      const anchor = (await trigger.boundingBox())!;
      expect(Math.abs(box.x - anchor.x)).toBeLessThan(40);
    }
    await dialog.getByText('Every unpaid bill across all nights, as of right now.').scrollIntoViewIfNeeded();
    await expect(dialog.getByText('Every unpaid bill across all nights, as of right now.')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await expect(trigger).toBeFocused();
    await trigger.click();
    await close.click();
    await expect(trigger).toBeFocused();
    await trigger.click();
    await page.mouse.click(4, 4);
    await expect(dialog).toHaveCount(0);
    await expect(trigger).toBeFocused();
    await trigger.click();
    await page.setViewportSize({ width: 400, height: 480 });
    const resized = (await dialog.boundingBox())!;
    expect(resized.x + resized.width).toBeLessThanOrEqual(385);
    expect(resized.y + resized.height).toBeLessThanOrEqual(465);
    await close.click();
    await page.getByRole('region', { name: 'Night check' }).locator('summary').click();
    expect(await page.locator('html').evaluate(el => el.scrollWidth)).toBeLessThanOrEqual(400);
    const stock = page.getByRole('link', { name: 'View stock' });
    await stock.scrollIntoViewIfNeeded();
    await expect(stock).toBeVisible();
    expect((await stock.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  });
}
