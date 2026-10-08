import type { Page } from '@playwright/test';
import type { Voucher, VoucherBatch } from '../src/api/types';

export const batches: VoucherBatch[] = [
  { id: 'partial', minutes: 120, hoursLabel: '2 hours', quantity: 12, issued: 12, outstanding: 6, redeemed: 6, expired: 0, expiresOn: '2026-11-14', note: 'Facebook giveaway', createdByUsername: 'owner', createdAt: '2026-10-09T07:00:00Z', codes: null },
  { id: 'new', minutes: 90, hoursLabel: '90 min', quantity: 500, issued: 500, outstanding: 500, redeemed: 0, expired: 0, expiresOn: '2026-12-31', note: 'Tournament prizes for visiting league players and their friends at the evening championship celebration', createdByUsername: 'evening-supervisor', createdAt: '2026-10-09T07:00:00Z', codes: null },
  { id: 'redeemed', minutes: 60, hoursLabel: '1 hour', quantity: 1, issued: 1, outstanding: 0, redeemed: 1, expired: 0, expiresOn: '2026-09-30', note: 'September winner', createdByUsername: null, createdAt: '2026-09-01T07:00:00Z', codes: null },
  { id: 'expired', minutes: 120, hoursLabel: '2 hours', quantity: 3, issued: 3, outstanding: 0, redeemed: 1, expired: 2, expiresOn: '2026-09-30', note: null, createdByUsername: 'owner', createdAt: '2026-09-01T07:00:00Z', codes: null },
];
export function codesFor(batch: VoucherBatch): Voucher[] {
  return Array.from({ length: batch.quantity }, (_, i) => {
    const status = i < batch.redeemed ? 'REDEEMED' : i < batch.redeemed + batch.expired ? 'EXPIRED' : 'OUTSTANDING';
    return { id: `${batch.id}-${i}`, batchId: batch.id, code: `SB-${String(i).padStart(3, '0')}-ABC`, minutes: batch.minutes, expiresOn: batch.expiresOn, status,
      redeemedAt: status === 'REDEEMED' ? '2026-09-25T09:00:00Z' : null,
      redeemedByUsername: status === 'REDEEMED' ? 'counter' : null,
      redeemedBillId: status === 'REDEEMED' ? `bill-${i}` : null,
      redeemedReceiptNo: status === 'REDEEMED' ? 2533 + i : null };
  });
}
export async function voucherFixtures(page: Page) {
  await page.route('**/api/v1/voucher-batches', route => route.fulfill({ json: { success: true, data: batches } }));
  await page.route('**/api/v1/vouchers?*', route => {
    const id = new URL(route.request().url()).searchParams.get('batchId');
    return route.fulfill({ json: { success: true, data: codesFor(batches.find(b => b.id === id)!) } });
  });
  await page.route('**/api/v1/setup/vouchers', route => route.fulfill({ json: { success: true, data: batches.map(b => ({ id: b.id, name: b.note ?? b.hoursLabel, archivedAt: null, canDelete: false, deletionReason: 'Redemption history is kept.', blockedReason: null })) } }));
}
