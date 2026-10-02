import { test, expect, OWNER, COUNTER, apiAs, apiGet, apiPost, openTable, backdateSession } from './fixtures';
import type { Page } from '@playwright/test';
import type { FloorView, CustomerType, Session } from '../src/api/types';

const card = (page: Page, number: number) => page.locator('button').filter({
  has: page.locator('.figure-table-number', { hasText: new RegExp(`^${String(number).padStart(2, '0')}$`) }),
});

// Real scratch records: deliberately misleading seeded names on Standard Tables 5–7.
test('Floor classification follows the flag through free, running, paused and resumed states', async ({ page, signIn, hall }) => {
  void hall;
  const api = await apiAs(OWNER);
  let sessionId: string | undefined;
  try {
    await signIn(COUNTER);
    await page.goto('/floor');
    const floor = await apiGet<FloorView>(api, '/api/v1/tables');
    for (let n = 1; n <= 7; n++) {
      const table = floor.tables.find(t => t.tableNumber === n)!;
      expect(table.isPremium).toBe(n <= 3);
      const tableCard = card(page, n);
      await expect(tableCard.getByText('Premium', { exact: true })).toHaveCount(n <= 3 ? 1 : 0);
      await expect(tableCard.locator(n <= 3 ? 'svg.stroke-gold' : 'svg.stroke-green')).toBeVisible();
      await expect(tableCard.getByText('Free', { exact: true })).toBeVisible();
    }
    sessionId = await openTable(page, 1);
    for (const [state, action] of [['Running', 'Pause'], ['Paused', 'Resume'], ['Running', null]] as const) {
      await page.getByRole('button', { name: 'Floor', exact: true }).click();
      await expect(card(page, 1).getByText('Premium', { exact: true })).toBeVisible();
      await expect(card(page, 1).getByText(state, { exact: true })).toBeVisible();
      await expect(card(page, 1).locator('svg[shape-rendering="crispEdges"]')).toBeVisible();
      await expect(card(page, 1)).toContainText('₱4.00 / min');
      await card(page, 1).click();
      if (action) {
        await page.getByRole('button', { name: action, exact: true }).click();
        await expect(page.getByRole('button', { name: action === 'Pause' ? 'Resume' : 'Pause', exact: true })).toBeVisible();
      }
    }
    const persisted = (await apiGet<FloorView>(api, '/api/v1/tables')).tables.find(t => t.tableNumber === 1)!;
    expect(persisted.ratePerMinute).toBe(4);
    expect(persisted.session!.ratePerMinute).toBe(4);
    expect(persisted.session!.flatAmount).toBeNull();
    expect(persisted.session!.rateOverrideKind).toBeNull();
    await apiPost(api, `/api/v1/sessions/${sessionId}/close`, {});
    sessionId = undefined;
    await page.goto('/floor');
    await expect(card(page, 1).getByText('Free', { exact: true })).toBeVisible();
    await expect(card(page, 1).getByText('Premium', { exact: true })).toBeVisible();
    await expect(card(page, 1).locator('svg.stroke-gold')).toBeVisible();
  } finally {
    if (sessionId) await apiPost(api, `/api/v1/sessions/${sessionId}/close`, {});
    await api.dispose();
  }
});

test('Floor keeps classification separate from session status and pricing at both widths and themes', async ({ page, signIn, hall }) => {
  void hall;
  const api = await apiAs(OWNER);
  const sessions: Session[] = [];
  try {
    const floor = await apiGet<FloorView>(api, '/api/v1/tables');
    const types = await apiGet<CustomerType[]>(api, '/api/v1/customer-types');
    const customerTypeId = types.find(t => t.isDefault)!.id;
    for (const n of [2, 3, 5, 6]) {
      const session = await apiPost<Session>(api, '/api/v1/sessions', {
        tableId: floor.tables.find(t => t.tableNumber === n)!.id, customerTypeId,
        ...(n === 2 || n === 5 ? { flatAmount: 321.09, flatRateReason: 'Floor classification fixture' }
          : { rateOverridePerMinute: 2.5, rateOverrideKind: 'PROMO', rateOverrideReason: 'Floor classification fixture' }),
      });
      sessions.push(session);
      backdateSession(session.id, 10);
      if (n === 2 || n === 5) await apiPost(api, `/api/v1/sessions/${session.id}/pause`, {});
    }
    await signIn(COUNTER);
    for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
      await page.setViewportSize({ width, height: 900 });
      await page.evaluate(t => localStorage.setItem('supreme.theme.pos', t), theme);
      await page.goto('/floor');
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
      await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
      for (let n = 1; n <= 7; n++) {
        const tableCard = card(page, n);
        const state = n === 2 || n === 5 ? 'Paused' : n === 3 || n === 6 ? 'Running' : 'Free';
        await expect(tableCard.getByText(state, { exact: true })).toBeVisible();
        if (process.env.FLOOR_PREMIUM_CAPTURE !== 'before') {
          await expect(tableCard.getByText('Premium', { exact: true })).toHaveCount(n <= 3 ? 1 : 0);
        }
        if (state === 'Paused') {
          await expect(tableCard.locator('.figure-amount')).toHaveText('₱321.09');
          await expect(tableCard).toContainText('Flat ₱321.09');
          await expect(tableCard.locator('.figure-timer')).toHaveText(/^0:10:\d{2}$/);
        } else if (state === 'Running') {
          await expect(tableCard).toContainText('Promo ₱2.50 / min');
        }
      }
      if (process.env.FLOOR_PREMIUM_CAPTURE) {
        await page.screenshot({ path: `../docs/qa/floor-premium/${process.env.FLOOR_PREMIUM_CAPTURE}-${theme}-${width}.png`, fullPage: true, animations: 'disabled' });
      }
    }
  } finally {
    for (const session of sessions) await apiPost(api, `/api/v1/sessions/${session.id}/close`, {});
    await api.dispose();
  }
});
