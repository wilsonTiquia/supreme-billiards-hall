import type { Page } from '@playwright/test';

// Read-only fixtures keep before/after images reproducible without trading data.
export async function listFixtures(page: Page) {
  await page.route('**/api/v1/bills?*', route => {
    const p = Number(new URL(route.request().url()).searchParams.get('page') ?? 0);
    return route.fulfill({ json: { success: true, data: { page: p, size: 50, totalPages: 3, totalElements: 101,
      content: Array.from({ length: p === 2 ? 1 : 50 }, (_, i) => ({ id: `bill-${p}-${i}`, receiptNo: 101 - p * 50 - i,
        closedAt: '2026-09-29T14:00:00Z', totalAmount: 1250, method: 'CASH', takenByUsername: 'Front Counter', quickSale: true })) } } });
  });
  await page.route('**/api/v1/audit/feed?*', route => {
    const p = Number(new URL(route.request().url()).searchParams.get('page') ?? 0);
    return route.fulfill({ json: { success: true, data: { page: p, size: 25, totalPages: 3, totalElements: 51,
      content: Array.from({ length: p === 2 ? 1 : 25 }, (_, i) => ({ id: `entry-${p}-${i}`, source: 'AUDIT', action: 'PRODUCT_UPDATED',
        actionLabel: 'Product updated', entityLabel: 'Product', subject: `Product ${51 - p * 25 - i}`, actorName: 'Owner',
        occurredAt: '2026-09-29T14:00:00Z', businessDate: '2026-09-29', before: { name: 'Old name' }, after: { name: 'New name' }, quantityDelta: null })) } } });
  });
}

