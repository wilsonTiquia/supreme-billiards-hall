-- Free table time, given away as a prize and redeemed at the counter.
--
-- A social-media giveaway or a tournament placing. The winner shows a screenshot, reads out a
-- code, the cashier types it in. What the code is worth is TIME, not money: "2 hours" covers up
-- to two hours of table time at whatever that table charges, so the same code is worth PHP 480
-- on a PHP 240/hour table and PHP 300 on a PHP 150/hour one. Play three hours and pay for one.
--
-- WHY TIME AND NOT PESOS. A peso voucher would have to be re-valued every time the owner
-- changed a table rate, and a code printed in September would quietly stop being the prize that
-- was advertised. Two hours is two hours whatever the rate does.
--
-- THE UNUSED PART IS FORFEITED. Play 90 minutes on a two-hour voucher and the remaining 30 are
-- gone: no change, no residual balance, the code is spent. That is what makes this a voucher
-- rather than an account, and it is the reason there is no `minutes_remaining` column here to
-- decrement -- a redemption is one event, not a running balance, and the redeemed_at pair below
-- is the whole of its state.
--
-- REDEEMED AT CHECKOUT, not at session start. The table runs normally and the voucher is
-- produced when it is time to pay, which is also why redemption reads the bill's TIME lines:
-- by then they exist and carry the rate each was actually billed at.

CREATE TABLE voucher_batch (
  id          uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id   uuid    NOT NULL REFERENCES branch(id),

  -- MINUTES, though the owner types hours. Everything in this system bills in minutes --
  -- bill_line.billed_minutes, table_session.billed_minutes, session_segment rates -- and a
  -- voucher measured in hours would be the one figure needing a conversion at every use.
  -- The conversion happens once, in the service, on the way in.
  minutes     integer NOT NULL,
  quantity    integer NOT NULL,
  expires_on  date    NOT NULL,
  note        text,

  created_by  uuid    NOT NULL REFERENCES app_user(id),
  created_at  timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT voucher_batch_branch_id_key UNIQUE (branch_id, id),
  CONSTRAINT voucher_batch_minutes_chk   CHECK (minutes > 0),

  -- A ceiling on one batch, not on the hall's generosity: the codes are generated and returned
  -- in a single transaction for the owner to copy or print, and a batch of fifty is the real
  -- case. 500 is far beyond any run this hall will do while keeping that one insert and that
  -- one response sane. Same posture as the 200-row page ceiling on the audit log and the sales
  -- list -- a screen, not a download.
  CONSTRAINT voucher_batch_quantity_chk  CHECK (quantity > 0 AND quantity <= 500)
);

CREATE TABLE voucher (
  id               uuid PRIMARY KEY DEFAULT uuidv7(),
  branch_id        uuid    NOT NULL REFERENCES branch(id),
  batch_id         uuid    NOT NULL,

  -- Stored NORMALISED: uppercase, prefix included, no separators -- "SB7K4M2Q". The cashier
  -- may type "sb 7k4-m2q" off a phone screen at 1am and the service normalises before it
  -- looks up, so this column is the one thing that ever gets matched. That is what lets the
  -- uniqueness below be a plain unique index rather than a functional one on lower(code), and
  -- it stops two codes existing that differ only in punctuation.
  code             text    NOT NULL,

  -- SNAPSHOTS, and the reason is bill_line.unit_price's reason. The owner editing a batch next
  -- month -- correcting a typo in the expiry, changing the hours on a future run -- must not
  -- change what a code printed last month is worth. Redemption reads these two columns and
  -- never joins back to voucher_batch for them; the batch is there to group and to count.
  minutes          integer NOT NULL,
  expires_on       date    NOT NULL,

  -- The whole of a voucher's state. Null means outstanding; set means spent, and it is set
  -- once, by a conditional UPDATE, and never unset except by an explicit release.
  redeemed_at      timestamptz,
  redeemed_by      uuid    REFERENCES app_user(id),
  redeemed_bill_id uuid,

  created_at       timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT voucher_branch_id_key UNIQUE (branch_id, id),

  -- THE constraint that makes single use real, and the check is the index rather than a
  -- SELECT: two tills racing the same code is exactly what a read-then-write loses. The
  -- service redeems with UPDATE ... WHERE redeemed_at IS NULL and treats zero rows affected as
  -- the already-redeemed refusal, the same posture table_session_one_open_per_table_key states
  -- for opening a table. This unique index is the other half -- it is what stops a second row
  -- carrying the same code being inserted to get around it.
  CONSTRAINT voucher_code_key UNIQUE (branch_id, code),

  CONSTRAINT voucher_batch_fk
    FOREIGN KEY (branch_id, batch_id) REFERENCES voucher_batch (branch_id, id),
  -- Composite, carrying branch_id, like every other cross-table reference here: a code from
  -- one branch can never be redeemed against another branch's bill, and the database says so
  -- rather than the service remembering to.
  CONSTRAINT voucher_bill_fk
    FOREIGN KEY (branch_id, redeemed_bill_id) REFERENCES bill (branch_id, id),

  CONSTRAINT voucher_minutes_chk CHECK (minutes > 0),

  -- All three or none. The same paired shape bill_line_void_pair_chk uses, and for the same
  -- reason: a code marked spent with nobody's name against it and no bill to point at is the
  -- one row this table must not be able to hold. It is also what makes releasing a voucher a
  -- single legal statement -- all three back to null together, or none of them.
  CONSTRAINT voucher_redemption_together_chk CHECK (
    num_nonnulls(redeemed_at, redeemed_by, redeemed_bill_id) IN (0, 3)
  )
);

