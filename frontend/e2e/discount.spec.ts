import { test, expect, COUNTER, BEER, openTable, addToBill, backdateSession } from './fixtures';

/*
 * CHARGING A BILL DOWN, and the refusal when the whole amount is typed.
 *
 * The field asks for what is being CHARGED, not what is coming off — and the obvious mistake is
 * to read it the other way and type the full figure, which is what the "Adjust total" button
 * helpfully pre-fills. That refusal's wording was rewritten (it used to tell the cashier the
 * bill would come to nothing, which was untrue about the money in front of them) and approved
 * without anybody seeing it on a screen. This is the test that looks at it.
 */
test('a bill is charged down with a reason, and the full amount is refused', async ({
  page,
  signIn,
  hall,
}) => {
  expect(hall.beerId).toBeTruthy();
  await signIn(COUNTER);

  // 30 min at ₱4.00/min plus one ₱90.00 beer — ₱210.00 on the bill.
  const sessionId = await openTable(page, 2);
  await addToBill(page, BEER.name);
  await expect(page.getByText('₱90.00').first()).toBeVisible();
  backdateSession(sessionId, 30);

  await page.getByRole('button', { name: 'Close table & check out' }).click();
  await page.getByRole('button', { name: 'Close & check out' }).click();
  await page.waitForURL(/\/checkout\/[0-9a-f-]{36}/);
  await expect(page.getByText('₱210.00').first()).toBeVisible();

  // ── The mistake first: type the whole amount ───────────────────────────────
  // "Adjust total" pre-fills the field with the full total, so submitting it unchanged is
  // exactly the mis-reading this refusal exists for — no typing required to reach it.
  await page.getByRole('button', { name: 'Adjust total' }).click();
  await expect(page.getByLabel('Amount to charge')).toHaveValue('210');
  await page.getByLabel('Why').fill('Regular');
  // Named in full: "Charge less time" (the other control on this screen) also starts with it.
  await page.getByRole('button', { name: 'Charge ₱210.00' }).click();

  await expect(page.getByText('That is the full amount, so there is no discount to record.')).toBeVisible();

  // ── Then the real thing ────────────────────────────────────────────────────
  await page.getByLabel('Amount to charge').fill('150');
  await page.getByLabel('Why').fill('Regular — owner said make it 150');
  await page.getByRole('button', { name: 'Charge ₱150.00' }).click();

  // The server did the subtraction; this is its figure coming back, not one worked out here.
  await expect(page.getByText(/₱60\.00 off · counter/)).toBeVisible();
  await expect(page.getByText('₱150.00').first()).toBeVisible();

  // And the discounted figure is what gets collected.
  await page.getByRole('button', { name: 'Cash', exact: true }).click(); // 'GCash' contains it
  await page.getByLabel('Tendered').fill('150');
  await page.getByRole('button', { name: 'Take ₱150.00' }).click();
  await page.waitForURL(/\/receipt\/[0-9a-f-]{36}/);
  await expect(page.getByText('₱150.00').first()).toBeVisible();
});
