-- One switch for the checkout transition, so it can be turned off from the admin screen
-- without a rebuild and without a restart.
--
-- Default false. The animation is decoration on the highest-frequency action in the building:
-- it should be turned on deliberately, after someone has watched it, rather than arrive
-- switched on for whoever happens to be working that night.
INSERT INTO branch_setting (branch_id, key, value)
SELECT id, 'checkout_animation', 'false'::jsonb FROM branch
ON CONFLICT (branch_id, key) DO NOTHING;

COMMENT ON TABLE branch_setting IS
  'Per-branch runtime config. Known keys: rate_floor_per_minute, rate_floor_percent, low_stock_threshold, allow_negative_stock, standard_cash_float, checkout_animation, payment_photo_retention, payment_photo_visibility.';