-- What is still in the wild, per batch and by expiry. Partial on the unredeemed rows because
-- that is the question the batch screen asks -- a full index would mostly be pages of codes
-- that were spent months ago.
CREATE INDEX voucher_outstanding_idx
  ON voucher (branch_id, batch_id, expires_on) WHERE redeemed_at IS NULL;
-- The other direction: which voucher was used on this bill, for the receipt and the release.
CREATE INDEX voucher_redeemed_bill_idx
  ON voucher (redeemed_bill_id) WHERE redeemed_bill_id IS NOT NULL;

COMMENT ON TABLE  voucher IS 'One giveaway code, worth up to `minutes` of standard-rate table time on one bill. Single use, enforced by a conditional UPDATE on redeemed_at rather than by a pre-check.';
COMMENT ON COLUMN voucher.code IS 'Normalised: uppercase, prefixed, no separators. The service normalises input before matching, so this is the only form that ever exists.';
COMMENT ON COLUMN voucher.minutes IS 'Snapshotted from the batch at generation. Editing the batch afterwards must never change what an already-printed code is worth.';
COMMENT ON COLUMN voucher.expires_on IS 'Snapshotted from the batch, for the same reason as minutes. Inclusive: a code expiring 31 October is good all of 31 October.';
COMMENT ON COLUMN voucher.redeemed_bill_id IS 'The bill it was spent on. Deliberately redundant with bill.voucher_id -- each is the natural read direction, and neither is cheap to derive from the other.';


-- =====================================================================
-- WHAT IT DOES TO THE BILL
-- =====================================================================

-- The THIRD subtraction on a bill, and independent of the other two.
--
-- V5's billed_minutes_override changes what the TIME lines cost. V19's discount_amount reaches
-- the whole payable, beer included. This one covers a measured quantity of TIME at the rate
-- that time was actually billed at, and stops there. All three can sit on one bill and each is
-- reported on its own row of the losses band.
--
-- NOT A NEGATIVE bill_line, and that door is closed on purpose: bill_line_quantity_chk requires
-- quantity > 0 and bill_line_price_chk requires unit_price >= 0, so a bill line can only ever
-- add. A negative line would also land in top_items, in the void arithmetic and in every
-- description-grouped report as a phantom product. The subtraction belongs on the bill.
ALTER TABLE bill
  ADD COLUMN voucher_amount          numeric(12,2) NOT NULL DEFAULT 0,
  ADD COLUMN voucher_id              uuid,
  ADD COLUMN voucher_minutes_covered integer;

ALTER TABLE bill ADD CONSTRAINT bill_voucher_fk
  FOREIGN KEY (branch_id, voucher_id) REFERENCES voucher (branch_id, id);

COMMENT ON COLUMN bill.voucher_amount IS 'Table time covered by a voucher, in pesos, computed at redemption from the bill''s TIME lines. Zero, not null, when none was used -- it is subtracted from every total.';
COMMENT ON COLUMN bill.voucher_minutes_covered IS 'Minutes the voucher actually covered, which is at most voucher.minutes. The difference between the two is what the customer forfeited, and it cannot be reconstructed once the lines are frozen.';

ALTER TABLE bill ADD CONSTRAINT bill_voucher_amount_chk CHECK (voucher_amount >= 0);

-- All three together or none, and the amount cannot exist without the code that produced it.
-- Same shape as bill_discount_together_chk. Releasing a voucher puts all three back to null in
-- one statement, so there is no moment at which a bill carries an amount from a code it no
-- longer points at.
ALTER TABLE bill ADD CONSTRAINT bill_voucher_together_chk CHECK (
  num_nonnulls(voucher_id, voucher_minutes_covered) IN (0, 2)
  AND (voucher_amount = 0 OR voucher_id IS NOT NULL)
);

/*
 * The two reductions together can never exceed what was being charged.
 *
 * This REPLACES bill_discount_within_subtotal_chk, which bounded the discount alone. With a
 * voucher on the bill that bound is no longer the real one: a 654-peso bill carrying a
 * 480-peso voucher can only give away 174 more before the total goes negative, and the old
 * check would have admitted a 600-peso discount on top.
 *
 * Dropped and re-added rather than added beside, because two overlapping bounds on the same
 * arithmetic is how one of them silently stops being true. V19 is frozen -- Flyway checksums
 * it -- so this is the only way to correct it.
 *
 * Still conditioned on the bill being finalised, for V19's reason, which has not changed:
 * subtotal_time and subtotal_items are zero for the whole life of an OPEN bill, and both
 * reductions are taken BEFORE checkout writes them. An unconditional version would reject
 * every voucher and every discount ever applied, on a row where both sides read 0.00. The live
 * bound lives in the service, which is the only place the figures exist yet.
 */
ALTER TABLE bill DROP CONSTRAINT bill_discount_within_subtotal_chk;

ALTER TABLE bill ADD CONSTRAINT bill_reductions_within_subtotal_chk CHECK (
  status NOT IN ('CLOSED', 'UNSETTLED')
  OR discount_amount + voucher_amount <= subtotal_time + subtotal_items
);

-- The losses band's sixth section: every bill a voucher was spent on, for one business day.
-- Partial, like bill_discount_idx, because a redeemed voucher is the rare row.
CREATE INDEX bill_voucher_idx
  ON bill (branch_id, business_date) WHERE voucher_amount > 0;
