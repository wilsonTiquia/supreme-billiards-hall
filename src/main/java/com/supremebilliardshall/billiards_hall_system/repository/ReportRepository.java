package com.supremebilliardshall.billiards_hall_system.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.UUID;

// Deliberately a class rather than a Spring Data interface: this reports across a dozen
// tables and belongs to no single entity, so declaring it as a repository for one of them
// would only mislead.
//
// The daily report is one native statement, grouped on business_date, in one round trip. One
// statement so the sections cannot drift apart, and returning a single jsonb document because
// a dozen typed projections would be far harder to read than the SQL is. JPQL is not an
// option: business_date is a generated column and the day is defined by business_date_of(),
// which only the database knows.
@Repository
public class ReportRepository {

    private static final String DAILY_REPORT_SQL = """
            WITH params AS (
              SELECT cast(:branchId as uuid) AS branch_id, cast(:reportDate as date) AS d, (cast(:reportDate as date) - 1) AS prev_d
            ),
            closed_bills AS (
              -- UNSETTLED counts as a sale. The regular who plays tonight and pays next month
              -- was still served tonight, and the night that reports 654 pesos short because
              -- nobody has collected it yet is not the truth about the night -- it is the truth
              -- about the drawer, which is what the cash count is for.
              --
              -- Everything downstream of this CTE therefore includes the debt: gross, cost,
              -- profit, salesByHour, topItems, tableUtilisation and perEmployee. payment_mix
              -- does NOT, and must not -- it reads the payment table, and an unsettled bill has
              -- no payment row to mix in.
              SELECT b.* FROM bill b, params p
              WHERE b.branch_id = p.branch_id AND b.status IN ('CLOSED', 'UNSETTLED')
                AND b.business_date IN (p.d, p.prev_d)
            ),
            unsettled_tonight AS (
              -- How much of this night's gross was left unpaid on the night. Reported beside
              -- gross rather than subtracted from it: the owner needs to see both the takings
              -- and how much of them was still a promise when the lights went off.
              --
              -- A HISTORICAL fact, keyed on unsettled_at IS NOT NULL rather than on the bill's
              -- CURRENT status, and that distinction is the whole reason this figure can be
              -- trusted. Were it status = 'UNSETTLED', collecting the debt five weeks later
              -- would silently rewrite a night the owner had already read and reconciled --
              -- 654 pesos of unsettled sales would become 0 with no record that it ever said
              -- anything else. What happened on the night does not change; what is still owed
              -- is the separate `outstanding` figure below.
              SELECT count(*)::int AS count, coalesce(sum(b.total_amount), 0) AS amount
              FROM bill b, params p
              WHERE b.branch_id = p.branch_id AND b.unsettled_at IS NOT NULL
                AND b.business_date = p.d
            ),
            collected_today AS (
              -- Money that came in tonight against an EARLIER night's sale.
              --
              -- Dated on both sides, and the two dates are different columns for a reason:
              -- payment.business_date is generated from taken_at, so it follows the money into
              -- tonight's drawer and tonight's cash count, while bill.business_date is generated
              -- from closed_at and stays on the night that was played. This figure is the
              -- difference between them, which is exactly what "old debts collected" means.
              --
              -- It is deliberately NOT added to gross: that revenue was recognised on the night
              -- it was earned, and counting it again here would report the same sale twice.
              SELECT count(*)::int AS count, coalesce(sum(pm.amount), 0) AS amount
              FROM payment pm
              JOIN bill b ON b.id = pm.bill_id, params p
              WHERE pm.branch_id = p.branch_id AND pm.business_date = p.d
                AND b.business_date < p.d
            ),
            outstanding AS (
              -- Every debt still open, across all dates, for the Attention band. Not scoped to
              -- a day: the question is "how much is out there", and a figure that reset each
              -- night would answer a question nobody asked.
              --
              -- The one figure in this report that is deliberately LIVE rather than a record of
              -- the reported night: it answers "right now", so it moves whenever a debt is
              -- collected, including on a report for a night three months ago. Everything else
              -- here is fixed once the night has passed.
              SELECT count(*)::int AS count, coalesce(sum(b.total_amount), 0) AS amount
              FROM bill b, params p
              WHERE b.branch_id = p.branch_id AND b.status = 'UNSETTLED'
            ),
            totals AS (
              SELECT b.business_date,
                     count(*)::int                                  AS bills,
                     coalesce(sum(b.total_amount), 0)               AS gross,
                     coalesce(sum(b.total_cost), 0)                 AS cost,
                     coalesce(sum(b.total_amount - b.total_cost), 0) AS profit,
                     coalesce(sum(b.subtotal_time), 0)              AS "timeRevenue",
                     coalesce(sum(b.subtotal_items), 0)             AS "itemRevenue"
              FROM closed_bills b GROUP BY b.business_date
            ),
            live_lines AS (
              SELECT l.*, b.business_date FROM bill_line l
              JOIN closed_bills b ON b.id = l.bill_id
              WHERE l.voided_at IS NULL
            ),
            sales_by_hour AS (
              SELECT extract(hour FROM (b.closed_at AT TIME ZONE INTERVAL '+08:00'))::int AS hour,
                     count(*)::int AS bills, coalesce(sum(b.total_amount), 0) AS amount
              FROM closed_bills b, params p WHERE b.business_date = p.d GROUP BY 1
            ),
            segment_minutes AS (
              /*
               * WALL CLOCK, PAUSES INCLUDED, AND THAT IS THE POINT -- which is why the column
               * this feeds is called occupiedMinutes and not billedMinutes.
               *
               * A paused table is not available to anybody: the customer still has it, and
               * nobody else can be seated there. Utilisation asks how much of the trading day
               * each table was held, and the denominator below is 19 hours of wall clock, so
               * the numerator has to be wall clock too or the percentage means nothing.
               *
               * Deducting pauses here would not produce the session's charged figure either.
               * A session's billedMinutes already diverges from wall clock for three further
               * reasons -- a flat session ignores minutes entirely, billed_minutes_override
               * rewrites them at checkout, and a rate override changes what a minute is worth.
               * Subtracting only pauses would give a THIRD number, reconciling with neither
               * occupancy nor money. This was reported as a bug three times because the field
               * was named after the wrong one of the two.
               *
               * The money question is answered exactly and elsewhere: totals.timeRevenue and
               * timeRevenueByMode for what the time sold, the losses band for what was given
               * away.
               */
              SELECT sg.pool_table_id,
                     sum(extract(epoch FROM (coalesce(sg.ended_at, now()) - sg.started_at)) / 60.0) AS minutes
              FROM session_segment sg
              JOIN table_session ts ON ts.id = sg.session_id
              JOIN closed_bills b   ON b.id = ts.bill_id, params p
              WHERE b.business_date = p.d GROUP BY sg.pool_table_id
            ),
            table_util AS (
              SELECT t.name AS "tableName",
                     round(coalesce(sm.minutes, 0))::int AS "occupiedMinutes",
                     -- The business day is 19 hours long: 10:00 to 05:00.
                     round(coalesce(sm.minutes, 0) / (19 * 60) * 100, 1) AS "utilisationPercent"
              FROM pool_table t LEFT JOIN segment_minutes sm ON sm.pool_table_id = t.id, params p
              WHERE t.branch_id = p.branch_id AND t.archived_at IS NULL
              ORDER BY t.table_number, t.name
            ),
            top_items AS (
              -- Grouped on the snapshot description, not the live product name: that is what was sold.
              SELECT l.description, sum(l.quantity) AS quantity, sum(l.line_total) AS revenue
              FROM live_lines l, params p
              WHERE l.business_date = p.d AND l.line_kind = 'PRODUCT'
              GROUP BY l.description ORDER BY revenue DESC LIMIT 10
            ),
            payment_mix AS (
              SELECT pm.method::text AS method, count(*)::int AS payments, coalesce(sum(pm.amount), 0) AS amount
              FROM payment pm, params p
              WHERE pm.branch_id = p.branch_id AND pm.business_date = p.d GROUP BY pm.method
            ),
            voids AS (
              SELECT count(*)::int AS "voidCount", coalesce(sum(l.line_total), 0) AS "voidAmount"
              FROM bill_line l JOIN bill b ON b.id = l.bill_id, params p
              WHERE b.branch_id = p.branch_id AND b.business_date = p.d AND l.voided_at IS NOT NULL
            ),
            overrides AS (
              -- A promo and a favour are the same arithmetic and different facts. Happy hour is
              -- a decision about the night; a free game for the owner's friend is a decision
              -- about one person. Reported as one lump they cannot be managed: the owner cannot
              -- tell whether a heavy month of giveaways was the promotion working or generosity
              -- running away.
              --
              -- Aggregate FILTER rather than GROUP BY, and that is load-bearing. `losses` is
              -- built by cross-joining these single-row CTEs and merging them with ||, so a CTE
              -- that returned ZERO rows on a quiet night would take the whole losses object
              -- with it -- and it would vanish silently, on exactly the nights nobody is
              -- looking closely. FILTER keeps one row whatever the night held.
              --
              -- The friend half is IS DISTINCT FROM 'PROMO', not = 'FRIEND', so a row with no
              -- kind still lands somewhere and the two halves always reconstruct what the
              -- single figure used to be. V18 backfilled every such row and the constraint
              -- stops new ones, so this is a belt on top of braces -- but a lost giveaway is
              -- not the place to find out a constraint was dropped.
              SELECT count(*) FILTER (WHERE ts.rate_override_kind = 'PROMO')::int
                       AS "promoSessions",
                     coalesce(sum((ts.standard_rate_per_minute - ts.rate_override_per_minute)
                                  * coalesce(ts.billed_minutes, 0))
                              FILTER (WHERE ts.rate_override_kind = 'PROMO'), 0)
                       AS "promoForgone",
                     count(*) FILTER (WHERE ts.rate_override_kind IS DISTINCT FROM 'PROMO')::int
                       AS "friendSessions",
                     coalesce(sum((ts.standard_rate_per_minute - ts.rate_override_per_minute)
                                  * coalesce(ts.billed_minutes, 0))
                              FILTER (WHERE ts.rate_override_kind IS DISTINCT FROM 'PROMO'), 0)
                       AS "friendForgone"
              FROM table_session ts JOIN bill b ON b.id = ts.bill_id, params p
              WHERE b.branch_id = p.branch_id AND b.business_date = p.d
                AND ts.rate_override_per_minute IS NOT NULL
            ),
            flats AS (
              -- A flat fee below what the meter would have charged is a giveaway, and nothing
              -- else would report it: the overrides CTE below filters on
              -- rate_override_per_minute, which table_session_flat_xor_override_chk guarantees
              -- is NULL on every flat session. PHP 50 on a five-hour table would be invisible.
              --
              -- Clamped PER ROW with GREATEST, not on the sum. A flat fee ABOVE the metered
              -- figure is not a loss, and letting it net off would hide a real giveaway behind
              -- someone else's good night.
              --
              -- coalesce on both factors because the failure mode here is silent: NULL * n is
              -- NULL, and sum() skips NULL rather than raising, so a missing standard rate
              -- would quietly under-count instead of failing loudly.
              SELECT count(*)::int AS "flatSessions",
                     coalesce(sum(GREATEST(
                       coalesce(ts.standard_rate_per_minute, 0) * coalesce(ts.billed_minutes, 0)
                         - ts.flat_amount, 0)), 0) AS "flatForgone"
              FROM table_session ts JOIN bill b ON b.id = ts.bill_id, params p
              WHERE b.branch_id = p.branch_id AND b.business_date = p.d
                AND ts.flat_amount IS NOT NULL
            ),
            time_by_mode AS (
              /*
               * Table revenue by HOW IT WAS PRICED, not by how much was given away.
               *
               * The losses band answers "what did we hand over"; this answers "what did the
               * night actually sell, and under which pricing". They are different questions:
               * a promo hour that fills four tables shows up here as revenue and there as a
               * giveaway, and the owner needs both to decide whether to run it again.
               *
               * Read off table_session.time_amount, which is the finalised charge for the
               * session -- the same figure the TIME lines carry -- so a flat session
               * contributes its fee and a reduced-time session contributes what was charged
               * rather than what was played.
               *
               * THE SPLIT MUST RECONSTRUCT THE WHOLE: these four modes sum to totals
               * .timeRevenue, and PromoRateSessionTest asserts exactly that on a day carrying
               * one of each. That reconciliation is a CURRENT property, not an invariant, and
               * it rests on three things being true today: a TIME line cannot be voided on its
               * own (BillServiceImpl refuses it), there is no void-a-session path, and there is
               * no bill merge. MERGE IS THE ONE THAT WILL BREAK IT -- moving sessions between
               * bills separates ts.bill_id from the bill whose subtotal_time they built. When
               * merge is built, this comes back here.
               */
              SELECT CASE
                       WHEN ts.flat_amount IS NOT NULL           THEN 'FLAT'
                       -- Not "rate_override_per_minute IS NOT NULL then look up the kind": the
                       -- together-constraint makes these the same test, and reading the kind
                       -- directly is the one that fails loudly if that ever stops holding.
                       WHEN ts.rate_override_kind IS NOT NULL    THEN ts.rate_override_kind::text
                       ELSE 'STANDARD'
                     END                                         AS mode,
                     count(*)::int                               AS sessions,
                     coalesce(sum(ts.time_amount), 0)            AS amount
              FROM table_session ts JOIN closed_bills b ON b.id = ts.bill_id, params p
              WHERE b.business_date = p.d
              GROUP BY 1
            ),
            time_revenue_by_mode AS (
              -- All four modes always, in a fixed order, zeros included. A split that shows
              -- three rows on a night with no promos reads as "the promo row is missing"
              -- rather than "there were none", and the four figures have to be addable on
              -- sight for the reader to check them against the total themselves.
              SELECT m.mode, coalesce(t.sessions, 0) AS sessions, coalesce(t.amount, 0) AS amount, m.ord
              FROM (VALUES ('STANDARD', 1), ('PROMO', 2), ('FRIEND', 3), ('FLAT', 4)) AS m(mode, ord)
              LEFT JOIN time_by_mode t ON t.mode = m.mode
            ),
            time_reductions AS (
              SELECT count(*)::int AS "reducedSessions",
                     coalesce(sum((ts.billed_minutes - ts.billed_minutes_override)
                                  * coalesce(ts.rate_override_per_minute, ts.standard_rate_per_minute)), 0)
                       AS "timeReductionForgone"
              FROM table_session ts JOIN bill b ON b.id = ts.bill_id, params p
              WHERE b.branch_id = p.branch_id AND b.business_date = p.d
                AND ts.billed_minutes_override IS NOT NULL
            ),
            discounts AS (
              /*
               * Money knocked off whole bills at the counter -- the giveaway route that reaches
               * the beer as well as the table.
               *
               * SEPARATE FROM time_reductions AND NOT DOUBLE-COUNTING IT. A reduction rewrites
               * the TIME lines before the discount is taken, so the subtotal the discount was
               * computed against is already the reduced one; each figure is read off a
               * different table over an amount the other never touches. A bill carrying both
               * appears in both rows, correctly, and the two add up to what was actually given
               * away on it.
               *
               * Note what this figure means for gross: totals.gross sums total_amount, which is
               * now the DISCOUNTED number. That is the right answer, because gross has to
               * reconcile to the drawer -- 600 pesos went in, and a gross of 654 would leave
               * the cash count short by 54 every time with nothing explaining it. This band is
               * what explains the gap.
               *
               * Dated by the BILL's business_date, like voids and overrides, rather than by
               * discount_at: the giveaway belongs to the night that was played.
               */
              SELECT count(*)::int AS "discountBills",
                     coalesce(sum(b.discount_amount), 0) AS "discountAmount"
              FROM bill b, params p
              WHERE b.branch_id = p.branch_id AND b.business_date = p.d
                AND b.discount_amount > 0
            ),
            vouchers AS (
              /*
               * Free table time given away as a prize -- a social-media giveaway, a tournament
               * placing -- and redeemed at the counter against the winner's bill.
               *
               * A THIRD row in this band, beside the discount and the time reduction, and it
               * overlaps neither. A time reduction rewrites the TIME lines before either of the
               * other two is computed; the voucher then covers a measured slice of what is left
               * and the discount is agreed on the remainder. Each figure is read off a
               * different column over an amount the others never touch, so a bill carrying all
               * three appears in all three rows correctly and they sum to what was given away.
               *
               * Dated by the BILL's business_date, like the discount, rather than by
               * voucher.redeemed_at: the giveaway belongs to the night that was played.
               *
               * Gross is the post-voucher figure, for the discount's reason: gross has to
               * reconcile to the drawer. A winner who played three hours on a two-hour voucher
               * puts 240 pesos in the till, and a gross of 720 would leave the cash count 480
               * short every time with nothing accounting for it. This band is that accounting.
               */
              SELECT count(*)::int AS "voucherCount",
                     coalesce(sum(b.voucher_amount), 0) AS "voucherAmount"
              FROM bill b, params p
              WHERE b.branch_id = p.branch_id AND b.business_date = p.d
                AND b.voucher_amount > 0
            ),
            comps AS (
              SELECT coalesce(sum(-sm.quantity_delta), 0) AS "compQuantity",
                     coalesce(sum(-sm.quantity_delta * pr.avg_cost), 0) AS "compEstimatedCost"
              FROM stock_movement sm JOIN product pr ON pr.id = sm.product_id, params p
              WHERE sm.branch_id = p.branch_id AND sm.business_date = p.d AND sm.reason = 'STAFF_COMP'
            ),
            low_stock AS (
              SELECT pr.name, pr.qty_on_hand AS "qtyOnHand"
              FROM product pr, params p
              WHERE pr.branch_id = p.branch_id AND pr.archived_at IS NULL
                AND pr.qty_on_hand <= coalesce((SELECT (bs.value #>> '{}')::numeric FROM branch_setting bs
                                                WHERE bs.branch_id = p.branch_id AND bs.key = 'low_stock_threshold'), 10)
              ORDER BY pr.qty_on_hand
            ),
            expenses AS (
              -- Operating cost, dated by the expense's OWN business_date rather than a bill's:
              -- an expense has no bill, and the water man paid at 02:00 belongs to the night
              -- still running. Voided rows excluded, exactly as voided lines are excluded from
              -- revenue -- the row is retained, but it is not a cost.
              SELECT e.business_date,
                     coalesce(sum(e.amount), 0) AS amount
              FROM expense e, params p
              WHERE e.branch_id = p.branch_id
                AND e.business_date IN (p.d, p.prev_d)
                AND e.voided_at IS NULL
              GROUP BY e.business_date
            ),
            expenses_by_category AS (
              -- Joined to the category rather than grouped on an id, and NOT filtered on
              -- archived_at: a category archived last week still has to report what was spent
              -- under it, or the breakdown stops adding up to the total beside it.
              SELECT ec.name AS category, coalesce(sum(e.amount), 0) AS amount
              FROM expense e
              JOIN expense_category ec ON ec.id = e.expense_category_id, params p
              WHERE e.branch_id = p.branch_id AND e.business_date = p.d
                AND e.voided_at IS NULL
              GROUP BY ec.name ORDER BY amount DESC
            ),
            per_employee AS (
              SELECT u.username, u.full_name AS "fullName", count(*)::int AS bills,
                     coalesce(sum(b.total_amount), 0) AS gross,
                     coalesce(sum(b.total_cost), 0) AS cost,
                     coalesce(sum(b.total_amount - b.total_cost), 0) AS profit
              FROM closed_bills b JOIN app_user u ON u.id = b.closed_by, params p
              WHERE b.business_date = p.d
              GROUP BY u.username, u.full_name ORDER BY gross DESC
            )
            SELECT jsonb_build_object(
              'businessDate',       p.d,
              'comparedTo',         p.prev_d,
              'totals',             coalesce((SELECT to_jsonb(t) - 'business_date' FROM totals t WHERE t.business_date = p.d),
                                             jsonb_build_object('bills', 0, 'gross', 0, 'cost', 0, 'profit', 0,
                                                                'timeRevenue', 0, 'itemRevenue', 0)),
              -- Zeros rather than an empty object, so the client can always subtract.
              'previousTotals',     coalesce((SELECT to_jsonb(t) - 'business_date' FROM totals t WHERE t.business_date = p.prev_d),
                                             jsonb_build_object('bills', 0, 'gross', 0, 'cost', 0, 'profit', 0,
                                                                'timeRevenue', 0, 'itemRevenue', 0)),
              'salesByHour',        coalesce((SELECT jsonb_agg(to_jsonb(s) ORDER BY s.hour) FROM sales_by_hour s), '[]'::jsonb),
              -- 'ord' is the ordering key, not a figure, and is dropped rather than shipped.
              'timeRevenueByMode',  coalesce((SELECT jsonb_agg(to_jsonb(t) - 'ord' ORDER BY t.ord)
                                              FROM time_revenue_by_mode t), '[]'::jsonb),
              'tableUtilisation',   coalesce((SELECT jsonb_agg(to_jsonb(t)) FROM table_util t), '[]'::jsonb),
              'topItems',           coalesce((SELECT jsonb_agg(to_jsonb(i)) FROM top_items i), '[]'::jsonb),
              'paymentMix',         coalesce((SELECT jsonb_agg(to_jsonb(m)) FROM payment_mix m), '[]'::jsonb),
              -- `disc` and `vch`, never `d`: params.d is a DATE column in the outer scope, and
              -- an unqualified `d` in to_jsonb() binds to that column rather than to the table
              -- alias -- which merges a json string into the object and turns the whole of
              -- `losses` into an array.
              'losses',             (SELECT to_jsonb(v) || to_jsonb(o) || to_jsonb(c) || to_jsonb(r) || to_jsonb(f) || to_jsonb(disc) || to_jsonb(vch)
                                     FROM voids v, overrides o, comps c, time_reductions r, flats f, discounts disc, vouchers vch),
              'expenses',           jsonb_build_object(
                                      'total',         coalesce((SELECT x.amount FROM expenses x WHERE x.business_date = p.d), 0),
                                      -- Zero rather than null, so the client can always subtract.
                                      'previousTotal', coalesce((SELECT x.amount FROM expenses x WHERE x.business_date = p.prev_d), 0),
                                      'byCategory',    coalesce((SELECT jsonb_agg(to_jsonb(c)) FROM expenses_by_category c), '[]'::jsonb)),
              'lowStock',           coalesce((SELECT jsonb_agg(to_jsonb(l)) FROM low_stock l), '[]'::jsonb),
              'perEmployee',        coalesce((SELECT jsonb_agg(to_jsonb(e)) FROM per_employee e), '[]'::jsonb),
              'unsettledTonight',   (SELECT to_jsonb(u) FROM unsettled_tonight u),
              'collectedToday',     (SELECT to_jsonb(c) FROM collected_today c),
              'outstanding',        (SELECT to_jsonb(o) FROM outstanding o)
            )::text AS report
            FROM params p
            """;

