import type { Page } from '@playwright/test';
import type { AuditFeedEntry } from '../src/api/types';

const base: AuditFeedEntry = {
  id: 'shared-id', source: 'AUDIT', action: 'PRODUCT_UPDATED', actionLabel: 'Product updated',
  entityLabel: 'Product', subject: 'San Miguel Pale Pilsen', actorName: 'Owner',
  occurredAt: '2026-09-29T14:00:00Z', businessDate: '2026-09-29',
  before: { sellingPrice: 85 }, after: { sellingPrice: 90 }, note: null,
  quantityDelta: null, customerTypeName: null,
};

export const auditEntries: AuditFeedEntry[] = [
  base,
  { ...base, source: 'STOCK', action: 'STOCK_CORRECTION', actionLabel: 'Stock corrected',
    before: null, after: null, quantityDelta: -2, note: 'Two damaged bottles counted at closing.' },
  { ...base, id: 'rate', action: 'SESSION_RATE_OVERRIDE', actionLabel: 'League players rate',
    entityLabel: 'Table', subject: 'Table 2', customerTypeName: 'League players',
    before: { ratePerHour: 240, ratePerMinute: 4 }, after: { ratePerHour: null, ratePerMinute: 5 },
    note: 'Rate changed for the next game.' },
  { ...base, id: 'long', subject: 'Tournament refreshments and equipment for the visiting league',
    actorName: 'Evening supervisor with a long display name',
    note: 'Supplier reference: ' + 'A'.repeat(120),
    before: { name: 'Old label', metadata: { note: 'Original supplier' } },
    after: { name: 'Updated label', metadata: { note: 'B'.repeat(180) } } },
  { ...base, id: 'empty', action: 'USER_ARCHIVED', actionLabel: 'Staff archived',
    entityLabel: 'Staff', subject: 'Former counter', actorName: null, before: null, after: null },
];

// Browser-only read fixtures; authentication still uses the isolated scratch backend.
export async function auditFixtures(page: Page) {
  await page.route('**/api/v1/audit/filters', route => route.fulfill({ json: { success: true, data: {
    actions: [{ action: 'PRODUCT_UPDATED', label: 'Product updated' }],
    actors: [{ id: 'owner-id', name: 'Owner' }],
  } } }));
  await page.route('**/api/v1/audit/feed?*', route => {
    const query = new URL(route.request().url()).searchParams;
    const p = Number(query.get('page') ?? 0);
    const filtered = query.has('action') || query.has('actor');
    const content = p === 2 ? [{ ...base, id: 'last', subject: 'Oldest product' }]
      : Array.from({ length: 25 }, (_, i) => !filtered && p === 0 && i < auditEntries.length
        ? auditEntries[i]!
        : { ...base, id: `entry-${p}-${i}`, subject: `Product ${51 - p * 25 - i}` });
    return route.fulfill({ json: { success: true, data: {
      page: p, size: 25, totalPages: 3, totalElements: 51, content,
    } } });
  });
}
