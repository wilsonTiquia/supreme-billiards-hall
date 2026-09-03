-- =====================================================================
-- SEED — minimum data for a working branch
-- =====================================================================


INSERT INTO branch (id, code, name, address)
VALUES ('01900000-0000-7000-8000-000000000001',
        'MAIN',
        'Supreme Billiard Hall',
        '2nd Floor MCC Building, Russia cor. Rhodesia St., Better Living Subd., Paranaque City');

-- rate_floor_per_minute is deliberately absent: the owner ruled that an
-- employee may set any friend rate without approval. Every override is still
-- recorded on table_session with the actor and the standard rate, and surfaced
-- on the admin dashboard. Detection, not prevention -- see the open-risks list.
INSERT INTO branch_setting (branch_id, key, value) VALUES
  ('01900000-0000-7000-8000-000000000001', 'low_stock_threshold',    '10'::jsonb),
  ('01900000-0000-7000-8000-000000000001', 'allow_negative_stock',   'true'::jsonb),
  ('01900000-0000-7000-8000-000000000001', 'payment_photo_retention','"forever"'::jsonb),
  ('01900000-0000-7000-8000-000000000001', 'payment_photo_visibility','"admin_only"'::jsonb);

-- Password hashes are placeholders; the application sets real bcrypt
-- hashes on first run.
INSERT INTO app_user (id, branch_id, username, password_hash, full_name, role) VALUES
  ('01900000-0000-7000-8000-0000000000a1', NULL,
   'owner', '$2a$10$7xX/m0QKvS71hIAbcMcvluSl.d94Hc7Vx0bjONJv5gZq5vA32WWQC', 'Owner', 'ADMIN'),
  ('01900000-0000-7000-8000-0000000000a2', '01900000-0000-7000-8000-000000000001',
   'counter', '$2a$10$ViiXt9CrEy5dj4P0AhZxRuDrwX/ovSs930oyz6dgNEYjhkjObaRXK', 'Front Counter', 'EMPLOYEE');

INSERT INTO customer_type (branch_id, name, allows_rate_override, is_default, sort_order) VALUES
  ('01900000-0000-7000-8000-000000000001', 'Regular',          false, true,  1),
  ('01900000-0000-7000-8000-000000000001', 'Friend of Owner',  true,  false, 2);

-- Seven tables: four standard at PHP 4.00/min, three premium at PHP 5.00/min.
-- Admins can add, rename, re-rate and archive tables from the UI; this is only
-- the starting set so the hall is usable on first launch.
INSERT INTO pool_table (id, branch_id, name, table_number) VALUES
  ('019a0000-0000-7000-8000-000000000001','01900000-0000-7000-8000-000000000001','Table 1',1),
  ('019a0000-0000-7000-8000-000000000002','01900000-0000-7000-8000-000000000001','Table 2',2),
  ('019a0000-0000-7000-8000-000000000003','01900000-0000-7000-8000-000000000001','Table 3',3),
  ('019a0000-0000-7000-8000-000000000004','01900000-0000-7000-8000-000000000001','Table 4',4),
  ('019a0000-0000-7000-8000-000000000005','01900000-0000-7000-8000-000000000001','Table 5 (Premium)',5),
  ('019a0000-0000-7000-8000-000000000006','01900000-0000-7000-8000-000000000001','Table 6 (Premium)',6),
  ('019a0000-0000-7000-8000-000000000007','01900000-0000-7000-8000-000000000001','Table 7 (Premium)',7);

INSERT INTO pool_table_rate (branch_id, pool_table_id, rate_per_minute, effective_from) VALUES
  ('01900000-0000-7000-8000-000000000001','019a0000-0000-7000-8000-000000000001',4.0000,'2026-01-01'),
  ('01900000-0000-7000-8000-000000000001','019a0000-0000-7000-8000-000000000002',4.0000,'2026-01-01'),
  ('01900000-0000-7000-8000-000000000001','019a0000-0000-7000-8000-000000000003',4.0000,'2026-01-01'),
  ('01900000-0000-7000-8000-000000000001','019a0000-0000-7000-8000-000000000004',4.0000,'2026-01-01'),
  ('01900000-0000-7000-8000-000000000001','019a0000-0000-7000-8000-000000000005',5.0000,'2026-01-01'),
  ('01900000-0000-7000-8000-000000000001','019a0000-0000-7000-8000-000000000006',5.0000,'2026-01-01'),
  ('01900000-0000-7000-8000-000000000001','019a0000-0000-7000-8000-000000000007',5.0000,'2026-01-01');

INSERT INTO product_category (branch_id, name, sort_order) VALUES
  ('01900000-0000-7000-8000-000000000001', 'Beer',       1),
  ('01900000-0000-7000-8000-000000000001', 'Softdrinks', 2),
  ('01900000-0000-7000-8000-000000000001', 'Food',       3),
  ('01900000-0000-7000-8000-000000000001', 'Cigarettes', 4);