    // The rows behind the dashboard's loss figures.
    //
    // Every predicate here mirrors the corresponding CTE in DAILY_REPORT_SQL exactly — including
    // the fact that comps are dated by the MOVEMENT's business_date while voids and overrides are
    // dated by their BILL's. If these drifted apart, the detail and the tile would disagree and
    // the owner would have no way to know which was right.
    private static final String LOSSES_DETAIL_SQL = """
            WITH params AS (
              SELECT cast(:branchId as uuid) AS branch_id, cast(:reportDate as date) AS d
            ),
            comp_lines AS (
              SELECT pr.name                            AS "productName",
                     (-sm.quantity_delta)               AS quantity,
                     sm.note                            AS reason,
                     u.username                         AS "actorUsername",
                     sm.occurred_at                     AS "occurredAt",
                     (-sm.quantity_delta * pr.avg_cost) AS "estimatedCost"
              FROM stock_movement sm
              JOIN product pr ON pr.id = sm.product_id
              LEFT JOIN app_user u ON u.id = sm.actor_id, params p
              WHERE sm.branch_id = p.branch_id AND sm.business_date = p.d
                AND sm.reason = 'STAFF_COMP'
            ),
            void_lines AS (
              SELECT l.description                      AS description,
                     l.quantity                         AS quantity,
                     l.line_total                       AS "lineTotal",
                     l.void_reason                      AS reason,
                     u.username                         AS "actorUsername",
                     l.voided_at                        AS "voidedAt",
                     b.id                               AS "billId",
                     b.receipt_no                       AS "receiptNo"
              FROM bill_line l
              JOIN bill b ON b.id = l.bill_id
              LEFT JOIN app_user u ON u.id = l.voided_by, params p
              WHERE b.branch_id = p.branch_id AND b.business_date = p.d
                AND l.voided_at IS NOT NULL
            ),
            override_lines AS (
              SELECT t.name                             AS "poolTableName",
                     ts.standard_rate_per_minute        AS "standardRatePerMinute",
                     ts.rate_override_per_minute        AS "chargedRatePerMinute",
                     -- Read-back only, and both snapshotted at open. Null on a per-minute
                     -- table or a per-minute friend rate, in which case the drill-down shows
                     -- the per-minute pair above exactly as it always has. The forgone figure
                     -- below is unchanged and stays per-minute: these two never enter it.
                     ts.standard_rate_per_hour          AS "standardRatePerHour",
                     ts.rate_override_per_hour          AS "chargedRatePerHour",
                     coalesce(ts.billed_minutes, 0)     AS "billedMinutes",
                     ((ts.standard_rate_per_minute - ts.rate_override_per_minute)
                        * coalesce(ts.billed_minutes, 0)) AS "forgoneRevenue",
                     u.username                         AS "actorUsername",
                     ts.rate_override_reason            AS reason,
                     ts.opened_at                       AS "openedAt",
                     -- Carried on the row and filtered on below rather than queried twice, so
                     -- the two sections cannot select different sets of the same overrides.
                     ts.rate_override_kind::text        AS "rateOverrideKind"
              FROM table_session ts
              JOIN bill b ON b.id = ts.bill_id
              JOIN pool_table t ON t.id = ts.pool_table_id
              LEFT JOIN app_user u ON u.id = ts.rate_override_by, params p
              WHERE b.branch_id = p.branch_id AND b.business_date = p.d
                AND ts.rate_override_per_minute IS NOT NULL
            ),
            flat_lines AS (
              SELECT t.name                             AS "poolTableName",
                     coalesce(ts.billed_minutes, 0)     AS "billedMinutes",
                     ts.standard_rate_per_minute        AS "standardRatePerMinute",
                     -- What the meter would have charged, beside what was actually charged, so
                     -- the reader can see the giveaway without doing the multiplication.
                     (coalesce(ts.standard_rate_per_minute, 0)
                        * coalesce(ts.billed_minutes, 0)) AS "meteredRevenue",
                     ts.flat_amount                     AS "flatAmount",
                     GREATEST(coalesce(ts.standard_rate_per_minute, 0)
                                * coalesce(ts.billed_minutes, 0)
                              - ts.flat_amount, 0)      AS "forgoneRevenue",
                     u.username                         AS "actorUsername",
                     ts.flat_rate_reason                AS reason,
                     ts.opened_at                       AS "openedAt"
              FROM table_session ts
              JOIN bill b ON b.id = ts.bill_id
              JOIN pool_table t ON t.id = ts.pool_table_id
              LEFT JOIN app_user u ON u.id = ts.flat_rate_by, params p
              WHERE b.branch_id = p.branch_id AND b.business_date = p.d
                AND ts.flat_amount IS NOT NULL
            ),
            discount_lines AS (
              -- The same predicate as the `discounts` CTE above, to the character: same branch,
              -- same bill business_date, same discount_amount > 0. The tile and this list are
              -- the same rows counted twice, and if they ever disagreed the owner would have no
              -- way to tell which one to believe.
              SELECT b.id                               AS "billId",
                     b.receipt_no                       AS "receiptNo",
                     (b.subtotal_time + b.subtotal_items) AS subtotal,
                     b.discount_amount                  AS "discountAmount",
                     b.total_amount                     AS "chargedAmount",
                     b.discount_reason                  AS reason,
                     u.username                         AS "actorUsername",
                     b.discount_at                      AS "discountAt"
              FROM bill b
              LEFT JOIN app_user u ON u.id = b.discount_by, params p
              WHERE b.branch_id = p.branch_id AND b.business_date = p.d
                AND b.discount_amount > 0
            ),
            voucher_lines AS (
              -- The same predicate as the `vouchers` CTE above, to the character: same branch,
              -- same bill business_date, same voucher_amount > 0. The tile and this list are the
              -- same rows counted twice, and if they ever disagreed the owner would have no way
              -- to tell which one to believe.
              --
              -- The table name comes from the bill's EARLIEST session rather than from a join,
              -- because this row is per bill and a merged bill has several. The lateral takes
              -- one and says which; a join would multiply the row and double the amount.
              SELECT b.id                               AS "billId",
                     b.receipt_no                       AS "receiptNo",
                     vo.code                            AS code,
                     vb.note                            AS "batchNote",
                     vo.minutes                         AS "voucherMinutes",
                     b.voucher_minutes_covered          AS "minutesCovered",
                     -- What the winner did not get to use. The code is spent either way: no
                     -- change, no residual balance.
                     GREATEST(vo.minutes - coalesce(b.voucher_minutes_covered, 0), 0)
                                                        AS "minutesForfeited",
                     b.voucher_amount                   AS "voucherAmount",
                     tbl.name                           AS "poolTableName",
                     u.username                         AS "actorUsername",
                     vo.redeemed_at                     AS "redeemedAt"
              FROM bill b
              JOIN voucher vo       ON vo.id = b.voucher_id
              JOIN voucher_batch vb ON vb.id = vo.batch_id
              LEFT JOIN app_user u  ON u.id = vo.redeemed_by
              LEFT JOIN LATERAL (
                SELECT t2.name
                FROM table_session ts2
                JOIN pool_table t2 ON t2.id = ts2.pool_table_id
                WHERE ts2.bill_id = b.id
                ORDER BY ts2.opened_at
                LIMIT 1
              ) tbl ON true, params p
              WHERE b.branch_id = p.branch_id AND b.business_date = p.d
                AND b.voucher_amount > 0
            ),
            time_reduction_lines AS (
              SELECT t.name                              AS "poolTableName",
                     ts.billed_minutes                   AS "actualMinutes",
                     ts.billed_minutes_override          AS "chargedMinutes",
                     coalesce(ts.rate_override_per_minute, ts.standard_rate_per_minute)
                                                         AS "ratePerMinute",
                     ((ts.billed_minutes - ts.billed_minutes_override)
                        * coalesce(ts.rate_override_per_minute, ts.standard_rate_per_minute))
                                                         AS "forgoneRevenue",
                     u.username                          AS "actorUsername",
                     ts.billed_minutes_override_reason   AS reason,
                     ts.closed_at                        AS "closedAt"
              FROM table_session ts
              JOIN bill b ON b.id = ts.bill_id
              JOIN pool_table t ON t.id = ts.pool_table_id
              LEFT JOIN app_user u ON u.id = ts.billed_minutes_override_by, params p
              WHERE b.branch_id = p.branch_id AND b.business_date = p.d
                AND ts.billed_minutes_override IS NOT NULL
            )
            SELECT jsonb_build_object(
              'businessDate', p.d,
              -- Each section's total is summed from the very rows listed beneath it, not read
              -- from a second query, so the two cannot drift.
              'comps', jsonb_build_object(
                'compQuantity',      coalesce((SELECT sum(c.quantity)        FROM comp_lines c), 0),
                'compEstimatedCost', coalesce((SELECT sum(c."estimatedCost") FROM comp_lines c), 0),
                'lines', coalesce((SELECT jsonb_agg(to_jsonb(c) ORDER BY c."occurredAt" DESC)
                                   FROM comp_lines c), '[]'::jsonb)),
              'voids', jsonb_build_object(
                'voidCount',  coalesce((SELECT count(*)::int       FROM void_lines v), 0),
                'voidAmount', coalesce((SELECT sum(v."lineTotal")  FROM void_lines v), 0),
                'lines', coalesce((SELECT jsonb_agg(to_jsonb(v) ORDER BY v."voidedAt" DESC)
                                   FROM void_lines v), '[]'::jsonb)),
              -- `dl` rather than `d`, for the reason given on `losses` above: params.d is a
              -- date column in scope here, and to_jsonb(d) would serialise that instead.
              'discounts', jsonb_build_object(
                'discountBills',  coalesce((SELECT count(*)::int              FROM discount_lines dl), 0),
                'discountAmount', coalesce((SELECT sum(dl."discountAmount")   FROM discount_lines dl), 0),
                'lines', coalesce((SELECT jsonb_agg(to_jsonb(dl) ORDER BY dl."discountAt" DESC)
                                   FROM discount_lines dl), '[]'::jsonb)),
              -- `vl` rather than `v`, which void_lines already holds, and never `d`.
              'vouchers', jsonb_build_object(
                'voucherCount',  coalesce((SELECT count(*)::int             FROM voucher_lines vl), 0),
                'voucherAmount', coalesce((SELECT sum(vl."voucherAmount")   FROM voucher_lines vl), 0),
                'lines', coalesce((SELECT jsonb_agg(to_jsonb(vl) ORDER BY vl."redeemedAt" DESC)
                                   FROM voucher_lines vl), '[]'::jsonb)),
              'timeReductions', jsonb_build_object(
                'reducedSessions',  coalesce((SELECT count(*)::int            FROM time_reduction_lines r), 0),
                'forgoneRevenue',   coalesce((SELECT sum(r."forgoneRevenue")  FROM time_reduction_lines r), 0),
                'lines', coalesce((SELECT jsonb_agg(to_jsonb(r) ORDER BY r."closedAt" DESC)
                                   FROM time_reduction_lines r), '[]'::jsonb)),
              -- The two halves of override_lines, split the same way the tile is: promo on the
              -- kind, friend on everything else, so a row with no kind appears in exactly one
              -- section and the two lists together are still the whole of override_lines.
              -- Both sections keep the scoped names they have always had: inside `promos` and
              -- inside `friendRates`, "overrideSessions" and "forgoneRevenue" can only mean
              -- that section's own overrides. It is the TILE figures that had to be renamed,
              -- because there the same words sit at the top level with nothing scoping them.
              'promos', jsonb_build_object(
                'overrideSessions', coalesce((SELECT count(*)::int           FROM override_lines o
                                              WHERE o."rateOverrideKind" = 'PROMO'), 0),
                'forgoneRevenue',   coalesce((SELECT sum(o."forgoneRevenue") FROM override_lines o
                                              WHERE o."rateOverrideKind" = 'PROMO'), 0),
                'lines', coalesce((SELECT jsonb_agg(to_jsonb(o) ORDER BY o."openedAt" DESC)
                                   FROM override_lines o
                                   WHERE o."rateOverrideKind" = 'PROMO'), '[]'::jsonb)),
              'friendRates', jsonb_build_object(
                'overrideSessions', coalesce((SELECT count(*)::int           FROM override_lines o
                                              WHERE o."rateOverrideKind" IS DISTINCT FROM 'PROMO'), 0),
                'forgoneRevenue',   coalesce((SELECT sum(o."forgoneRevenue") FROM override_lines o
                                              WHERE o."rateOverrideKind" IS DISTINCT FROM 'PROMO'), 0),
                'lines', coalesce((SELECT jsonb_agg(to_jsonb(o) ORDER BY o."openedAt" DESC)
                                   FROM override_lines o
                                   WHERE o."rateOverrideKind" IS DISTINCT FROM 'PROMO'), '[]'::jsonb)),
              'flatRates', jsonb_build_object(
                'flatSessions', coalesce((SELECT count(*)::int           FROM flat_lines f), 0),
                -- Summed from the rows listed beneath it, each already clamped at zero, so this
                -- equals the tile's figure by construction rather than by coincidence.
                'flatForgone',  coalesce((SELECT sum(f."forgoneRevenue") FROM flat_lines f), 0),
                'lines', coalesce((SELECT jsonb_agg(to_jsonb(f) ORDER BY f."openedAt" DESC)
                                   FROM flat_lines f), '[]'::jsonb))
            )::text AS detail
            FROM params p
            """;

