import { test, expect, COUNTER, apiAs, apiGet, apiPost, openTable, backdateSession, BEER } from './fixtures';
import type { Bill, Session } from '../src/api/types';

for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
  test(`counter navigation and ordering at ${width}px in ${theme}`, async ({ page, signIn, hall }) => {
    await page.setViewportSize({ width, height: 900 });
    await signIn(COUNTER);
    await page.evaluate(t => localStorage.setItem('supreme.theme.pos', t), theme);
    const api = await apiAs(COUNTER);
    const sessionId = await openTable(page, 6);
    try {
      backdateSession(sessionId, 12);
      await page.reload();
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
      const back = page.getByRole('link', { name: 'Back to floor', exact: true });
      await expect(back).toBeInViewport();
      await expect(back).toHaveAttribute('href', '/floor');
      if (width === 400) await page.getByRole('button', { name: 'Add items', exact: true }).click();
      const search = page.getByLabel('Search products');
      await expect(search).toBeFocused();
      await page.getByRole('button', { name: 'Beer', exact: true }).click();
      await search.fill(BEER.name);
      await search.press('Enter');
      if (width === 400) await expect(page.getByRole('dialog')).toHaveCount(0);
      await expect(page.locator('main').getByText(BEER.name, { exact: true }).last()).toBeVisible();

      // The new primary action still waits for the backend and reconciles the live bill.
      const pause = page.getByRole('button', { name: 'Pause', exact: true });
      await pause.focus();
      await page.keyboard.press('Space');
      await expect(page.getByRole('button', { name: 'Resume', exact: true })).toBeVisible();
      const paused = await apiGet<Session>(api, `/api/v1/sessions/${sessionId}`);
      expect(paused.status).toBe('PAUSED');
      expect(paused.billedMinutes).toBe(12);
      expect(paused.itemTotal).toBe(90);
      const bill = await apiGet<Bill>(api, `/api/v1/bills/${paused.billId}`);
      expect(bill.lines.filter(l => l.productId === hall.beerId)).toHaveLength(1);
      const timer = await page.locator('.figure-timer').textContent();
      await expect(page.locator('.figure-amount')).toHaveText(new Intl.NumberFormat('en-PH', { style: 'currency', currency: 'PHP' }).format(paused.runningTotal));
      await back.focus();
      await page.keyboard.press('Enter');
      await expect(page).toHaveURL(/\/floor$/);
      const card = page.locator('button').filter({ has: page.locator('.figure-table-number', { hasText: /^06$/ }) });
      await expect(card).toContainText('Paused');
      await card.click();
      await expect(page.locator('.figure-timer')).toHaveText(timer!);
      await page.getByRole('button', { name: 'Resume', exact: true }).click();
      await expect(page.getByRole('button', { name: 'Pause', exact: true })).toBeVisible();
      expect((await apiGet<Session>(api, `/api/v1/sessions/${sessionId}`)).status).toBe('OPEN');
      await back.click();
      await expect(page).toHaveURL(/\/floor$/);

      await page.goto('/quick-sale');
      await expect(back).toBeInViewport();
      await expect(page.getByLabel('Search products')).toBeFocused();
      await page.getByLabel('Search products').fill(BEER.name);
      await page.getByLabel('Search products').press('Enter');
      await expect(page.getByRole('button', { name: 'Take ₱90.00', exact: true })).toBeVisible();
      await page.getByRole('button', { name: `One more ${BEER.name}` }).click();
      await expect(page.getByRole('button', { name: 'Take ₱180.00', exact: true })).toBeVisible();
      await back.focus();
      await page.keyboard.press('Enter');
      await expect(page).toHaveURL(/\/floor$/);
      // Leaving Quick sale has not closed or settled the table's bill.
      expect((await apiGet<Bill>(api, `/api/v1/bills/${paused.billId}`)).status).toBe('OPEN');
    } finally {
      await apiPost(api, `/api/v1/sessions/${sessionId}/close`, {});
      await api.dispose();
    }
  });
}

test('pause keeps pending and failure states and resumes after server confirmation', async ({ page, signIn, hall }) => {
  void hall;
  await signIn(COUNTER);
  const api = await apiAs(COUNTER);
  const id = await openTable(page, 6);
  try {
    const pause = page.getByRole('button', { name: 'Pause', exact: true });
    let release!: () => void;
    const gate = new Promise<void>(resolve => { release = resolve; });
    await page.route(`**/sessions/${id}/pause`, async route => {
      await gate;
      await route.fulfill({ status: 409, json: { success: false, message: 'Pause could not be recorded. Try again.' } });
    });
    await pause.click();
    await expect(pause).toBeDisabled();
    await expect(pause).toHaveAttribute('aria-busy', 'true');
    await expect(page.getByRole('button', { name: 'Resume', exact: true })).toHaveCount(0);
    release();
    await expect(page.getByText('Pause could not be recorded. Try again.', { exact: true })).toBeVisible();
    await expect(pause).toBeEnabled();
    expect((await apiGet<Session>(api, `/api/v1/sessions/${id}`)).status).toBe('OPEN');
    await page.unroute(`**/sessions/${id}/pause`);
    await pause.click();
    const resume = page.getByRole('button', { name: 'Resume', exact: true });
    await expect(resume).toBeVisible();
    let releaseResume!: () => void;
    const resumeGate = new Promise<void>(resolve => { releaseResume = resolve; });
    await page.route(`**/sessions/${id}/resume`, async route => {
      await resumeGate;
      await route.fulfill({ response: await route.fetch() });
    });
    await resume.click();
    await expect(resume).toBeDisabled();
    await expect(resume).toHaveAttribute('aria-busy', 'true');
    await expect(pause).toHaveCount(0);
    releaseResume();
    await expect(pause).toBeEnabled();
    expect((await apiGet<Session>(api, `/api/v1/sessions/${id}`)).status).toBe('OPEN');
  } finally {
    await apiPost(api, `/api/v1/sessions/${id}/close`, {});
    await api.dispose();
  }
});

test('return links remain usable with empty products and a failed session load', async ({ page, signIn, hall }) => {
  void hall;
  await page.setViewportSize({ width: 400, height: 900 });
  await signIn(COUNTER);
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/v1/products?activeOnly=true', async route => {
    await gate;
    await route.fulfill({ json: { success: true, data: [] } });
  });
  await page.goto('/quick-sale');
  await expect(page.getByRole('status').filter({ hasText: 'Loading products…' })).toBeVisible();
  const back = page.getByRole('link', { name: 'Back to floor', exact: true });
  await expect(back).toBeInViewport();
  release();
  await expect(page.getByText('Nothing in this category yet.', { exact: true })).toBeVisible();
  await page.getByLabel('Search products').fill('Water');
  await expect(page.getByText('Nothing in this shelf matches “Water”.', { exact: true })).toBeVisible();
  await back.click();
  await expect(page).toHaveURL(/\/floor$/);
  await page.route('**/api/v1/sessions/missing', route => route.fulfill({ status: 404, json: { success: false, message: 'Session not found.' } }));
  await page.goto('/sessions/missing');
  await expect(page.getByText('Session not found.', { exact: true })).toBeVisible();
  await back.focus();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/floor$/);
});
