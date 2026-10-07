import { test, expect, OWNER, apiGet, apiPost } from './fixtures';
import { catalogFixtures, catalogProducts, picture } from './pr06-fixtures';
import type { ProductAdmin, StockMovement } from '../src/api/types';
import type { Page } from '@playwright/test';

async function newProduct(page: Page, name: string) {
  await page.getByRole('button', { name: 'New product', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Name', { exact: true }).fill(name);
  await dialog.getByLabel('Selling price').fill('90');
  return dialog;
}
async function productNamed(page: Page, name: string) {
  const products = await apiGet<ProductAdmin[]>(page.request, '/api/v1/products?activeOnly=false&includeArchived=true');
  const matches = products.filter(p => p.name === name);
  expect(matches).toHaveLength(1);
  return matches[0]!;
}

for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
  test(`category/search/archive filters and keyboard menu · ${width} · ${theme}`, async ({ page, signIn }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.addInitScript(t => localStorage.setItem('supreme.theme.admin', t), theme);
    await catalogFixtures(page);
    await signIn(OWNER);
    await page.goto('/admin/products');
    await expect(page.getByRole('region', { name: 'Beer', exact: true })).toContainText('Inactive');
    await expect(page.getByRole('region', { name: 'Uncategorized', exact: true })).toContainText('Bottled water');
    await page.getByLabel('Filter by category').selectOption('food');
    await expect(page.getByRole('cell', { name: 'Sisig', exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: 'San Miguel Pale Pilsen', exact: true })).toHaveCount(0);
    await page.getByLabel('Search products').fill('not on the menu');
    await expect(page.getByText('No products match these filters.')).toBeVisible();
    await page.getByLabel('Search products').press('Escape');
    await page.getByLabel('Filter by category').selectOption('uncategorized');
    await expect(page.getByRole('cell', { name: 'Bottled water', exact: true })).toBeVisible();
    await page.getByLabel('Filter by category').selectOption('all');
    await page.getByLabel('Include archived').check();
    await expect(page.getByRole('region', { name: 'Seasonal specials', exact: true })).toContainText('Archived');
    await expect(page.getByRole('button', { name: 'Restore', exact: true })).toBeVisible();
    const trigger = page.getByRole('button', { name: 'Actions for San Miguel Pale Pilsen', exact: true });
    await trigger.focus();
    await trigger.press('ArrowDown');
    await expect(page.getByRole('menuitem', { name: 'Edit', exact: true })).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(page.getByRole('menuitem', { name: 'Archive', exact: true })).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(trigger).toBeFocused();
    await trigger.click();
    await page.getByRole('menuitem', { name: 'Edit', exact: true }).click();
    await expect(page.getByRole('dialog')).toHaveAccessibleName('Edit San Miguel Pale Pilsen');
    await page.getByRole('button', { name: 'Close', exact: true }).click();
    await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
  });
}

test('create without a picture, validate files, replace/remove local preview and cancel without writes', async ({ page, signIn }) => {
  await signIn(OWNER);
  await page.goto('/admin/products');
  let creates = 0;
  let uploads = 0;
  page.on('request', r => {
    if (r.method() === 'POST' && r.url().endsWith('/products')) creates++;
    if (r.method() === 'POST' && r.url().endsWith('/image')) uploads++;
  });
  let dialog = await newProduct(page, 'PR06 no picture');
  const picker = dialog.getByLabel('Choose product picture');
  await picker.setInputFiles({ name: 'notes.txt', mimeType: 'text/plain', buffer: Buffer.from('not an image') });
  await expect(dialog.getByRole('alert')).toContainText('Use a JPEG, PNG or WebP');
  await expect(dialog.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
  await picker.setInputFiles({ ...picture, buffer: Buffer.alloc(2 * 1024 * 1024 + 1) });
  await expect(dialog.getByRole('alert')).toContainText('limit is 2 MB');
  await picker.setInputFiles(picture);
  await expect(dialog.getByRole('img', { name: 'Selected product picture' })).toBeVisible();
  await picker.setInputFiles({ ...picture, name: 'replacement.png' });
  await expect(dialog.getByText('replacement.png')).toBeVisible();
  await dialog.getByRole('button', { name: 'Remove picture', exact: true }).click();
  await expect(dialog.getByRole('img')).toHaveCount(0);
  await dialog.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  expect((await productNamed(page, 'PR06 no picture')).imageSha256).toBeNull();
  expect(creates).toBe(1);
  expect(uploads).toBe(0);
  dialog = await newProduct(page, 'PR06 canceled');
  await dialog.getByLabel('Choose product picture').setInputFiles(picture);
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  expect(creates).toBe(1);
  expect(uploads).toBe(0);
});

test('create with picture and opening stock, edit image, archive/restore and preserve archived category', async ({ page, signIn }) => {
  await signIn(OWNER);
  const category = await apiPost<{ id: string }>(page.request, '/api/v1/categories', { name: 'PR06 seasonal' });
  await page.goto('/admin/products');
  let dialog = await newProduct(page, 'PR06 pictured');
  await dialog.getByLabel('Category', { exact: true }).selectOption(category.id);
  await dialog.getByLabel('Choose product picture').setInputFiles(picture);
  await dialog.getByLabel('Quantity', { exact: true }).fill('12.5');
  await dialog.getByLabel('Unit cost').fill('62.5');
  await dialog.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  const product = await productNamed(page, 'PR06 pictured');
  expect(product.imageSha256).toBeTruthy();
  expect(product.qtyOnHand).toBe(12.5);
  expect(product.avgCost).toBe(62.5);
  const downloaded = await page.request.get(`/api/v1/products/${product.id}/image`);
  expect(await downloaded.body()).toEqual(picture.buffer);
  await apiPost(page.request, `/api/v1/setup/categories/${category.id}/archive`, {});
  await page.reload();
  await expect(page.getByRole('region', { name: 'PR06 seasonal', exact: true })).toContainText('PR06 pictured');
  const row = page.getByRole('row').filter({ has: page.getByRole('cell', { name: 'PR06 pictured', exact: true }) });
  await row.getByRole('button', { name: 'Actions for PR06 pictured', exact: true }).click();
  await page.getByRole('menuitem', { name: 'Edit', exact: true }).click();
  dialog = page.getByRole('dialog');
  await expect(dialog.getByLabel('Category', { exact: true })).toHaveValue(category.id);
  // Different bytes guarantee that replacement changes the checksum and URL.
  const replacement = { ...picture, buffer: Buffer.concat([picture.buffer, Buffer.from('replacement')]) };
  await dialog.getByLabel('Choose product picture').setInputFiles(replacement);
  await expect(dialog.locator('img')).not.toHaveAttribute('src', new RegExp(product.imageSha256!));
  await dialog.getByRole('button', { name: 'Remove picture', exact: true }).click();
  await expect(dialog.getByRole('button', { name: 'Add picture', exact: true })).toBeVisible();
  await dialog.getByLabel('Choose product picture').setInputFiles(picture);
  await expect(dialog.getByRole('button', { name: 'Replace picture', exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  expect((await productNamed(page, 'PR06 pictured')).categoryId).toBe(category.id);
  await row.getByRole('button', { name: 'Actions for PR06 pictured', exact: true }).click();
  await page.getByRole('menuitem', { name: 'Archive', exact: true }).click();
  await page.getByRole('button', { name: 'Archive it', exact: true }).click();
  await expect(row).toHaveCount(0);
  await page.getByLabel('Include archived').check();
  const archived = page.getByRole('row').filter({ hasText: 'PR06 pictured' });
  await expect(archived).toContainText('Archived');
  await archived.getByRole('button', { name: 'Restore', exact: true }).click();
  await expect(row).toBeVisible();
  const restored = await productNamed(page, 'PR06 pictured');
  expect(restored.archivedAt).toBeNull();
  expect(restored.imageSha256).toBe(product.imageSha256);
});

test('image failure retries the saved ID once, even after dismissing, without duplicating opening stock', async ({ page, signIn }) => {
  await signIn(OWNER);
  await page.goto('/admin/products');
  let creates = 0;
  const uploadIds: string[] = [];
  let releaseUpload!: () => void;
  const held = new Promise<void>(resolve => { releaseUpload = resolve; });
  await page.route('**/api/v1/products/*/image', async route => {
    if (route.request().method() !== 'POST') return route.continue();
    uploadIds.push(route.request().url().split('/').at(-2)!);
    if (uploadIds.length === 1) {
      await held;
      return route.fulfill({ status: 503, json: { success: false, message: 'Image storage temporarily unavailable.' } });
    }
    return route.continue();
  });
  page.on('request', r => { if (r.method() === 'POST' && r.url().endsWith('/products')) creates++; });
  const dialog = await newProduct(page, 'PR06 retry');
  await dialog.getByLabel('Choose product picture').setInputFiles(picture);
  await dialog.getByLabel('Quantity', { exact: true }).fill('7.5');
  await dialog.getByLabel('Unit cost').fill('30');
  await dialog.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('Product saved, including any opening stock');
  await expect(page.getByText('Product saved, including any opening stock. Only the picture is being uploaded.', { exact: true })).toBeFocused();
  await expect(page.getByText('Uploading picture…', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Close', exact: true }).click();
  releaseUpload();
  await expect(page.getByText(/Its picture still needs uploading/)).toBeVisible();
  const product = await productNamed(page, 'PR06 retry');
  expect(product.imageSha256).toBeNull();
  await page.getByRole('button', { name: 'Review picture' }).click();
  await expect(page.getByRole('dialog').getByRole('alert')).toContainText('Image storage temporarily unavailable.');
  await page.screenshot({ path: '../docs/qa/pr06/after-upload-failure.png', animations: 'disabled' });
  await page.getByRole('button', { name: 'Retry picture upload' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(creates).toBe(1);
  expect(uploadIds).toEqual([product.id, product.id]);
  const saved = await productNamed(page, 'PR06 retry');
  expect(saved.imageSha256).toBeTruthy();
  expect(saved.qtyOnHand).toBe(7.5);
  expect(saved.avgCost).toBe(30);
  const movements = await apiGet<StockMovement[]>(page.request, `/api/v1/products/${product.id}/movements`);
  expect(movements).toHaveLength(1);
  expect(movements[0]).toMatchObject({ reason: 'DELIVERY', quantityDelta: 7.5 });
});

test('catalog loading, empty, fetch error and dense long-label states', async ({ page, signIn }) => {
  await catalogFixtures(page);
  await signIn(OWNER);
  let release!: () => void;
  const held = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/v1/products?*', async route => {
    await held;
    return route.fulfill({ json: { success: true, data: [] } });
  });
  await page.goto('/admin/products');
  await expect(page.getByText('Loading products…', { exact: true })).toBeVisible();
  release();
  await expect(page.getByText('No products yet. Create your first product to get started.')).toBeVisible();
  await page.route('**/api/v1/products?*', route => route.fulfill({ status: 503, json: { success: false, message: 'Catalog unavailable.' } }));
  await page.reload();
  await expect(page.getByRole('alert')).toContainText('Catalog unavailable.');
  await page.route('**/api/v1/products?*', route => route.fulfill({ json: { success: true,
    data: Array.from({ length: 150 }, (_, i) => ({ ...catalogProducts[i % 4], id: `dense-${i}`, name: `Menu item ${i} ${'long-label-'.repeat(7)}` })),
  } }));
  for (const width of [400, 1280]) {
    await page.setViewportSize({ width, height: 900 });
    await page.reload();
    await expect(page.getByRole('button', { name: /^Actions for Menu item/ })).toHaveCount(150);
    await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
    await page.getByRole('button', { name: /^Actions for Menu item 149 / }).click();
    await expect(page.getByRole('menuitem', { name: 'Edit', exact: true })).toBeInViewport();
    await page.keyboard.press('Escape');
  }
});

test('creation errors keep the draft; an image failure can finish without undoing the saved product', async ({ page, signIn }) => {
  await signIn(OWNER);
  await page.goto('/admin/products');
  let attempts = 0;
  await page.route('**/api/v1/products', route => {
    if (route.request().method() !== 'POST') return route.continue();
    attempts++;
    return attempts === 1
      ? route.fulfill({ status: 409, json: { success: false, message: 'A product already uses that name.' } })
      : route.continue();
  });
  await page.route('**/api/v1/products/*/image', route => route.fulfill({ status: 503,
    json: { success: false, message: 'Image storage temporarily unavailable.' },
  }));
  const dialog = await newProduct(page, 'PR06 creation recovery');
  await dialog.getByLabel('Choose product picture').setInputFiles(picture);
  await dialog.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('A product already uses that name.');
  await expect(dialog.getByRole('img', { name: 'Selected product picture' })).toBeVisible();
  await dialog.getByLabel('Name', { exact: true }).fill('PR06 finish without image');
  await dialog.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('dialog').getByRole('alert')).toContainText('Picture upload failed:');
  await page.getByRole('button', { name: 'Finish without picture' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  const product = await productNamed(page, 'PR06 finish without image');
  expect(product.imageSha256).toBeNull();
  expect(product.qtyOnHand).toBe(0);
  await expect(page.getByRole('button', { name: 'New product', exact: true })).toBeEnabled();
});
