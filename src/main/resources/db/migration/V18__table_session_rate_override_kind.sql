-- Which kind of override it was: a promo, or a favour.
--
-- ONE DISCRIMINATOR, NOT A SECOND MECHANISM. Everything about how money is computed is
-- unchanged: a promo writes rate_override_per_minute / rate_override_per_hour through the same
-- path the friend rate uses, snapshots onto session_segment.rate_per_minute the same way, and
-- the forgone-revenue arithmetic in DAILY_REPORT_SQL and LOSSES_DETAIL_SQL stays per-minute and
-- untouched. This column only says which kind it was.
--
-- Without it the report cannot tell a promo from a favour: the `overrides` CTE reported every
-- override as one lump, so a happy hour and a free game for the owner's friend were the same
-- figure. The alternative the counter had was to invent a "Promo" customer type, which files a
-- happy-hour walk-in as customer type Promo and destroys the record of who they actually were.
-- A promo is a PRICING MODE, not a kind of customer.

-- An ENUM and not a lookup table, by V1's own rule: enums for closed sets that change with a
-- deploy, lookup tables for sets the ADMIN manages at runtime (customer types, product
-- categories). This is the former -- staff pick from a fixed pair, there is no screen for
-- adding a third. If the owner later wants a Student rate that is a migration, and that is the
-- honest cost of keeping this out of the admin's hands.
CREATE TYPE rate_override_kind AS ENUM ('FRIEND', 'PROMO');

ALTER TABLE table_session
  ADD COLUMN rate_override_kind rate_override_kind;

-- Every override written before today was a friend rate: it is the only kind the counter could
-- enter. Backfilled BEFORE the constraint below, deliberately -- the constraint is then the
-- PROOF the backfill was complete, because a row this UPDATE missed fails the migration here
-- rather than shipping. Left null instead, those rows would be invisible to the new grouping
-- and the report would under-count a night the owner has already read.
UPDATE table_session
   SET rate_override_kind = 'FRIEND'
 WHERE rate_override_per_minute IS NOT NULL;

-- Neither half means anything alone.
--
-- An override without a kind is invisible to the grouping -- it would fall out of both the
-- promo and the friend figures and reconcile against nothing. A kind without an override prices
-- nothing at all: it is a label on a session billed at the standard rate, which is a lie in a
-- column rather than a discount. Written as an equality between two IS NULL tests, the same
-- all-or-nothing shape as table_session_flat_together_chk, rather than two one-way checks.
ALTER TABLE table_session
  ADD CONSTRAINT table_session_override_kind_together_chk
  CHECK ((rate_override_per_minute IS NULL) = (rate_override_kind IS NULL));

COMMENT ON COLUMN table_session.rate_override_kind IS
  'Which kind of rate override this session carries: PROMO (happy hour -- an event, ungated, any customer type, reason required) or FRIEND (a favour -- gated on customer_type.allows_rate_override, reason optional). NEVER used to compute money: the charge comes from rate_override_per_minute either way, and this column exists so the report can tell a promo from a favour. Null exactly when there is no override.';
