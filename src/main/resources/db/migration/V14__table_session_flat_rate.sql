-- Tournament pricing: PHP 500 for the table, however long the session runs.
--
-- Set AT SESSION START and stored on the session, a third choice beside the standard rate and
-- the friend rate. The pool table's own configuration is never touched, so there is nothing to
-- remember to switch back when the tournament ends -- the next session on that table bills
-- normally because nothing about the table ever changed.
--
-- The timer still runs and the minutes are still recorded. What stops growing is the charge.
ALTER TABLE table_session
  ADD COLUMN flat_amount      numeric(12,2),
  ADD COLUMN flat_rate_by     uuid REFERENCES app_user(id),
  ADD COLUMN flat_rate_reason text;

-- Zero is legitimate, exactly as it is for the friend rate: a comped tournament table is a real
-- thing. This mirrors table_session_override_positive_chk and deliberately NOT V12's
-- pool_table_rate_hour_positive_chk, which is > 0 because a table priced at zero is a
-- configuration mistake rather than a decision someone made and signed for.
ALTER TABLE table_session
  ADD CONSTRAINT table_session_flat_positive_chk
  CHECK (flat_amount IS NULL OR flat_amount >= 0);

-- All three or none, mirroring the rate override and cash_count.closed_at/closed_by.
--
-- A flat fee is a giveaway vector of exactly the same shape as the friend rate: PHP 50 on a
-- five-hour table costs the hall real money. The control on it is the same one this system uses
-- everywhere -- recorded with its actor and its reason, not prevented -- so the record is not
-- optional. Without the reason the row is just a smaller number with nobody's name on it.
ALTER TABLE table_session
  ADD CONSTRAINT table_session_flat_together_chk
  CHECK (num_nonnulls(flat_amount, flat_rate_by, flat_rate_reason) IN (0, 3));

-- One session, one pricing story.
--
-- This is load-bearing for the reports, not merely tidy. The `overrides` CTE in
-- DAILY_REPORT_SQL finds giveaways with "rate_override_per_minute IS NOT NULL", and it is THIS
-- constraint that guarantees a flat session is not also counted there -- forgone revenue would
-- otherwise be computed per-minute against a session that was never priced per minute.
ALTER TABLE table_session
  ADD CONSTRAINT table_session_flat_xor_override_chk
  CHECK (flat_amount IS NULL OR rate_override_per_minute IS NULL);

-- "Charge fewer minutes" has no meaning against a fee that was never per-minute, and the
-- service refuses it. This is the backstop for a row written by hand: the time_reductions CTE
-- multiplies the minutes forgone by a rate, and on a flat session that rate never applied, so
-- such a row would put a confidently wrong number in the losses band rather than an error.
ALTER TABLE table_session
  ADD CONSTRAINT table_session_flat_no_time_override_chk
  CHECK (flat_amount IS NULL OR billed_minutes_override IS NULL);

COMMENT ON COLUMN table_session.flat_amount IS
  'Fixed charge for the whole session regardless of length -- tournament pricing. Set at session start and never per-minute. Zero is legitimate. NULL means the session is metered, which is every session that predates this. Mutually exclusive with rate_override_per_minute.';
COMMENT ON COLUMN table_session.flat_rate_by IS
  'Who set it. Required whenever the flat amount is set, never separately.';
COMMENT ON COLUMN table_session.flat_rate_reason IS
  'Why -- "Saturday tournament". Required, and the whole control on a fee somebody chose.';

-- Why session_segment.rate_per_minute is 0 on these sessions.
--
-- That column is NOT NULL with a >= 0 check, so a flat session has to put something there, and
-- zero is the only honest value: the segment is not priced. ZERO ON A SEGMENT MEANS THE SESSION
-- IS PRICED AT SESSION LEVEL, NOT THAT THE TABLE WAS FREE. Read table_session.flat_amount to
-- find what was actually charged; a report that reads segment rates alone will under-count.
--
-- The alternative -- spreading the fee across the segments -- was rejected because it does not
-- survive a transfer. Segments record WHERE and HOW LONG, and a session moved between two
-- tables mid-tournament has two of them; one flat charge attaches to the session, which is the
-- thing that was sold. Spreading it would have to pick a split rule, and every split rule is
-- wrong on a bill somebody reads.
COMMENT ON COLUMN session_segment.rate_per_minute IS
  'Snapshot of the rate this segment was billed at. ZERO means the session carries a flat amount and is priced at session level -- not that the table was free. Re-pricing a table tomorrow must not change what this segment cost tonight.';
