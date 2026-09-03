-- Charging a friend for two hours when they played three.
--
-- The override is a SEPARATE column from billed_minutes on purpose. session_segment and the
-- computed billed_minutes are the record of what physically happened at the table, and that
-- record is never rewritten — the same rule the stock ledger and the void retention follow.
-- What is charged may differ from what happened, but the difference has to remain visible.
ALTER TABLE table_session
  ADD COLUMN billed_minutes_override        integer,
  ADD COLUMN billed_minutes_override_by     uuid REFERENCES app_user(id),
  ADD COLUMN billed_minutes_override_reason text;

COMMENT ON COLUMN table_session.billed_minutes_override IS
  'Minutes actually charged, when the counter charged less than was played. NULL means the computed billed_minutes was charged. Never above billed_minutes: charging for time not played is an overcharge, not a discount.';
COMMENT ON COLUMN table_session.billed_minutes_override_by IS
  'Who reduced the time. Set together with the override, never separately.';
COMMENT ON COLUMN table_session.billed_minutes_override_reason IS
  'Why. Required whenever the override is set — the reason is the whole control on a giveaway.';

-- All three or none, mirroring rate_override and cash_count.closed_at/closed_by.
ALTER TABLE table_session
  ADD CONSTRAINT table_session_time_override_together_chk
  CHECK (num_nonnulls(billed_minutes_override, billed_minutes_override_by,
                      billed_minutes_override_reason) IN (0, 3));

-- Never negative, and never more than was played. The upper bound is enforced in the service
-- too, where billed_minutes is known at the moment of the edit; this is the backstop for any
-- row written outside the application.
ALTER TABLE table_session
  ADD CONSTRAINT table_session_time_override_range_chk
  CHECK (billed_minutes_override IS NULL
         OR (billed_minutes_override >= 0
             AND (billed_minutes IS NULL OR billed_minutes_override <= billed_minutes)));
