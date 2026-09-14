import { test, expect, OWNER, COUNTER, openTable, backdateSession } from './fixtures';

/*
 * THE PRIZE WINNER, end to end and across three screens that have never been driven together.
 *
 * The owner generates a batch, the counter spends one code at checkout, the bill closes at
 * ₱0.00 with no payment row behind it, and the night's figures account for what was given away.
 * Vouchers, the zero-total close and the losses band each had no browser coverage at all, and
 * this is the one path where all three meet.
 *
 * Thirty minutes played against a two-hour code, so the forfeited ninety are a real number the
 * cashier has to say out loud rather than a zero that would pass either way.
 */
test('a prize code closes a bill at nothing and lands in the night as given away', async ({
  page,
  signIn,
  hall,
}) => {
  expect(hall.beerId).toBeTruthy(); // the fixture also turns the counter login on

  // ── The owner makes the prize ──────────────────────────────────────────────
  await signIn(OWNER);
  await page.goto('/admin/vouchers');
  await page.getByRole('button', { name: 'New batch' }).click();

  const expiry = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
  await page.getByLabel('Hours per voucher').fill('2');
  await page.getByLabel('How many').fill('1');
  await page.getByLabel('Expires on').fill(expiry);
  await page.getByLabel('What this giveaway is (optional)').fill('E2E Facebook draw');
  await page.getByRole('button', { name: 'Generate' }).click();

  // The one response that ever carries live codes, and the only moment they are on screen.
  const generated = page.getByRole('dialog', { name: '1 codes generated' });
  await expect(generated).toBeVisible();
  const code = (await generated.getByText(/^SB-[0-9A-Z]{3}-[0-9A-Z]{3}$/).textContent())?.trim();
  expect(code).toMatch(/^SB-[0-9A-Z]{3}-[0-9A-Z]{3}$/);
  await generated.getByRole('button', { name: 'Close' }).click();

  // ── The counter plays the winner in ────────────────────────────────────────
  await signIn(COUNTER);

  /*
   * The free-table warning, checked here because this is the only spec with a rate form within
   * reach -- openTable dismisses the modal immediately, so it is opened by hand and cancelled.
   *
   * ₱0.0001/hour derives ₱0.0000 a minute and bills nothing. That is the documented arithmetic
   * and zero is a legitimate rate, so it is not refused -- but a counter who typed a figure and
   * got a free table has to be told at the point of entry rather than at checkout.
   */
  await page.goto('/floor');
  await page
    .locator('button', { has: page.locator('.figure-table-number', { hasText: /^01$/ }) })
    .click();
  await page.getByRole('button', { name: 'Promo', exact: true }).click();
  await page.getByRole('button', { name: 'Per hour', exact: true }).click();
  await page.getByLabel('Promo rate per hour').fill('0.0001');
  await expect(page.getByText(/rounds to ₱0.0000 per minute/)).toBeVisible();
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();

  const sessionId = await openTable(page, 1);

  // Thirty minutes at ₱4.00/min is ₱120.00 of table time. See the note on backdateSession:
  // this is the one thing in the suite no user could do.
  backdateSession(sessionId, 30);

  await page.getByRole('button', { name: 'Close table & check out' }).click();
  await page.getByRole('button', { name: 'Close & check out' }).click();
  await page.waitForURL(/\/checkout\/[0-9a-f-]{36}/);
  const billId = page.url().split('/checkout/')[1];
  await expect(page.getByText('₱120.00').first()).toBeVisible();

  // ── The code is spent ──────────────────────────────────────────────────────
  await page.getByRole('button', { name: 'Apply voucher' }).click();
  await page.getByLabel('Voucher code').fill(code!);
  await page.getByRole('button', { name: 'Apply voucher' }).click();

  /*
   * The sentence the cashier reads to the customer. Asserted word for word because the whole
   * point of it is that the customer hears "spent" at the counter rather than working it out
   * from a receipt afterwards — and until this test ran, nobody had seen it rendered.
   */
  const redemption = page.getByText(new RegExp(`${code} covered 30 min of table time`));
  await expect(redemption).toBeVisible();
  await expect(redemption).toContainText('₱120.00 off');
  await expect(redemption).toContainText(
    'The remaining 90 min of the voucher are forfeited: there is no change and no balance left on it.',
  );

  // ── Nothing to pay ─────────────────────────────────────────────────────────
  await expect(page.getByText(`Voucher ${code} covered all of it.`)).toBeVisible();
  await page.getByRole('button', { name: 'Nothing to pay — finish' }).click();

  await page.waitForURL(new RegExp(`/receipt/${billId}`));
  // Exact: the checkout's own "Nothing to pay — finish" button is still in the DOM through the
  // morph animation, and the receipt's payment line is the thing being asserted.
  await expect(page.getByText('Nothing to pay', { exact: true })).toBeVisible();

  // ── And the owner sees where the money went ────────────────────────────────
  await signIn(OWNER);

  // The sale is a sale: it took a receipt number and it is on the night's list, at zero.
  await page.goto('/admin/sales');
  const row = page.getByRole('row').filter({ hasText: '₱0.00' }).first();
  await expect(row).toBeVisible();

  // The line that says why this list will not sum to the dashboard's gross. Added because the
  // two figures disagree by design on any night with an unpaid bill, and nothing said so.
  await expect(page.getByText(/will not add up to gross/)).toBeVisible();

  // And the giveaway is accounted for, with the forfeited minutes stated rather than folded
  // into one number — fifty two-hour codes is not fifty times two hours of lost revenue.
  await page.goto('/dashboard');

  // Table use, which no browser test had ever read. Thirty minutes were played on Table 1,
  // and the figure is occupiedMinutes -- wall clock, pauses included -- shown as hours to one
  // decimal. It was called billedMinutes and was reported as a bug three times for looking
  // like the session's charged figure. The section is collapsed until opened, and its
  // header carries the busiest table's share.
  const tableUse = page.getByRole('button', { name: /^Table use/ });
  await expect(tableUse).toHaveText(/Table 1 · \d+%/);
  await tableUse.click();
  const utilisation = page.locator('section').filter({ has: tableUse });
  // A figure, not a blank: a renamed field the client did not follow renders " h" with
  // nothing in front of it. Scoped to the section because "0.5 h" would also match a table
  // NAMED "Table 3" followed by "0.0 h" on the row below.
  await expect(utilisation.getByText(/^\d+\.\d h ·/).first()).toBeVisible();

  // The giveaway section is collapsed too, and its header is the night's total.
  await page.getByRole('button', { name: /^Given away/ }).click();
  await page.getByRole('button', { name: /Vouchers/ }).click();
  const detail = page.getByText(new RegExp(`^${code}`));
  await expect(detail).toBeVisible();
  await expect(page.getByText('30 min covered of 2 hours · 90 min forfeited')).toBeVisible();
  await expect(page.getByText('E2E Facebook draw')).toBeVisible();
});
