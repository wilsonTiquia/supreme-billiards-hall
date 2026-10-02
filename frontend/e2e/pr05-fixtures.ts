import type { Page } from '@playwright/test';
import type { PeriodReport } from '../src/api/types';

/** Read-only synthetic figures; login and response shape come from the scratch app. */
export async function reportFixtures(page: Page, variant: 'normal' | 'negative' | 'zero-prior' | 'empty' = 'normal') {
  await page.route('**/api/v1/reports/period?*', async route => {
    const response = await route.fetch();
    const envelope = await response.json();
    const d = envelope.data as PeriodReport;
    if (variant !== 'empty') {
      d.headline = { bills: 393, gross: 335666, costOfGoods: 152574, grossProfit: 183092,
        operatingExpenses: variant === 'negative' ? 200000 : 79700, net: variant === 'negative' ? -16908 : 103392,
        tradingDays: 18, grossPerTradingDay: 18648.11, netPerTradingDay: variant === 'negative' ? -939.33 : 5744, grossMarginPercent: 54.5 };
      if (variant !== 'zero-prior') d.previousHeadline = { bills: 688, gross: 425000, costOfGoods: 109000,
        grossProfit: 316000, operatingExpenses: 124000, net: 192000, tradingDays: 27,
        grossPerTradingDay: 15740.74, netPerTradingDay: 7111.11, grossMarginPercent: 74.4 };
      d.byDay = d.byDay.map((day, i) => ({ ...day, trading: true, bills: 12, gross: 10000 + i * 100,
        costOfGoods: 4000, grossProfit: 6000 + i * 100, operatingExpenses: 500, net: 5500 + i * 100 }));
      d.breakEven = { computable: true, requiredGrossPerTradingDay: 8118, actualGrossPerTradingDay: 18648.11 };
      d.products = Array.from({ length: 40 }, (_, i) => ({ name: i ? `Product ${i + 1}` : 'San Miguel Pale Pilsen — celebration bucket with snacks and extra ice', quantity: 100 - i, revenue: 20430 - i * 350, cost: 1000, margin: 19430 - i * 350, marginPercent: 75 }));
      d.tables = d.tables.map((t, i) => ({ ...t, occupiedMinutes: 5500 - i * 100, timeRevenue: 26000 - i * 700, revenuePerOccupiedHour: 280, utilisationPercent: 45 }));
      d.cash = { varianceTotal: -200, nightsWithVariance: 1, countedNights: 13, uncountedTradingDays: 5,
        unsettled: { thisPeriod: { count: 2, amount: 1383 }, oneToFourWeeksBefore: { count: 0, amount: 0 }, older: { count: 3, amount: 1450 } } };
      Object.assign(d.givenAway, { promoSessions: 21, promoForgone: 2266, friendSessions: 6, friendForgone: 1427,
        flatSessions: 0, flatForgone: 0, reducedSessions: 1, timeReductionForgone: 88, discountBills: 6,
        discountAmount: 398, voucherCount: 1, voucherAmount: 600, voidCount: 11, voidAmount: 980,
        compQuantity: 12, compEstimatedCost: 777, total: 6536, percentOfGross: 1.9 });
    }
    await route.fulfill({ response, json: envelope });
  });
}
