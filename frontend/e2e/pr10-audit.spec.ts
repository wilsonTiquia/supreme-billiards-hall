import { test, expect, OWNER, apiAs, apiPost } from './fixtures';
import { auditFixtures } from './pr10-fixtures';

test.use({ hasTouch: true });

test('row clicks, Enter and Space expand only the selected event and preserve its details', async ({ page, signIn }) => {
  await auditFixtures(page);
  await signIn(OWNER);
  await page.goto('/admin/audit');
  const table = page.getByRole('table', { name: 'Audit log', exact: true });
  const product = table.getByRole('row').filter({ hasText: /Product updated.*San Miguel Pale Pilsen/ });
  const toggle = product.getByRole('button');
  const stock = table.getByRole('row').filter({ hasText: /Stock corrected.*San Miguel Pale Pilsen/ });
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await product.getByRole('cell').nth(1).click();
  await expect(toggle).toHaveAttribute('aria-expanded', 'true');
  await expect(stock.getByRole('button')).toHaveAttribute('aria-expanded', 'false');
  const region = page.getByRole('region', { name: /Product updated San Miguel/ });
  await expect(region).toHaveAttribute('id', (await toggle.getAttribute('aria-controls'))!);
  await expect(region).toContainText('₱85.00');
  await expect(region).toContainText('₱90.00');
  await region.getByText('Selling price', { exact: true }).click();
  await expect(toggle).toHaveAttribute('aria-expanded', 'true');
  // Details are a sibling row: future links/buttons cannot bubble into the summary toggle.
  await region.evaluate(element => {
    element.insertAdjacentHTML('beforeend', '<a href="#detail-action">Detail link</a><button>Detail action</button>');
  });
  await region.getByRole('link', { name: 'Detail link' }).click();
  await region.getByRole('button', { name: 'Detail action' }).click();
  await expect(toggle).toHaveAttribute('aria-expanded', 'true');
  await toggle.focus();
  await page.keyboard.press('Enter');
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await expect(toggle).toBeFocused();
  await page.keyboard.press('Space');
  await expect(toggle).toHaveAttribute('aria-expanded', 'true');
  await toggle.click();
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await stock.getByRole('cell').nth(2).click();
  await expect(page.getByRole('region', { name: /Stock corrected/ })).toContainText('Stock -2');
  await expect(page.getByRole('region', { name: /Stock corrected/ })).toContainText('Two damaged bottles');
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  const rate = table.getByRole('row').filter({ hasText: 'League players rate' });
  await rate.getByRole('cell').last().click();
  const rateDetails = page.getByRole('region', { name: /League players rate/ });
  await expect(rateDetails.getByRole('row')).toHaveCount(2);
  await expect(rateDetails).toContainText('₱240.00 / hour');
  await expect(rateDetails).toContainText('₱5.00 / min');
  await expect(rate).toContainText('Table 2');
  await expect(rate).not.toContainText('Table Table 2');
  const empty = table.getByRole('row').filter({ hasText: 'Staff archived' });
  await empty.click();
  await expect(empty.getByRole('button')).toHaveCount(0);
  await expect(empty).toContainText('system');
});

test('expansion follows event identity across pages and filters keep API ordering and counts', async ({ page, signIn }) => {
  await auditFixtures(page);
  await signIn(OWNER);
  await page.goto('/admin/audit');
  await page.getByLabel('What happened').selectOption('PRODUCT_UPDATED');
  await page.getByLabel('Who', { exact: true }).selectOption('owner-id');
  const first = page.getByRole('button', { name: /Details for Product updated Product 51/ });
  await first.click();
  const nav = page.getByRole('navigation', { name: 'Audit pages' });
  await expect(nav).toContainText('51 entries');
  await expect(nav.getByRole('button', { name: 'Previous' })).toBeDisabled();
  const request = page.waitForRequest(r => r.url().includes('/audit/feed?') && new URL(r.url()).searchParams.get('page') === '1');
  await nav.getByRole('button', { name: 'Next' }).click();
  const query = new URL((await request).url()).searchParams;
  expect(Object.fromEntries(query)).toEqual({ action: 'PRODUCT_UPDATED', actor: 'owner-id', page: '1', size: '25' });
  await expect(page.getByRole('button', { name: /Details for Product updated Product 26/ })).toHaveAttribute('aria-expanded', 'false');
  await expect(page.getByRole('region', { name: /^Details for/ })).toHaveCount(0);
  await nav.getByRole('button', { name: 'Previous' }).click();
  await expect(page.getByRole('button', { name: /Hide for Product updated Product 51/ })).toHaveAttribute('aria-expanded', 'true');
  await nav.getByRole('button', { name: 'Next' }).click();
  await nav.getByRole('button', { name: 'Next' }).click();
  await expect(nav).toContainText('Page 3 of 3');
  await expect(nav.getByRole('button', { name: 'Next' })).toBeDisabled();
  await expect(page.getByRole('button', { name: /Details for Product updated Oldest product/ })).toHaveAttribute('aria-expanded', 'false');
  await page.getByLabel('What happened').selectOption('');
  await expect(nav).toContainText('Page 1 of 3');
  await expect(page.getByLabel('Who', { exact: true })).toHaveValue('owner-id');
});

