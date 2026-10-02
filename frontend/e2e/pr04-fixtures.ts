import type { Page } from '@playwright/test';
import type { CashCount, DailyReport } from '../src/api/types';
import { analyticsFixtures } from './pr03-fixtures';

export type NightScenario = 'urgent' | 'clear' | 'other' | 'error' | 'loading' | 'running' | 'recount' | 'stale';
export const selectedNight = '2026-09-25';

/** Synthetic read-only responses. Login and navigation still use the isolated scratch app. */
export async function nightCheckFixtures(page: Page, scenario: NightScenario = 'urgent') {
  await analyticsFixtures(page);
  await page.route('**/api/v1/reports/daily?*', async route => {
    const response = await route.fetch();
    const envelope = await response.json();
    const d = envelope.data as DailyReport;
    const followUp = scenario === 'urgent';
    d.outstanding = { count: followUp ? 5 : 0, amount: followUp ? 2833 : 0 };
    d.unsettledTonight = { count: followUp ? 2 : 0, amount: followUp ? 654 : 0 };
    d.lowStock = ['urgent', 'other'].includes(scenario) ? Array.from({ length: 12 }, (_, i) => ({ name: i === 0 ? 'San Miguel Pale Pilsen celebration bucket with snacks and extra ice' : `Low stock product ${i + 1}`, qtyOnHand: i - 3 })) : [];
    d.totals.bills = followUp ? 124 : 0;
    await route.fulfill({ response, json: envelope });
  });
  await page.route('**/api/v1/business-day/current', route => route.fulfill({ json: { success: true, data: {
    businessDate: scenario === 'running' ? selectedNight : '2026-09-26', serverNow: scenario === 'running' ? '2026-09-26T02:00:00+08:00' : '2026-09-26T12:00:00+08:00', canClose: true, openSessions: [],
  } } }));
  for (const path of ['bills/unsettled', 'business-day/uncounted']) {
    await page.route(`**/api/v1/${path}`, async route => {
      if (scenario === 'loading') await new Promise(resolve => setTimeout(resolve, 2500));
      if (scenario === 'error') return route.fulfill({ status: 503, json: { success: false, message: 'Checks unavailable' } });
      await route.fulfill({ json: { success: true, data: scenario === 'urgent'
        ? path === 'bills/unsettled' ? [{ id: 'synthetic-bill' }, { id: 'synthetic-bill-2' }]
          : [{ businessDate: '2026-09-20', bills: 5 }, { businessDate: '2026-09-22', bills: 2 }]
        : [] } });
    });
  }
  await page.route('**/api/v1/business-day/*/cash-count', async route => {
    if (scenario === 'error') return route.fulfill({ status: 503, json: { success: false, message: 'Cash unavailable' } });
    const date = new URL(route.request().url()).pathname.split('/')[4];
    const count: CashCount = { stale: scenario === 'stale', id: 'synthetic-count', businessDate: date, openingFloat: 1000, cashSales: 0,
      cashExpenses: 0, expectedCash: 1000, floatOverridden: false, countedCash: 1000, variance: 0,
      countedAt: `${date}T21:00:00Z`, note: null, closedAt: `${date}T21:01:00Z`, closedByUsername: 'owner',
      salesAfterClose: scenario === 'recount' ? 1 : 0, amountAfterClose: 100, cashAfterClose: 100,
      expensesAfterClose: 0, cashExpensesAfterClose: 0 };
    await route.fulfill({ json: { success: true, data: ['urgent', 'running'].includes(scenario) ? null : count } });
  });
}
