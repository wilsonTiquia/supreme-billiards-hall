import type { Page } from '@playwright/test';
import type { DailyReport, PeriodReport } from '../src/api/types';

/** Synthetic read-only report responses; authentication and shell use the scratch app. */
export async function analyticsFixtures(page: Page, variant: 'normal' | 'zero' | 'single' | 'dense' = 'normal') {
  await page.route('**/api/v1/reports/*?*', async route => {
    const url = new URL(route.request().url());
    if (!['/api/v1/reports/daily', '/api/v1/reports/period'].includes(url.pathname)) return route.continue();
    const response = await route.fetch();
    const envelope = await response.json();
    const empty = variant === 'zero';
    const hours = empty ? [] : [{ hour: 10, bills: 1, amount: 1234.56 }, { hour: 17, bills: 4, amount: 18000.75 }, { hour: 4, bills: 2, amount: 500.25 }];
    const products = Array.from({ length: 8 }, (_, i) => ({ name: i === 0 ? 'San Miguel Pale Pilsen — celebration bucket with snacks and extra ice' : `Product ${i + 1}`, description: i === 0 ? 'San Miguel Pale Pilsen — celebration bucket with snacks and extra ice' : `Product ${i + 1}`, quantity: 40 - i, revenue: 4000 - i * 350, cost: 1000, margin: 3000 - i * 350, marginPercent: 75 }));
    if (url.pathname.endsWith('/daily')) {
      const d = envelope.data as DailyReport;
      if (!empty) {
        d.totals = { bills: 7, gross: 19735.56, cost: 4000, profit: 15735.56, timeRevenue: variant === 'single' ? 0 : 12000, itemRevenue: variant === 'single' ? 21000 : 9000 };
        d.salesByHour = hours;
        d.topItems = products;
        d.tableUtilisation = d.tableUtilisation.map((t, i) => ({ ...t, occupiedMinutes: 600 - i * 65, utilisationPercent: (600 - i * 65) / 11.4 }));
        d.paymentMix = [{ method: 'CASH', payments: 5, amount: 15000 }, { method: 'GCASH', payments: 2, amount: 4735.56 }];
        d.losses.discountAmount = 1000; d.losses.voucherAmount = 264.44;
      }
    } else {
      const d = envelope.data as PeriodReport;
      if (!empty) {
        if (variant === 'dense') d.byDay = Array.from({ length: 366 }, (_, i) => ({ ...d.byDay[0], businessDate: new Date(Date.UTC(2025, 9, 1 + i)).toISOString().slice(0, 10) }));
        d.byDay = d.byDay.map((day, i) => ({ ...day, trading: true, bills: i === 0 ? 1 : 4, gross: variant === 'single' && i !== 0 ? 0 : i === 0 ? 1234.56 : i % 5 === 0 ? 18000.75 : 6000 + i * 10, costOfGoods: 1000, grossProfit: 5000, operatingExpenses: 500, net: 4500 }));
        d.headline = { ...d.headline, bills: 100, gross: 197350.56, costOfGoods: 40000, grossProfit: 157350.56, operatingExpenses: 30000, net: 127350.56, tradingDays: d.byDay.length, grossPerTradingDay: 18000, netPerTradingDay: 10000, grossMarginPercent: 80 };
        d.breakEven = { computable: true, requiredGrossPerTradingDay: 4000.25, actualGrossPerTradingDay: 18000 };
        d.tables = d.tables.map((t, i) => ({ ...t, occupiedMinutes: 6000 - i * 65, utilisationPercent: 45, timeRevenue: 20000 - i * 800, revenuePerOccupiedHour: 200 - i * 2 }));
        d.products = products; d.byHour = hours;
      }
    }
    await route.fulfill({ response, json: envelope });
  });
}
