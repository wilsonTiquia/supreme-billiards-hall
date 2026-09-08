-- The bill comes to 654, the customer asks for 600, the owner says yes.
--
-- A SECOND and independent giveaway route, not a variant of the first. V5's
-- billed_minutes_override changes what the TIME lines cost, and can only ever reach table
-- time. This reaches the whole payable -- the beer and the sisig included -- and the two
-- compose: a bill can carry both, and each is reported on its own row of the losses band.
--
-- WHY A FIXED PESO AMOUNT AND NOT A RATE. A percentage would re-derive itself every time a
-- line was added, so the round number the customer was promised at the counter would stop
-- being round the moment somebody ordered another beer. The amount is agreed once, in pesos,
-- and it stays where it was put. Add a 50-peso beer to a bill discounted by 54 and the total
-- goes to 650; the discount is still 54.
--
-- WHY ANYONE MAY DO IT. The owner is not at the hall most nights, so a discount that needed
-- his approval would either not happen or would happen off the books. This follows the posture
-- table_session.rate_override_approved_by already states in its own comment: employees may
-- give away what the situation calls for, and the control is detection rather than
-- prevention -- a mandatory reason, a named actor, an audit row, and a figure on the
-- dashboard the owner reads the next morning.
ALTER TABLE bill
  ADD COLUMN discount_amount numeric(12,2) NOT NULL DEFAULT 0,
  ADD COLUMN discount_reason text,
  ADD COLUMN discount_by     uuid REFERENCES app_user(id),
  ADD COLUMN discount_at     timestamptz;

COMMENT ON COLUMN bill.discount_amount IS
  'Pesos taken off the whole bill at the counter. A FIXED amount, never a rate: adding a line afterwards raises the total and leaves this where it is. Zero, not null, when none was given -- it is subtracted from every total.';
COMMENT ON COLUMN bill.discount_reason IS
  'Why. Required whenever the discount is non-zero -- with no approval step, the reason and the actor are the entire control.';
COMMENT ON COLUMN bill.discount_by IS
  'Who agreed it. Resolved server-side from the authenticated user, never sent by the client.';
COMMENT ON COLUMN bill.discount_at IS
  'When it was agreed. Always before closed_at: the discount is taken on a live bill and the checkout that follows reads the discounted total.';

-- Never negative. A negative discount is a surcharge, which this hall does not do and which
-- the service refuses in the same breath: the counter types the amount being CHARGED, and a
-- charge above the subtotal is rejected rather than stored as a negative here.
ALTER TABLE bill ADD CONSTRAINT bill_discount_amount_chk CHECK (discount_amount >= 0);

-- All three of the context columns or none of them, and all three whenever there is money
-- involved. Same shape as V5's table_session_time_override_together_chk and V1's rate
-- override: a giveaway with no name against it is the one row this system must not be able
-- to hold.
ALTER TABLE bill ADD CONSTRAINT bill_discount_together_chk CHECK (
  num_nonnulls(discount_reason, discount_by, discount_at) IN (0, 3)
  AND (discount_amount = 0 OR discount_reason IS NOT NULL)
);

/*
 * The discount can never exceed what was being charged -- CONDITIONED ON THE BILL BEING
 * FINALISED, which is not pedantry but the only form of this check that can hold.
 *
 * subtotal_time and subtotal_items are zero for the whole life of an OPEN bill. Nothing
 * writes them until CheckoutServiceImpl.finalise sums the live lines at checkout; until then
 * the running figures exist only in the response DTO. The discount is taken BEFORE that, on a
 * live bill, which is the entire point of it. An unconditional
 * `discount_amount <= subtotal_time + subtotal_items` would therefore reject every discount
 * ever applied, on a table where both sides read 0.00.
 *
 * So the real bound lives in the service, where the lines are actually summed and where the
 * refusal can say what the subtotal was. This is the backstop for a row written by SQL
 * outside the application, and it fires from the moment the totals become real.
 *
 * V5 settled this pattern for the same reason: table_session_time_override_range_chk bounds
 * the override by billed_minutes only when billed_minutes is known, and leaves the live check
 * to the service that knows it.
 */
ALTER TABLE bill ADD CONSTRAINT bill_discount_within_subtotal_chk CHECK (
  status NOT IN ('CLOSED', 'UNSETTLED')
  OR discount_amount <= subtotal_time + subtotal_items
);

-- The losses band's fifth section: every discounted bill on one business day. Partial, like
-- bill_unsettled_idx, because discounts are the rare row and a full index would mostly be
-- pages of bills that were charged in full.
CREATE INDEX bill_discount_idx
  ON bill (branch_id, business_date) WHERE discount_amount > 0;
