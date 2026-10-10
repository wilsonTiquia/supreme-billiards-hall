import type { Page } from '@playwright/test';

export const debts = [
  { id: 'debt-today', receiptNo: 4463, businessDate: '2026-10-09', daysOutstanding: 0, totalAmount: 850, tableNames: ['Table 2'], latestNote: { body: 'Jun and Marco - pays next week' } },
  { id: 'debt-yesterday', receiptNo: 4462, businessDate: '2026-10-08', daysOutstanding: 1, totalAmount: 725, tableNames: ['Table 1'], latestNote: { body: 'Miss Ana - pays next week' } },
  { id: 'debt-13', receiptNo: 4461, businessDate: '2026-09-26', daysOutstanding: 13, totalAmount: 476, tableNames: ['Table 3'], latestNote: { body: 'Kuya Bong - pays next week' } },
  { id: 'debt-14', receiptNo: 4460, businessDate: '2026-09-25', daysOutstanding: 14, totalAmount: 350, tableNames: ['Table 4'], latestNote: { body: 'Ate Liza’s group - pays next week' } },
  { id: 'debt-old', receiptNo: 4459, businessDate: '2026-09-25', daysOutstanding: 14, totalAmount: 624, tableNames: ['Table 5'], latestNote: { body: 'Sir Ramon - returning after work on Friday with the rest of the team to pay the full balance' } },
  { id: 'debt-unnamed', receiptNo: 4458, businessDate: '2026-08-01', daysOutstanding: 69, totalAmount: 120, tableNames: [], latestNote: null },
].map(bill => ({ ...bill, unsettledAt: '2026-10-10T02:00:00+08:00', unsettledByUsername: 'counter' }));
export const nights = Array.from({ length: 20 }, (_, i) => ({ businessDate: `2026-09-${String(i + 1).padStart(2, '0')}`, bills: i + 1 }));

export async function debtFixtures(page: Page) {
  const fulfill = (data: unknown) => ({ json: { success: true, data } });
  await page.route('**/api/v1/bills/unpaid', r => r.fulfill(fulfill(debts)));
  await page.route('**/api/v1/bills/unsettled', r => r.fulfill(fulfill([])));
  await page.route('**/api/v1/business-day/current', r => r.fulfill(fulfill({ businessDate: '2026-10-09', standardCashFloat: 1000, openSessions: [], canClose: false })));
  await page.route('**/api/v1/business-day/*/open-sessions', r => r.fulfill(fulfill({ businessDate: r.request().url().split('/').at(-2), standardCashFloat: 1000, openSessions: [], canClose: false })));
  await page.route('**/api/v1/business-day/uncounted', r => r.fulfill(fulfill(nights)));
  await page.route('**/api/v1/business-day/*/cash-count', r => r.fulfill(fulfill(null)));
  await page.route('**/api/v1/expenses?*', r => r.fulfill(fulfill([])));
}
