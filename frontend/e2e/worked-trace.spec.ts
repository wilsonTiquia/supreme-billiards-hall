import { test, expect, COUNTER, BEER, SISIG, openTable, addToBill, backdateSession } from './fixtures';

/*
 * THE WORKED TRACE — BACKEND-SPEC.md §4, acceptance test 1.
 *
 * Table 3 at ₱4.00/min for ninety minutes, two beers and a sisig, one beer voided, closed and
 * paid by GCash: ₱360.00 of table time plus ₱270.00 of items is ₱630.00. It is the oldest
 * assertion in the project and the criterion for the whole thing — and until now it had only
 * ever been proved through MockMvc. This drives the same figures through the screens the
 * counter actually uses.
 *
 * The ninety minutes are the one thing that cannot be clicked: see backdateSession.
 */
test('the worked trace comes to ₱630.00 through the UI', async ({ page, signIn, hall }) => {
  expect(hall.beerId).toBeTruthy();
  await signIn(COUNTER);

  const sessionId = await openTable(page, 3);

  // 2 × beer, 1 × sisig.
  await addToBill(page, BEER.name);
  await addToBill(page, BEER.name);
  await addToBill(page, SISIG.name);
  await expect(page.getByText('2 × ₱90.00')).toBeVisible();
  await expect(page.getByText('1 × ₱180.00')).toBeVisible();

  // ── Void one beer, with a reason ───────────────────────────────────────────
  await page.getByRole('button', { name: 'Void one…' }).click();
  await page.getByRole('button', { name: 'Void', exact: true }).first().click();
  await page.getByLabel('Reason').fill('Customer changed order');
  await page.getByRole('button', { name: 'Void line' }).click();

  // Retained and struck through, never deleted — the rule the audit trail rests on.
  await expect(page.getByText('Voided — Customer changed order')).toBeVisible();

  // ── Ninety minutes, then close ─────────────────────────────────────────────
  backdateSession(sessionId, 90);
  await page.getByRole('button', { name: 'Close table & check out' }).click();
  await page.getByRole('button', { name: 'Close & check out' }).click();
  await page.waitForURL(/\/checkout\/[0-9a-f-]{36}/);

  // ₱360.00 time + ₱270.00 items. The voided beer is on the bill and out of the total.
  await expect(page.getByText('₱360.00').first()).toBeVisible();
  await expect(page.getByText('₱630.00').first()).toBeVisible();
  await expect(page.getByText('Voided — Customer changed order')).toBeVisible();

  // ── Paid by GCash ──────────────────────────────────────────────────────────
  await page.getByRole('button', { name: 'GCash' }).click();
  await page.getByLabel('Reference number').fill('GC-E2E-TRACE');
  await page.getByRole('button', { name: 'Take ₱630.00' }).click();

  await page.waitForURL(/\/receipt\/[0-9a-f-]{36}/);
  await expect(page.getByText('₱630.00').first()).toBeVisible();
  await expect(page.getByText('GC-E2E-TRACE')).toBeVisible();
});