test('loading, empty and failed feeds do not show stale events or pagination', async ({ page, signIn }) => {
  await auditFixtures(page);
  await signIn(OWNER);
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/v1/audit/feed?*', async route => {
    await gate;
    await route.fulfill({ status: 400, json: { success: false, message: 'Audit unavailable for test' } });
  });
  await page.goto('/admin/audit');
  await expect(page.getByText('Loading the trail…')).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Audit pages' })).toHaveCount(0);
  release();
  await expect(page.getByText('Audit unavailable for test')).toBeVisible();
  await expect(page.getByRole('table', { name: 'Audit log' })).toHaveCount(0);
  await page.route('**/api/v1/audit/feed?*', route => route.fulfill({ json: { success: true, data: {
    content: [], page: 0, size: 25, totalElements: 0, totalPages: 0,
  } } }));
  await page.reload();
  await expect(page.getByText('Nothing recorded for that filter.')).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Audit pages' })).toHaveCount(0);
});

test('long payloads wrap without moving columns in light and dark at desktop and phone widths', async ({ page, signIn }) => {
  await auditFixtures(page);
  await signIn(OWNER);
  for (const width of [1280, 400]) for (const theme of ['light', 'dark']) {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(t => localStorage.setItem('supreme.theme.admin', t), theme);
    await page.goto('/admin/audit');
    const table = page.getByRole('table', { name: 'Audit log', exact: true });
    const row = table.getByRole('row').filter({ hasText: 'Tournament refreshments' });
    await expect(row).toBeVisible();
    const before = await row.getByRole('cell').evaluateAll(cells => cells.map(cell => ({ x: cell.getBoundingClientRect().x, width: cell.getBoundingClientRect().width })));
    const button = row.getByRole('button');
    const box = await button.boundingBox();
    expect(box!.height).toBeGreaterThanOrEqual(44);
    expect(box!.width).toBeGreaterThanOrEqual(44);
    await button.tap();
    const region = page.getByRole('region', { name: /Product updated Tournament/ });
    await expect(region).toContainText('B'.repeat(180));
    await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width);
    expect(await row.getByRole('cell').evaluateAll(cells => cells.map(cell => ({ x: cell.getBoundingClientRect().x, width: cell.getBoundingClientRect().width })))).toEqual(before);
    expect(await region.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);
    await button.focus();
    await page.keyboard.press('Space');
    await expect(button).toHaveAttribute('aria-expanded', 'false');
  }
});

test('real scratch audit creation and stock delivery expand with server values', async ({ page, signIn }) => {
  const owner = await apiAs(OWNER);
  try {
    const product = await apiPost<{ id: string }>(owner, '/api/v1/products', {
      name: 'PR10 audit product', sellingPrice: 95,
    });
    await apiPost(owner, '/api/v1/stock/deliveries', {
      supplierName: 'Audit test supplier', note: 'PR10 delivery note',
      lines: [{ productId: product.id, quantity: 12, unitCost: 60 }],
    });
  } finally { await owner.dispose(); }
  await signIn(OWNER);
  await page.goto('/admin/audit');
  const table = page.getByRole('table', { name: 'Audit log', exact: true });
  const product = table.getByRole('row').filter({ hasText: /Product added.*PR10 audit product/ });
  await product.getByRole('cell').nth(2).click();
  await expect(page.getByRole('region', { name: /Product added.*PR10 audit product/ })).toContainText('₱95.00');
  const stock = table.getByRole('row').filter({ hasText: /Delivery.*PR10 audit product/ });
  await stock.getByRole('button').focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('region', { name: /Delivery.*PR10 audit product/ })).toContainText('Stock +12');
});
