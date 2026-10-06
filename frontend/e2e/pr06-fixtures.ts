import type { Page } from '@playwright/test';

export const catalogCategories = [
  { id: 'beer', name: 'Beer', sortOrder: 1 },
  { id: 'food', name: 'Food', sortOrder: 2 },
];
export const catalogProducts = [
  { id: 'beer-1', name: 'San Miguel Pale Pilsen', categoryId: 'beer', sellingPrice: 90, avgCost: 62.5, qtyOnHand: 48, isActive: true, archivedAt: null, imageSha256: null },
  { id: 'beer-2', name: 'Celebration bucket with snacks, extra ice and six bottles of premium beer', categoryId: 'beer', sellingPrice: 620, avgCost: 375, qtyOnHand: 12, isActive: false, archivedAt: null, imageSha256: null },
  { id: 'food-1', name: 'Sisig', categoryId: 'food', sellingPrice: 180, avgCost: 95, qtyOnHand: 20, isActive: true, archivedAt: null, imageSha256: null },
  { id: 'other-1', name: 'Bottled water', categoryId: null, sellingPrice: 25, avgCost: 12.5, qtyOnHand: 0, isActive: true, archivedAt: null, imageSha256: null },
  { id: 'old-1', name: 'Seasonal snack', categoryId: 'seasonal', sellingPrice: 60, avgCost: 25, qtyOnHand: 4, isActive: true, archivedAt: '2026-09-27T14:00:00Z', imageSha256: null },
];

// Read-only visual fixtures; mutation coverage uses the real scratch API.
export async function catalogFixtures(page: Page) {
  await page.route('**/api/v1/categories', route => route.fulfill({ json: { success: true, data: catalogCategories } }));
  await page.route('**/api/v1/setup/categories', route => route.fulfill({ json: { success: true, data: [
    ...catalogCategories.map(c => ({ ...c, archivedAt: null })),
    { id: 'seasonal', name: 'Seasonal specials', archivedAt: '2026-09-27T14:00:00Z' },
  ] } }));
  await page.route('**/api/v1/products?*', route => route.fulfill({ json: { success: true,
    data: catalogProducts.filter(p => !p.archivedAt || new URL(route.request().url()).searchParams.get('includeArchived') === 'true'),
  } }));
}

export const picture = { name: 'product.png', mimeType: 'image/png', buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jf1sAAAAASUVORK5CYII=', 'base64') };
