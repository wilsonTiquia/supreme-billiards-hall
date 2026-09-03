-- The drawer does not start empty.
--
-- Staff need a float to make change, so at open there is (say) PHP 1,000 in the till that is not
-- takings. Until now expected_cash was the sum of the day's cash payments alone, which quietly
-- assumed an empty drawer: at close the till holds float + sales while the system expected sales,
-- and the variance reads +1,000 every single night. A control that is predictably wrong is not a
-- control — it gets ignored, and the one night it means something it gets ignored too.
--
-- So the float becomes part of the arithmetic, and is stored on the row rather than looked up
-- later. A branch setting can be changed next month; a reconciliation from March has to still add
-- up in March's terms, which means the figure it was computed from lives here.

-- expected_cash was never the expected drawer total — it was the cash takings. Now that the
-- expected total is takings + float, the old name would be actively misleading, and this column
-- is read by anyone auditing a night after the fact. Rename it to what it holds.
-- The generated column is dropped first because it depends on the name.
ALTER TABLE cash_count DROP COLUMN variance;
ALTER TABLE cash_count RENAME COLUMN expected_cash TO cash_sales;

COMMENT ON COLUMN cash_count.cash_sales IS
  'SUM(payment.amount) WHERE method = CASH for this business day, frozen at the moment of counting. Takings only — it is not what the drawer should hold.';

-- Defaults to zero so every row already in the table keeps exactly the variance it had, and so a
-- hall that keeps no float needs no configuration: the setting stays 0 and nothing changes.
ALTER TABLE cash_count
  ADD COLUMN opening_float numeric(12,2) NOT NULL DEFAULT 0;

COMMENT ON COLUMN cash_count.opening_float IS
  'The change float in the drawer at open, as applied to THIS night. Copied from the branch standard at the moment of counting, not joined to it, so a later change to the standard cannot rewrite what an old night was reconciled against.';

-- Whether this night used the branch standard or something else. Kept as a flag as well as an
-- audit row because the question "which nights did not run the usual float" should be answerable
-- by reading the reconciliations themselves, not by trawling the log.
ALTER TABLE cash_count
  ADD COLUMN float_overridden boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN cash_count.float_overridden IS
  'True when the float for this night differed from the branch standard. The reason is on the audit row.';

ALTER TABLE cash_count
  ADD COLUMN variance numeric(12,2)
  GENERATED ALWAYS AS (counted_cash - (cash_sales + opening_float)) STORED;

COMMENT ON COLUMN cash_count.variance IS
  'counted_cash - (cash_sales + opening_float). Negative is a shortfall. Still computed by the database so no caller can produce a variance the arithmetic does not support.';

ALTER TABLE cash_count
  ADD CONSTRAINT cash_count_float_nonneg_chk CHECK (opening_float >= 0);

COMMENT ON TABLE cash_count IS
  'End-of-day drawer reconciliation, and the day-end record itself. The drawer should hold opening_float + cash_sales; variance is what it actually held minus that. closed_at marks the night as finished.';

-- The standard float, so the close-out has a default and staff are never asked a question they
-- would answer the same way every night. Zero preserves today's behaviour exactly; the owner
-- sets the real figure once from the admin settings screen.
INSERT INTO branch_setting (branch_id, key, value)
SELECT id, 'standard_cash_float', '0'::jsonb FROM branch
ON CONFLICT (branch_id, key) DO NOTHING;

COMMENT ON TABLE branch_setting IS
  'Per-branch runtime config. Known keys: rate_floor_per_minute, rate_floor_percent, low_stock_threshold, allow_negative_stock, standard_cash_float, payment_photo_retention, payment_photo_visibility.';
