-- The cash count row doubles as the day-end record. That overloading is deliberate: a business
-- day cannot close without a drawer count, so the count row is the only row that is guaranteed
-- to exist for a closed day. A separate business_day table would carry two columns and one
-- guaranteed join, and would let the two records disagree about whether the night is over.
ALTER TABLE cash_count
  ADD COLUMN closed_at timestamptz,
  ADD COLUMN closed_by uuid REFERENCES app_user(id);

COMMENT ON COLUMN cash_count.closed_at IS
  'When the business day was closed. NULL means the drawer is counted but the day is still open. Closing a day that already has this set is a 409.';
COMMENT ON COLUMN cash_count.closed_by IS
  'Who closed the business day. Set together with closed_at, never separately.';

ALTER TABLE cash_count
  ADD CONSTRAINT cash_count_closed_together_chk
  CHECK ((closed_at IS NULL) = (closed_by IS NULL));

COMMENT ON TABLE cash_count IS
  'End-of-day drawer reconciliation, and the day-end record itself. A negative variance is a shortfall; the dashboard surfaces it. closed_at marks the night as finished.';
