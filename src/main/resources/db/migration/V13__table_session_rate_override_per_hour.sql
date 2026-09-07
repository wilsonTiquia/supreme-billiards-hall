-- The friend rate, in the unit the owner thinks in.
--
-- Same shape and the same rule as V12's pool_table_rate.rate_per_hour: these two columns are an
-- INPUT RECORD and a READ-BACK, never a price. rate_override_per_minute stays the only figure
-- that reaches session_segment.rate_per_minute, which stays the only figure that bills, and the
-- forgone-revenue arithmetic in DAILY_REPORT_SQL and LOSSES_DETAIL_SQL stays per-minute and
-- untouched.
ALTER TABLE table_session
  ADD COLUMN rate_override_per_hour numeric(12,2),
  ADD COLUMN standard_rate_per_hour numeric(12,2);

-- NOT the > 0 of pool_table_rate_hour_positive_chk. This mirrors its own neighbour,
-- table_session_override_positive_chk, which allows zero deliberately: a comped game is a
-- legitimate friend rate, and the control on it is that the actor and the reason are recorded,
-- not that the amount has a floor.
ALTER TABLE table_session
  ADD CONSTRAINT table_session_override_hour_positive_chk
  CHECK (rate_override_per_hour IS NULL OR rate_override_per_hour >= 0);

-- The hourly figure never travels alone.
--
-- Both report queries find giveaways with "rate_override_per_minute IS NOT NULL". A row
-- carrying only the hourly figure would satisfy every other check on this table and still be
-- invisible to forgone revenue -- a discount that never reaches the report. The service cannot
-- write one, because the hourly figure derives into the per-minute column before either is
-- stored; this is the backstop for anything written by hand.
--
-- Note what this does NOT say: it does not require the hourly column, because the per-minute
-- input mode remains first-class and leaves it null.
ALTER TABLE table_session
  ADD CONSTRAINT table_session_override_hour_derived_chk
  CHECK (rate_override_per_hour IS NULL OR rate_override_per_minute IS NOT NULL);

-- No positivity check on standard_rate_per_hour, matching standard_rate_per_minute beside it,
-- which has none either. The standard rate is copied from pool_table_rate, where it is already
-- checked at source.

COMMENT ON COLUMN table_session.rate_override_per_hour IS
  'What the counter typed, when they entered the friend rate hourly. NULL means they typed it per minute, which is still the default input mode. Zero is legitimate -- a comped game. NEVER used to compute money: rate_override_per_minute is derived from this at HALF_UP to 4 decimals and is what reaches the segment and the reports.';
COMMENT ON COLUMN table_session.standard_rate_per_hour IS
  'The table''s hourly figure at the moment this session opened, or NULL if the table was configured per minute. Snapshotted for the same reason standard_rate_per_minute is: the drill-down compares the giveaway against the standard, and reading the standard back from pool_table_rate at report time would show a re-rated table''s CURRENT price against an old session. Display only -- forgone revenue is computed from the per-minute pair.';
