import { test, expect, COUNTER, BEER, SISIG, apiAs, apiGet, openTable, addToBill } from './fixtures';

/*
 * WHAT THE COUNTER MUST NEVER SEE — BACKEND-SPEC.md §4, acceptance test 7.
 *
 * Employees never see purchase price, average cost, unit cost, total cost or profit. The
 * protection is that the API omits the keys for an EMPLOYEE, not that the UI hides them, so the
 * assertions here are made against the SERIALISED PAGE rather than against what is visible:
 * a figure rendered into the DOM and hidden with CSS would pass a "cannot see it" check and
 * still be one View Source away from the whole cost base.
 */
test('an employee sees no cost or profit on any screen they can reach', async ({
  page,
  signIn,
  hall,
}) => {
  expect(hall.beerId).toBeTruthy();
  await signIn(COUNTER);

  const costFigures = [BEER.cost.toFixed(2), SISIG.cost.toFixed(2)]; // 62.50 and 95.00

  /** Nothing costed anywhere in the DOM, and no cost or profit label in what is on screen. */
  async function assertNothingCosted(where: string) {
    const html = await page.content();
    for (const figure of costFigures) {
      expect(html, `${figure} is in the ${where} markup`).not.toContain(figure);
    }
    const visible = await page.locator('body').innerText();
    expect(visible, `a cost label is on the ${where}`).not.toMatch(/\bcosts?\b/i);
    expect(visible, `a profit label is on the ${where}`).not.toMatch(/\bprofits?\b/i);
  }

  // The product grid — where the counter spends the night, and the one screen that renders a
  // per-product figure at all.
  await openTable(page, 4);
  await addToBill(page, BEER.name);
  await addToBill(page, SISIG.name);
  await expect(page.getByText('1 × ₱90.00')).toBeVisible();
  await expect(page.getByText('1 × ₱180.00')).toBeVisible();
  await assertNothingCosted('session screen');

  // The checkout, where an admin gets totalCost and grossProfit on the same shape.
  await page.getByRole('button', { name: 'Close table & check out' }).click();
  await page.getByRole('button', { name: 'Close & check out' }).click();
  await page.waitForURL(/\/checkout\/[0-9a-f-]{36}/);
  await expect(page.getByText('₱270.00').first()).toBeVisible();
  await assertNothingCosted('checkout screen');

  // The screens that carry the figures are not reachable either — a counter typing the address
  // in gets the floor, not the night's profit.
  await page.goto('/dashboard');
  await expect(page).not.toHaveURL(/dashboard/);

  /*
   * And the reason all of the above holds: the keys are ABSENT from the payload, not null and
   * not hidden. This is the actual guarantee — the DOM assertions above are what the owner can
   * check by looking, this is what makes them true.
   */
  const counter = await apiAs(COUNTER);
  const products = await apiGet<Array<Record<string, unknown>>>(counter, '/api/v1/products');
  expect(products.length).toBeGreaterThan(0);
  for (const product of products) {
    expect(Object.keys(product)).not.toContain('avgCost');
  }
  await counter.dispose();
});
