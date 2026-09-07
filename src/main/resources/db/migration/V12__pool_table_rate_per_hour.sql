-- The owner thinks in pesos per hour and types the division wrong.
--
-- This column is an INPUT RECORD, not a price. It stores the figure the admin actually typed
-- so the rate screen can read it back and say "PHP 240.00 / hour" instead of "PHP 4.00 / min",
-- which is the same rate and not the sentence the owner has in their head.
--
-- Money is never computed from it. rate_per_minute remains the only billing truth, exactly as
-- session_segment.rate_per_minute remains the only truth for a session already running. The
-- service derives rate_per_minute = rate_per_hour / 60 at HALF_UP to four decimals and writes
-- both; nothing downstream reads this column.
--
-- Nullable on purpose, and the null means something: a table configured per minute has no
-- hourly figure to show, and inventing one by multiplying would put a number on the screen the
-- admin never typed. Every row written before this migration is such a table.
--
-- numeric(12,2) rather than the (10,4) of rate_per_minute: this is what someone typed into a
-- peso box, and a peso box has two decimals. The four decimals exist on the per-minute side
-- precisely because dividing by 60 needs them.
ALTER TABLE pool_table_rate
  ADD COLUMN rate_per_hour numeric(12,2);

-- Same shape as pool_table_rate_positive_chk, allowing for the null. A zero or negative hourly
-- figure would derive a per-minute rate the existing check already rejects, so this only makes
-- the refusal name the column the admin was actually looking at.
ALTER TABLE pool_table_rate
  ADD CONSTRAINT pool_table_rate_hour_positive_chk
  CHECK (rate_per_hour IS NULL OR rate_per_hour > 0);

COMMENT ON COLUMN pool_table_rate.rate_per_hour IS
  'What the admin typed, when they configured this rate hourly. NULL means they typed a per-minute rate. Recorded so the screen can read back their own figure -- NEVER used to compute money. rate_per_minute is the billing truth and is derived from this at HALF_UP to 4 decimals; the two do not always reconcile exactly (PHP 200/hour stores 3.3333/min, which is PHP 199.998 per hour), and the screen is expected to say so rather than hide it.';
