-- A debt is a property of the payable, not of a remark about it.
--
-- Regulars play tonight and pay next month. Until now such a bill sat OPEN with total_amount
-- still 0.00 and no receipt number, which made it invisible to every report: DAILY_REPORT_SQL
-- filters on status = 'CLOSED', so ~654 pesos of table time played on a Friday simply was not
-- in Friday's gross. The sale happened; only the money had not arrived.
--
-- WHY HERE AND NOT ON session_note. V11's closing comment sketches the opposite: "an amount
-- owed and a settled_at ... lands as two nullable columns added here by a later migration".
-- That paragraph is superseded, and V11 cannot be edited to say so -- Flyway checksums it --
-- so it is said here.
--
-- The decisive argument is V11's own header. session_note is APPEND-ONLY by trigger, on the
-- stated ground that a note about money owed must not be removable by the person who owes a
-- favour. A settled_at on that table could only ever be written by an UPDATE, which
-- forbid_mutation() rejects: settlement would have to be expressed as a second note row, and
-- "is this debt paid?" would become a scan of a thread rather than the reading of a column.
-- The footer contradicts the header, and the header is the half worth keeping.
--
-- Three further reasons:
--   * Revenue recognition needs bill.status and bill.business_date. A note cannot carry a sale
--     into the night's gross, cannot freeze total_amount, and cannot take a receipt number.
--   * The amount owed IS bill.total_amount. Storing it again would be a second number that can
--     disagree with the first -- the same objection BusinessDayServiceImpl makes to storing
--     expected_cash beside the three components it is computed from.
--   * Cardinality. A merged bill carries several sessions and so several threads; the debt is
--     one. "Who owes it" is many, with an author each, and stays exactly where V11 put it.
--
-- So: no owed_by column, and no unsettled_note column. The name lives in session_note, and
-- leave-unpaid refuses to proceed unless the thread already carries one.


-- When the bill was marked unpaid, and by whom. NOT the same instant as closed_at in meaning,
-- though they are written together: closed_at is when the SALE happened and is what
-- business_date is generated from, and it must never move again. See the settled_at comment.
ALTER TABLE bill ADD COLUMN unsettled_at timestamptz;
ALTER TABLE bill ADD COLUMN unsettled_by uuid REFERENCES app_user(id);

-- When the debt was collected. A SEPARATE column and not a reuse of closed_at, because
-- bill.business_date is GENERATED ALWAYS AS business_date_of(COALESCE(closed_at, opened_at)):
-- writing closed_at at settlement would silently move a September sale onto an October report,
-- and the generated column would rewrite itself with no update to the row that says why.
-- The money arriving is dated by payment.business_date, which is generated from taken_at and
-- therefore lands in the drawer that actually received it.
ALTER TABLE bill ADD COLUMN settled_at timestamptz;

COMMENT ON COLUMN bill.unsettled_at IS
  'When this sale was recorded as a debt. The sale itself is dated by closed_at, which is stamped at the same moment and never moves again.';
COMMENT ON COLUMN bill.unsettled_by IS
  'Who left it unpaid. Resolved server-side from the authenticated user, never sent by the client.';
COMMENT ON COLUMN bill.settled_at IS
  'When the debt was collected. Deliberately not closed_at: business_date is generated from closed_at, so settling five weeks later would move the original night''s revenue.';

-- Status UNSETTLED requires both halves of who-and-when, and they are always written together.
-- Stated as an implication rather than an equality because a settled debt is CLOSED and KEEPS
-- its unsettled_at: that is the record of how the sale was made, and the Attention band's
-- "old debts collected today" reads it back.
ALTER TABLE bill ADD CONSTRAINT bill_unsettled_consistency_chk CHECK (
  (status <> 'UNSETTLED' OR (unsettled_at IS NOT NULL AND unsettled_by IS NOT NULL))
  AND (unsettled_at IS NULL) = (unsettled_by IS NULL)
);

-- settled_at is only ever set on a bill that passed through UNSETTLED, and a settled debt is
-- CLOSED -- there is no third state where the money has arrived but the bill has not closed.
-- The ordering clause is not pedantry: a settled_at before its unsettled_at would mean the
-- payment was recorded against a debt that did not yet exist, which is the shape a clock skew
-- or a hand-written correction would take.
ALTER TABLE bill ADD CONSTRAINT bill_settled_consistency_chk CHECK (
  settled_at IS NULL
  OR (status = 'CLOSED' AND unsettled_at IS NOT NULL AND settled_at >= unsettled_at)
);

-- Relaxed to admit UNSETTLED on exactly the same terms as CLOSED, in BOTH arms. The second arm
-- is the one that is easy to miss: as written it required closed_at IS NULL for every status
-- other than CLOSED, so an UNSETTLED bill could not carry the closed_at that dates its sale.
--
-- A bill left unpaid is finalised in every respect a closed one is -- totals frozen, receipt
-- number allocated, closed_at and closed_by stamped. The only thing it lacks is the money.
ALTER TABLE bill DROP CONSTRAINT bill_closed_consistency_chk;
ALTER TABLE bill ADD CONSTRAINT bill_closed_consistency_chk CHECK (
  (status IN ('CLOSED', 'UNSETTLED') AND closed_at IS NOT NULL AND closed_by IS NOT NULL
                                     AND receipt_no IS NOT NULL)
  OR (status NOT IN ('CLOSED', 'UNSETTLED') AND closed_at IS NULL AND closed_by IS NULL)
);

-- V1 calls this "the dashboard's core access path", and its predicate was status = 'CLOSED'.
-- The daily report now reads status IN ('CLOSED','UNSETTLED'), which no longer implies that
-- predicate, so the planner would have dropped this index and fallen back to a sequential scan
-- of bill -- still correct, silently slower, and invisible until the table is large enough to
-- hurt. Widened to match the query exactly.
DROP INDEX bill_business_day_idx;
CREATE INDEX bill_business_day_idx
  ON bill (branch_id, business_date) WHERE status IN ('CLOSED', 'UNSETTLED');

-- The debt list: every outstanding bill in a branch, newest first, across all business dates.
-- Deliberately not scoped to a day, for the reason BillRepository gives about the unpaid strip
-- -- a debt that vanished at the date roll would never be collected.
CREATE INDEX bill_unsettled_idx
  ON bill (branch_id, unsettled_at DESC) WHERE status = 'UNSETTLED';
