-- Archive now controls visibility only. Keep every legacy redemption block in place.
ALTER TABLE voucher_batch ADD COLUMN cancelled_at timestamptz;

WITH cancelled AS (
    UPDATE voucher_batch SET cancelled_at = archived_at
    WHERE archived_at IS NOT NULL
    RETURNING id, branch_id, note, archived_at, cancelled_at
)
INSERT INTO audit_log (branch_id, action, entity_table, entity_id, before, after, note)
SELECT branch_id, 'VOUCHER_BATCH_CANCELLED', 'voucher_batch', id,
       jsonb_build_object('name', note, 'archivedAt', archived_at, 'cancelledAt', NULL),
       jsonb_build_object('name', note, 'archivedAt', archived_at, 'cancelledAt', cancelled_at),
       'Migration V25: preserved the redemption block on a previously archived batch; no codes reactivated.'
FROM cancelled;
