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

  // And the giveaway is accounted for, with the forfeited minutes stated rather than folded
  // into one number — fifty two-hour codes is not fifty times two hours of lost revenue.
  await page.goto('/dashboard');
  await page.getByRole('button', { name: /Vouchers/ }).click();
  const detail = page.getByText(new RegExp(`^${code}`));
  await expect(detail).toBeVisible();
  await expect(page.getByText('30 min covered of 2 hours · 90 min forfeited')).toBeVisible();
  await expect(page.getByText('E2E Facebook draw')).toBeVisible();
});
