-- Classification only: no rates, sessions, bill lines or receipts are changed.
ALTER TABLE pool_table ADD COLUMN is_premium boolean NOT NULL DEFAULT false;

-- Owner-confirmed Tables 1, 2 and 3 in MAIN. IDs, branch, numbers and names were
-- verified against the existing local records and V2 before authoring this backfill.
-- Do not infer premium from a rate, mutable name or a number in another branch.
UPDATE pool_table
SET is_premium = true
WHERE branch_id = '01900000-0000-7000-8000-000000000001'
  AND id IN (
    '019a0000-0000-7000-8000-000000000001',
    '019a0000-0000-7000-8000-000000000002',
    '019a0000-0000-7000-8000-000000000003'
  );