    /*
     * The owner's month, in one statement, and it has to agree with the nightly report to the
     * centavo -- so the CTEs that decide what a sale is (closed_bills), what a live line is
     * (live_lines), what an expense is (live_expenses) and each of the seven giveaway rows are
     * the daily ones with the single-date predicate widened to a range, not rewritten. A test
     * asserts that the sum of dailyReport() over each night of a range equals this report for
     * the range, and the widening is the only thing that makes that hold.
     *
     * Two windows: the period asked for, and the previous one the service computed (the
     * like-for-like rule lives in ReportServiceImpl.previousPeriod). closed_bills spans both,
     * and every figure below says which window it reads with an explicit BETWEEN rather than
     * assuming the two are contiguous -- for a month-to-date they are not.
     *
     * A TRADING DAY is a business_date with at least one sale or a cash_count row. Every "per
     * day" figure divides by trading days, never by calendar days: a hall closed on Tuesdays
     * would otherwise report a fifth of its takings as missing.
     *
     * Aliases are deliberately never `d`, `p` (other than params itself) or `prev_d`, and the
     * params columns are from_d / to_d / prev_from / prev_to, for the reason on the daily SQL: an
     * unqualified name inside to_jsonb() that happens to match a params column binds to the
     * column and silently turns a section into a string.
     */
    private static final String PERIOD_REPORT_SQL = """
            WITH params AS (
              SELECT cast(:branchId as uuid) AS branch_id,
                     cast(:fromDate as date) AS from_d,
                     cast(:toDate as date)   AS to_d,
                     cast(:prevFrom as date) AS prev_from,
                     cast(:prevTo as date)   AS prev_to,
                     -- First month of the expense grid: the month `to` falls in and the five before it.
                     (date_trunc('month', cast(:toDate as date)) - interval '5 months')::date AS grid_from
            ),
            windows AS (
              SELECT 'current' AS which, p.from_d AS win_from, p.to_d AS win_to FROM params p
              UNION ALL
              SELECT 'previous', p.prev_from, p.prev_to FROM params p
            ),
            closed_bills AS (
              -- The daily CTE, widened. UNSETTLED counts as a sale here for the same reason it
              -- does there: the night the table was played is the night the sale belongs to.
              SELECT b.* FROM bill b, params p
              WHERE b.branch_id = p.branch_id AND b.status IN ('CLOSED', 'UNSETTLED')
                AND b.business_date BETWEEN p.prev_from AND p.to_d
            ),
            live_lines AS (
              SELECT l.*, b.business_date FROM bill_line l
              JOIN closed_bills b ON b.id = l.bill_id
              WHERE l.voided_at IS NULL
            ),
            live_expenses AS (
              -- Dated by the expense's OWN business_date, voided rows excluded, as on the daily.
              -- Reaches back to the start of the six-month grid, which is earlier than the
              -- previous window; every reader below narrows to the window it wants.
              SELECT e.* FROM expense e, params p
              WHERE e.branch_id = p.branch_id AND e.voided_at IS NULL
                AND e.business_date BETWEEN LEAST(p.prev_from, p.grid_from) AND p.to_d
            ),
            trading_days AS (
              SELECT b.business_date AS bd FROM closed_bills b
              UNION
              SELECT cc.business_date FROM cash_count cc, params p
              WHERE cc.branch_id = p.branch_id AND cc.business_date BETWEEN p.prev_from AND p.to_d
            ),
            day_sales AS (
              SELECT b.business_date                    AS bd,
                     count(*)::int                      AS bills,
                     coalesce(sum(b.total_amount), 0)   AS gross,
                     coalesce(sum(b.total_cost), 0)     AS cogs
              FROM closed_bills b GROUP BY b.business_date
            ),
            day_opex AS (
              SELECT e.business_date AS bd, coalesce(sum(e.amount), 0) AS opex
              FROM live_expenses e GROUP BY e.business_date
            ),
            days AS (
              -- One row per calendar night from the earlier window's start to `to`, trading or
              -- not, so byDay has a slot for every night and a closed Tuesday shows as a gap
              -- rather than vanishing from the trend.
              SELECT cal.bd,
                     (cal.bd IN (SELECT td.bd FROM trading_days td)) AS trading,
                     coalesce(ds.bills, 0) AS bills,
                     coalesce(ds.gross, 0) AS gross,
                     coalesce(ds.cogs, 0)  AS cogs,
                     coalesce(dx.opex, 0)  AS opex
              FROM (SELECT generate_series(p.prev_from, p.to_d, interval '1 day')::date AS bd FROM params p) cal
              LEFT JOIN day_sales ds ON ds.bd = cal.bd
              LEFT JOIN day_opex  dx ON dx.bd = cal.bd
            ),
            window_totals AS (
              SELECT w.which,
                     coalesce(sum(dy.bills), 0)::int                     AS bills,
                     coalesce(sum(dy.gross), 0)                          AS gross,
                     coalesce(sum(dy.cogs), 0)                           AS "costOfGoods",
                     coalesce(sum(dy.gross - dy.cogs), 0)                AS "grossProfit",
                     coalesce(sum(dy.opex), 0)                           AS "operatingExpenses",
                     coalesce(sum(dy.gross - dy.cogs - dy.opex), 0)      AS net,
                     count(*) FILTER (WHERE dy.trading)::int             AS "tradingDays"
              FROM windows w LEFT JOIN days dy ON dy.bd BETWEEN w.win_from AND w.win_to
              GROUP BY w.which
            ),
            headline AS (
              -- NULL, not zero, where the figure is undefined: a gross of 0.00 per trading day
              -- across zero trading days would be a number the owner could compare against.
              SELECT wt.*,
                     CASE WHEN wt."tradingDays" > 0 THEN round(wt.gross / wt."tradingDays", 2) END AS "grossPerTradingDay",
                     CASE WHEN wt."tradingDays" > 0 THEN round(wt.net / wt."tradingDays", 2) END   AS "netPerTradingDay",
                     CASE WHEN wt.gross > 0 THEN round(wt."grossProfit" / wt.gross * 100, 1) END    AS "grossMarginPercent"
              FROM window_totals wt
            ),
            break_even AS (
              -- opex / gross margin ratio / trading days: the gross a trading day must take for
              -- the margin on it to cover the period's operating cost. Undefined without sales,
              -- and equally undefined when the margin is not positive -- no level of sales
              -- covers costs when every peso sold costs more than a peso -- so both are the
              -- `computable = false` case rather than a division.
              SELECT CASE WHEN h.gross > 0 AND h."grossProfit" > 0 AND h."tradingDays" > 0
                          THEN round(h."operatingExpenses" / (h."grossProfit" / h.gross) / h."tradingDays", 2)
                     END                                                 AS "requiredGrossPerTradingDay",
                     h."grossPerTradingDay"                              AS "actualGrossPerTradingDay",
                     (h.gross > 0 AND h."grossProfit" > 0 AND h."tradingDays" > 0) AS computable
              FROM headline h WHERE h.which = 'current'
            ),
            day_of_week AS (
              -- Averages over TRADING days of that weekday only. Seven rows always, Monday
              -- first, and NULL where the weekday never traded: "no Tuesdays" and "Tuesdays
              -- averaged zero" are different answers to "should we open on Tuesdays".
              SELECT dow.iso                                             AS "isoDay",
                     count(dy.bd) FILTER (WHERE dy.trading)::int         AS "tradingDays",
                     CASE WHEN count(dy.bd) FILTER (WHERE dy.trading) > 0
                          THEN round(sum(dy.gross) FILTER (WHERE dy.trading)
                                     / count(dy.bd) FILTER (WHERE dy.trading), 2) END AS "avgGross",
                     CASE WHEN count(dy.bd) FILTER (WHERE dy.trading) > 0
                          THEN round(sum(dy.bills) FILTER (WHERE dy.trading)::numeric
                                     / count(dy.bd) FILTER (WHERE dy.trading), 1) END AS "avgBills",
                     CASE WHEN count(dy.bd) FILTER (WHERE dy.trading) > 0
                          THEN round(sum(dy.gross - dy.cogs - dy.opex) FILTER (WHERE dy.trading)
                                     / count(dy.bd) FILTER (WHERE dy.trading), 2) END AS "avgNet"
              FROM (VALUES (1), (2), (3), (4), (5), (6), (7)) AS dow(iso)
              CROSS JOIN params p
              LEFT JOIN days dy ON extract(isodow FROM dy.bd) = dow.iso
                               AND dy.bd BETWEEN p.from_d AND p.to_d
              GROUP BY dow.iso
            ),
            sales_by_hour AS (
              -- The daily's extract(), summed across the period.
              SELECT extract(hour FROM (b.closed_at AT TIME ZONE INTERVAL '+08:00'))::int AS hour,
                     count(*)::int AS bills, coalesce(sum(b.total_amount), 0) AS amount
              FROM closed_bills b, params p
              WHERE b.business_date BETWEEN p.from_d AND p.to_d GROUP BY 1
            ),
            expenses_by_category AS (
              -- Both windows in one pass, so a category that was paid last month and not this
              -- one still appears, with this month's zero beside last month's figure. Grouped
              -- on the name and not filtered on archived_at, as on the daily.
              SELECT ec.name AS category,
                     coalesce(sum(e.amount) FILTER (WHERE e.business_date BETWEEN p.from_d AND p.to_d), 0)       AS amount,
                     coalesce(sum(e.amount) FILTER (WHERE e.business_date BETWEEN p.prev_from AND p.prev_to), 0) AS "previousAmount"
              FROM live_expenses e
              JOIN expense_category ec ON ec.id = e.expense_category_id, params p
              GROUP BY ec.name
              HAVING coalesce(sum(e.amount) FILTER (WHERE e.business_date BETWEEN p.from_d AND p.to_d), 0) > 0
                  OR coalesce(sum(e.amount) FILTER (WHERE e.business_date BETWEEN p.prev_from AND p.prev_to), 0) > 0
            ),
            expenses_by_category_pct AS (
              SELECT ebc.category, ebc.amount, ebc."previousAmount",
                     CASE WHEN h.gross > 0 THEN round(ebc.amount / h.gross * 100, 1) END AS "percentOfGross"
              FROM expenses_by_category ebc, headline h WHERE h.which = 'current'
            ),
            grid_months AS (
              SELECT generate_series(p.grid_from, date_trunc('month', p.to_d)::date, interval '1 month')::date AS month_start
              FROM params p
            ),
            grid_cells AS (
              -- Through `to` and no further: the last column is the month `to` falls in, and it
              -- is partial when `to` is not a month end. The page says so.
              SELECT ec.name AS category,
                     date_trunc('month', e.business_date)::date AS month_start,
                     sum(e.amount) AS amount
              FROM live_expenses e
              JOIN expense_category ec ON ec.id = e.expense_category_id, params p
              WHERE e.business_date BETWEEN p.grid_from AND p.to_d
              GROUP BY 1, 2
            ),
            grid_rows AS (
              -- One row per category, the six months as a positional array in month order
              -- (zeros filled) so the client draws a grid rather than pivoting one.
              SELECT cat.category,
                     (SELECT jsonb_agg(coalesce(gc.amount, 0) ORDER BY gm.month_start)
                      FROM grid_months gm
                      LEFT JOIN grid_cells gc ON gc.month_start = gm.month_start AND gc.category = cat.category) AS amounts,
                     (SELECT coalesce(sum(gc.amount), 0) FROM grid_cells gc WHERE gc.category = cat.category) AS total
              FROM (SELECT DISTINCT gc.category FROM grid_cells gc) cat
            ),
            segment_shares AS (
              /*
               * The daily's segment_minutes -- WALL CLOCK, PAUSES INCLUDED, see the long comment
               * there for why -- with the charge attached.
               *
               * The charge is the session's live TIME line total, which is the finalised figure
               * the customer paid for the time: it already reflects a rate override, a flat fee
               * or a reduced-minutes rewrite, none of which a segment's own rate_per_minute
               * carries (a flat session's segments say zero). It is apportioned across the
               * session's segments by minutes, so a session moved between tables credits each
               * with its share and the tables sum to the period's time revenue.
               */
              SELECT sg.pool_table_id,
                     extract(epoch FROM (coalesce(sg.ended_at, now()) - sg.started_at)) / 60.0 AS minutes,
                     sum(extract(epoch FROM (coalesce(sg.ended_at, now()) - sg.started_at)) / 60.0)
                       OVER (PARTITION BY sg.session_id)                                       AS session_minutes,
                     coalesce(tl.time_charged, 0)                                              AS time_charged
              FROM session_segment sg
              JOIN table_session ts ON ts.id = sg.session_id
              JOIN closed_bills b   ON b.id = ts.bill_id
              -- Joined, not a correlated subquery: live_lines is a CTE with no index, and a
              -- lookup per segment scanned every line of the period for every session --
              -- eight seconds over six months of nights.
              LEFT JOIN (SELECT l.session_id, sum(l.line_total) AS time_charged
                         FROM live_lines l WHERE l.line_kind = 'TIME' GROUP BY l.session_id) tl
                     ON tl.session_id = ts.id, params p
              WHERE b.business_date BETWEEN p.from_d AND p.to_d
            ),
            table_stats AS (
              SELECT ss.pool_table_id,
                     sum(ss.minutes) AS minutes,
                     sum(CASE WHEN ss.session_minutes > 0
                              THEN ss.time_charged * ss.minutes / ss.session_minutes ELSE 0 END) AS time_revenue
              FROM segment_shares ss GROUP BY ss.pool_table_id
            ),
            tables AS (
              -- Live tables always; an archived one only if it was played in the period, or its
              -- revenue would be attributed to nothing. Utilisation is over the period's
              -- TRADING days of 19 hours each, and NULL when there were none.
              SELECT t.name                                                       AS "tableName",
                     round(coalesce(st.minutes, 0))::int                          AS "occupiedMinutes",
                     CASE WHEN h."tradingDays" > 0
                          THEN round(coalesce(st.minutes, 0) / (19 * 60 * h."tradingDays") * 100, 1) END
                                                                                  AS "utilisationPercent",
                     round(coalesce(st.time_revenue, 0), 2)                       AS "timeRevenue",
                     CASE WHEN coalesce(st.minutes, 0) > 0
                          THEN round(coalesce(st.time_revenue, 0) / (st.minutes / 60.0), 2) END
                                                                                  AS "revenuePerOccupiedHour",
                     t.table_number, t.name
              FROM pool_table t
              LEFT JOIN table_stats st ON st.pool_table_id = t.id
              CROSS JOIN (SELECT h1."tradingDays" FROM headline h1 WHERE h1.which = 'current') h, params p
              WHERE t.branch_id = p.branch_id AND (t.archived_at IS NULL OR st.pool_table_id IS NOT NULL)
            ),
            products_sold AS (
              -- Snapshotted line figures -- the money is never read off product -- grouped on
              -- the product (every PRODUCT line carries one, by constraint) so the unsold list
              -- below can be its complement. Only the name is the product's current one.
              SELECT pr.name,
                     sum(l.quantity)                                          AS quantity,
                     sum(l.line_total)                                        AS revenue,
                     sum(l.line_cost)                                         AS cost,
                     sum(l.line_total - l.line_cost)                          AS margin,
                     CASE WHEN sum(l.line_total) > 0
                          THEN round(sum(l.line_total - l.line_cost) / sum(l.line_total) * 100, 1) END
                                                                              AS "marginPercent",
                     pr.id                                                    AS product_id
              FROM live_lines l
              JOIN product pr ON pr.id = l.product_id, params p
              WHERE l.business_date BETWEEN p.from_d AND p.to_d AND l.line_kind = 'PRODUCT'
              GROUP BY pr.id, pr.name
            ),
            unsold_products AS (
              -- Capital on the shelf: stock that did not move once in the period, valued at
              -- the product's current average cost.
              SELECT pr.name,
                     pr.qty_on_hand                            AS "qtyOnHand",
                     pr.avg_cost                               AS "avgCost",
                     round(pr.qty_on_hand * pr.avg_cost, 2)    AS "capitalOnShelf"
              FROM product pr, params p
              WHERE pr.branch_id = p.branch_id AND pr.archived_at IS NULL AND pr.qty_on_hand > 0
                AND NOT EXISTS (SELECT 1 FROM products_sold ps WHERE ps.product_id = pr.id)
            ),
            voids AS (
              SELECT count(*)::int AS "voidCount", coalesce(sum(l.line_total), 0) AS "voidAmount"
              FROM bill_line l JOIN bill b ON b.id = l.bill_id, params p
              WHERE b.branch_id = p.branch_id AND b.business_date BETWEEN p.from_d AND p.to_d
                AND l.voided_at IS NOT NULL
            ),
            overrides AS (
              -- The daily's overrides CTE, widened. FILTER rather than GROUP BY for the reason
              -- given there: a zero-row CTE would take the whole givenAway object with it.
              SELECT count(*) FILTER (WHERE ts.rate_override_kind = 'PROMO')::int
                       AS "promoSessions",
                     coalesce(sum((ts.standard_rate_per_minute - ts.rate_override_per_minute)
                                  * coalesce(ts.billed_minutes, 0))
                              FILTER (WHERE ts.rate_override_kind = 'PROMO'), 0)
                       AS "promoForgone",
                     count(*) FILTER (WHERE ts.rate_override_kind IS DISTINCT FROM 'PROMO')::int
                       AS "friendSessions",
                     coalesce(sum((ts.standard_rate_per_minute - ts.rate_override_per_minute)
                                  * coalesce(ts.billed_minutes, 0))
                              FILTER (WHERE ts.rate_override_kind IS DISTINCT FROM 'PROMO'), 0)
                       AS "friendForgone"
              FROM table_session ts JOIN bill b ON b.id = ts.bill_id, params p
              WHERE b.branch_id = p.branch_id AND b.business_date BETWEEN p.from_d AND p.to_d
                AND ts.rate_override_per_minute IS NOT NULL
            ),
            flats AS (
              SELECT count(*)::int AS "flatSessions",
                     coalesce(sum(GREATEST(
                       coalesce(ts.standard_rate_per_minute, 0) * coalesce(ts.billed_minutes, 0)
                         - ts.flat_amount, 0)), 0) AS "flatForgone"
              FROM table_session ts JOIN bill b ON b.id = ts.bill_id, params p
              WHERE b.branch_id = p.branch_id AND b.business_date BETWEEN p.from_d AND p.to_d
                AND ts.flat_amount IS NOT NULL
            ),
            time_reductions AS (
              SELECT count(*)::int AS "reducedSessions",
                     coalesce(sum((ts.billed_minutes - ts.billed_minutes_override)
                                  * coalesce(ts.rate_override_per_minute, ts.standard_rate_per_minute)), 0)
                       AS "timeReductionForgone"
              FROM table_session ts JOIN bill b ON b.id = ts.bill_id, params p
              WHERE b.branch_id = p.branch_id AND b.business_date BETWEEN p.from_d AND p.to_d
                AND ts.billed_minutes_override IS NOT NULL
            ),
            discounts AS (
              SELECT count(*)::int AS "discountBills",
                     coalesce(sum(b.discount_amount), 0) AS "discountAmount"
              FROM bill b, params p
              WHERE b.branch_id = p.branch_id AND b.business_date BETWEEN p.from_d AND p.to_d
                AND b.discount_amount > 0
            ),
            vouchers AS (
              SELECT count(*)::int AS "voucherCount",
                     coalesce(sum(b.voucher_amount), 0) AS "voucherAmount"
              FROM bill b, params p
              WHERE b.branch_id = p.branch_id AND b.business_date BETWEEN p.from_d AND p.to_d
                AND b.voucher_amount > 0
            ),
            comps AS (
              -- Dated by the MOVEMENT's business_date, as on the daily, and valued at the
              -- product's current average cost: an estimate, unlike the seven exact rows.
              SELECT coalesce(sum(-sm.quantity_delta), 0) AS "compQuantity",
                     coalesce(sum(-sm.quantity_delta * pr.avg_cost), 0) AS "compEstimatedCost"
              FROM stock_movement sm JOIN product pr ON pr.id = sm.product_id, params p
              WHERE sm.branch_id = p.branch_id AND sm.business_date BETWEEN p.from_d AND p.to_d
                AND sm.reason = 'STAFF_COMP'
            ),
            given_away AS (
              -- The eight rows the daily carries, plus their sum and the sum as a share of gross.
              -- The comps estimate is inside the total; the page labels it as an estimate.
              SELECT to_jsonb(v) || to_jsonb(o) || to_jsonb(c) || to_jsonb(r) || to_jsonb(f)
                       || to_jsonb(disc) || to_jsonb(vch)                              AS lines,
                     (v."voidAmount" + o."promoForgone" + o."friendForgone" + f."flatForgone"
                       + r."timeReductionForgone" + disc."discountAmount" + vch."voucherAmount"
                       + c."compEstimatedCost")                                        AS total
              FROM voids v, overrides o, comps c, time_reductions r, flats f, discounts disc, vouchers vch
            ),
            cash AS (
              -- The generated variance column, summed. A night that balanced contributes zero
              -- and is not "a night with a variance".
              SELECT coalesce(sum(cc.variance), 0)                      AS "varianceTotal",
                     count(*) FILTER (WHERE cc.variance <> 0)::int      AS "nightsWithVariance",
                     count(*)::int                                      AS "countedNights"
              FROM cash_count cc, params p
              WHERE cc.branch_id = p.branch_id AND cc.business_date BETWEEN p.from_d AND p.to_d
            ),
            uncounted AS (
              -- Trading days in the period nobody counted the drawer on.
              SELECT count(*)::int AS "uncountedTradingDays"
              FROM days dy, params p
              WHERE dy.trading AND dy.bd BETWEEN p.from_d AND p.to_d
                AND NOT EXISTS (SELECT 1 FROM cash_count cc
                                WHERE cc.branch_id = p.branch_id AND cc.business_date = dy.bd)
            ),
            unsettled_aging AS (
              -- Debts still open, LIVE by status like the daily's `outstanding`, aged by the
              -- night they were played relative to the period. Bills dated after `to` are left
              -- out: a debt from this week is not a fact about last month.
              SELECT jsonb_build_object(
                       'count',  count(*) FILTER (WHERE b.business_date BETWEEN p.from_d AND p.to_d)::int,
                       'amount', coalesce(sum(b.total_amount) FILTER (WHERE b.business_date BETWEEN p.from_d AND p.to_d), 0))
                       AS "thisPeriod",
                     jsonb_build_object(
                       'count',  count(*) FILTER (WHERE b.business_date < p.from_d AND b.business_date >= p.from_d - 28)::int,
                       'amount', coalesce(sum(b.total_amount) FILTER (WHERE b.business_date < p.from_d AND b.business_date >= p.from_d - 28), 0))
                       AS "oneToFourWeeksBefore",
                     jsonb_build_object(
                       'count',  count(*) FILTER (WHERE b.business_date < p.from_d - 28)::int,
                       'amount', coalesce(sum(b.total_amount) FILTER (WHERE b.business_date < p.from_d - 28), 0))
                       AS older
              FROM bill b, params p
              WHERE b.branch_id = p.branch_id AND b.status = 'UNSETTLED' AND b.business_date <= p.to_d
            )
            SELECT jsonb_build_object(
              'from',              p.from_d,
              'to',                p.to_d,
              'previousFrom',      p.prev_from,
              'previousTo',        p.prev_to,
              'headline',          (SELECT to_jsonb(h) - 'which' FROM headline h WHERE h.which = 'current'),
              'previousHeadline',  (SELECT to_jsonb(h) - 'which' FROM headline h WHERE h.which = 'previous'),
              'breakEven',         (SELECT to_jsonb(be) FROM break_even be),
              'byDay',             coalesce((SELECT jsonb_agg(jsonb_build_object(
                                              'businessDate',      dy.bd,
                                              'trading',           dy.trading,
                                              'bills',             dy.bills,
                                              'gross',             dy.gross,
                                              'costOfGoods',       dy.cogs,
                                              'grossProfit',       dy.gross - dy.cogs,
                                              'operatingExpenses', dy.opex,
                                              'net',               dy.gross - dy.cogs - dy.opex)
                                            ORDER BY dy.bd)
                                            FROM days dy WHERE dy.bd BETWEEN p.from_d AND p.to_d), '[]'::jsonb),
              'byDayOfWeek',       (SELECT jsonb_agg(to_jsonb(dw) ORDER BY dw."isoDay") FROM day_of_week dw),
              'byHour',            coalesce((SELECT jsonb_agg(to_jsonb(s) ORDER BY s.hour) FROM sales_by_hour s), '[]'::jsonb),
              'expensesByCategory', coalesce((SELECT jsonb_agg(to_jsonb(ebc) ORDER BY ebc.amount DESC, ebc.category)
                                              FROM expenses_by_category_pct ebc), '[]'::jsonb),
              'expensesByMonth',   jsonb_build_object(
                                     'months', coalesce((SELECT jsonb_agg(to_char(gm.month_start, 'YYYY-MM') ORDER BY gm.month_start)
                                                         FROM grid_months gm), '[]'::jsonb),
                                     'rows',   coalesce((SELECT jsonb_agg(to_jsonb(gr) ORDER BY gr.total DESC, gr.category)
                                                         FROM grid_rows gr), '[]'::jsonb)),
              -- Weakest table first: lowest revenue per occupied hour, and a table nobody
              -- played (NULL) is the weakest of all. The two sort columns are dropped.
              'tables',            coalesce((SELECT jsonb_agg(to_jsonb(tb) - 'table_number' - 'name'
                                                              ORDER BY tb."revenuePerOccupiedHour" ASC NULLS FIRST,
                                                                       tb.table_number, tb.name)
                                             FROM tables tb), '[]'::jsonb),
              -- Thinnest margin first, so anything sold at or below cost is at the top.
              'products',          coalesce((SELECT jsonb_agg(to_jsonb(ps) - 'product_id'
                                                              ORDER BY ps.margin ASC, ps.revenue DESC, ps.name)
                                             FROM products_sold ps), '[]'::jsonb),
              'unsoldProducts',    coalesce((SELECT jsonb_agg(to_jsonb(up) ORDER BY up."capitalOnShelf" DESC, up.name)
                                             FROM unsold_products up), '[]'::jsonb),
              'givenAway',         (SELECT ga.lines || jsonb_build_object(
                                              'total',          ga.total,
                                              'percentOfGross', CASE WHEN h.gross > 0 THEN round(ga.total / h.gross * 100, 1) END)
                                    FROM given_away ga, headline h WHERE h.which = 'current'),
              'cash',              (SELECT to_jsonb(c) || to_jsonb(u) || jsonb_build_object('unsettled', to_jsonb(ua))
                                    FROM cash c, uncounted u, unsettled_aging ua)
            )::text AS report
            FROM params p
            """;

