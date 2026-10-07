import type { Page } from '@playwright/test';

export const snapshot = {
  id: 'receipt-51', billId: 'bill-1-0', receiptNo: 51, issuedAt: '2026-09-29T14:00:00Z',
  payload: {
    status: 'UNSETTLED', subtotalTime: 240, subtotalItems: 390, totalAmount: 530,
    discountAmount: 40, discountReason: 'Regular customer', voucherAmount: 60,
    voucherCode: 'SB-7K4-M2Q', voucherHoursCovered: '0.25',
    lines: [
      { description: 'Table 3 · 60 min @ 4.00/min', quantity: 1, unitPrice: 240, lineTotal: 240 },
      { description: 'San Miguel Pale Pilsen', quantity: 1, unitPrice: 90, lineTotal: 90 },
      { description: 'San Miguel Pale Pilsen', quantity: 1, unitPrice: 90, lineTotal: 90 },
      { description: 'Sizzling pork sisig with extra rice for the evening group', quantity: 1, unitPrice: 210, lineTotal: 210 },
    ],
  },
  settlement: { method: 'CASH', amount: 530, takenAt: '2026-09-30T04:30:00Z', takenByUsername: 'owner' },
};

export async function receiptFixtures(page: Page) {
  await page.route('**/api/v1/bills?*', route => {
    const p = Number(new URL(route.request().url()).searchParams.get('page') ?? 0);
    return route.fulfill({ json: { success: true, data: {
      page: p, size: 50, totalPages: 3, totalElements: 101,
      content: Array.from({ length: p === 2 ? 1 : 50 }, (_, i) => ({
        id: `bill-${p}-${i}`, receiptNo: 101 - p * 50 - i, closedAt: snapshot.issuedAt,
        totalAmount: 530, method: 'CASH', takenByUsername: 'owner', quickSale: false,
      })),
    } } });
  });
  await page.route('**/api/v1/bills/*/receipt', route => {
    const billId = route.request().url().split('/bills/')[1].split('/')[0];
    const [, p, i] = billId.split('-').map(Number);
    const receiptNo = Number.isFinite(p + i) ? 101 - p * 50 - i : 51;
    return route.fulfill({ json: { success: true, data: { ...snapshot, billId, receiptNo, id: `receipt-${receiptNo}` } } });
  });
  await page.route('**/api/v1/bills/*/notes', route => route.fulfill({ json: { success: true, data: [
    { id: 'note-1', body: 'Marco and friends — paid the next afternoon. Keep the original unpaid receipt for the night’s record.', authorUsername: 'counter', createdAt: '2026-09-29T14:00:00Z', kind: 'STAFF' },
    { id: 'note-2', body: 'Settled by owner — CASH 530.00', authorUsername: 'owner', createdAt: '2026-09-30T04:30:00Z', kind: 'SYSTEM' },
  ] } }));
}
