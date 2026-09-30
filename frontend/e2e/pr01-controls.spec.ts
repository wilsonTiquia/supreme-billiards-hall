import { test, expect } from '@playwright/test';

test('menu keyboard, disabled/hidden actions, navigation, dismissal and confirmations', async ({ page }) => {
  await page.goto('/e2e/controls/');
  const trigger = page.getByRole('button', { name: 'Actions for Table 1' });
  const menu = page.getByRole('menu');
  await trigger.click();
  await expect(menu).toBeVisible();
  await trigger.click();
  await expect(menu).toBeHidden();
  await trigger.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('menuitem', { name: 'Edit', exact: true })).toBeFocused();
  await expect(page.getByRole('menuitem', { name: 'Admin only' })).toHaveCount(0);
  await page.keyboard.press('ArrowDown');
  await expect(page.getByRole('menuitem', { name: 'Reset password' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(menu).toBeVisible();
  await expect(page.getByText('No action', { exact: true })).toBeVisible();
  await page.keyboard.press('End');
  await expect(page.getByRole('menuitem', { name: 'Archive', exact: true })).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(page.getByRole('menuitem', { name: 'Edit', exact: true })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(menu).toBeHidden();
  await expect(trigger).toBeFocused();
  await page.keyboard.press('ArrowUp');
  await expect(page.getByRole('menuitem', { name: 'Archive', exact: true })).toBeFocused();
  await page.keyboard.press('Home');
  await page.keyboard.press('v');
  await expect(page.getByRole('menuitem', { name: 'View receipt' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/#receipt$/);
  await expect(trigger).toBeFocused();
  await trigger.click();
  await page.getByRole('menuitem', { name: 'Archive', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Confirm archive' })).toBeFocused();
  await expect(page.getByText('No action', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Confirm archive' }).click();
  await expect(page.getByText('Archived', { exact: true })).toBeVisible();
  await trigger.click();
  await page.getByRole('button', { name: 'Outside control' }).click();
  await expect(menu).toBeHidden();
  await expect(page.getByRole('button', { name: 'Outside control' })).toBeFocused();
  await trigger.click();
  await page.keyboard.press('Tab');
  await expect(menu).toBeHidden();
  await expect(page.getByRole('button', { name: 'Toggle pending' })).toBeFocused();
  await trigger.click();
  await page.keyboard.press('Shift+Tab');
  await expect(page.getByRole('link', { name: 'Back to list' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/#destination$/);
});

test('pagination boundaries and pending prevent duplicate changes', async ({ page }) => {
  await page.goto('/e2e/controls/');
  const nav = page.getByRole('navigation', { name: 'Example pages' });
  const prev = nav.getByRole('button', { name: 'Previous' });
  const next = nav.getByRole('button', { name: 'Next' });
  await expect(prev).toBeDisabled();
  await next.click();
  await expect(nav).toContainText('Page 2 of 3');
  await page.getByRole('button', { name: 'Toggle pending' }).click();
  await expect(prev).toBeDisabled();
  await expect(next).toBeDisabled();
  await page.getByRole('button', { name: 'Toggle pending' }).click();
  await next.click();
  await expect(nav).toContainText('Page 3 of 3');
  await expect(next).toBeDisabled();
  await prev.click();
  await expect(nav).toContainText('Page 2 of 3');
});

test('touch opens, activates and dismisses menu in both themes and narrow viewports', async ({ browser }) => {
  const context = await browser.newContext({ hasTouch: true, viewport: { width: 400, height: 800 } });
  const page = await context.newPage();
  await page.goto(`http://localhost:${process.env.E2E_WEB_PORT ?? '5174'}/e2e/controls/`);
  for (const width of [400, 1280]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: 800 });
    await page.locator('html').evaluate((el, t) => { el.setAttribute('data-theme', t); }, theme);
    await page.getByRole('button', { name: 'Actions for Table 1' }).tap();
    const bounds = await page.getByRole('menu').boundingBox();
    expect(bounds!.x).toBeGreaterThanOrEqual(0);
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width);
    if (process.env.PR01_CAPTURE) await page.screenshot({ animations: 'disabled', path: `../docs/qa/pr01/after-controls-${theme}-${width}.png` });
    await page.getByRole('menuitem', { name: 'Edit', exact: true }).tap();
    await expect(page.getByText('Edited', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Actions for Table 1' }).tap();
    await page.getByRole('heading').tap();
    await expect(page.getByRole('menu')).toBeHidden();
  }
  await context.close();
});


test('long menus fit a short phone viewport and keyboard reaches the last action', async ({ page }) => {
  await page.setViewportSize({ width: 400, height: 480 });
  await page.goto('/e2e/controls/');
  await expect(page.getByRole('button', { name: 'No permitted actions' })).toBeDisabled();
  await page.getByRole('button', { name: 'Many actions', exact: true }).click();
  const menu = page.getByRole('menu', { name: 'Many actions', exact: true });
  const bounds = await menu.boundingBox();
  expect(bounds!.y).toBeGreaterThanOrEqual(0);
  expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(480);
  await page.keyboard.press('End');
  await expect(page.getByRole('menuitem').last()).toBeInViewport();
  await page.keyboard.press('Enter');
  await expect(page.getByText('Selected 30', { exact: true })).toBeVisible();
});