    @PersistenceContext
    private EntityManager entityManager;

    public String dailyReport(UUID branchId, LocalDate reportDate) {
        return (String) entityManager.createNativeQuery(DAILY_REPORT_SQL, String.class)
                .setParameter("branchId", branchId.toString())
                .setParameter("reportDate", reportDate.toString())
                .getSingleResult();
    }

    public String lossesDetail(UUID branchId, LocalDate reportDate) {
        return (String) entityManager.createNativeQuery(LOSSES_DETAIL_SQL, String.class)
                .setParameter("branchId", branchId.toString())
                .setParameter("reportDate", reportDate.toString())
                .getSingleResult();
    }

    // The previous window is the service's decision (see ReportServiceImpl.previousPeriod); the
    // SQL only reads both.
    public String periodReport(UUID branchId, LocalDate from, LocalDate to,
                               LocalDate previousFrom, LocalDate previousTo) {
        return (String) entityManager.createNativeQuery(PERIOD_REPORT_SQL, String.class)
                .setParameter("branchId", branchId.toString())
                .setParameter("fromDate", from.toString())
                .setParameter("toDate", to.toString())
                .setParameter("prevFrom", previousFrom.toString())
                .setParameter("prevTo", previousTo.toString())
                .getSingleResult();
    }
}
