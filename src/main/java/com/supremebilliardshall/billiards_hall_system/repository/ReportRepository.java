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
              SELECT sg.pool_table_id,
                     sum(extract(epoch FROM (coalesce(sg.ended_at, now()) - sg.started_at)) / 60.0) AS minutes
              FROM session_segment sg
              JOIN table_session ts ON ts.id = sg.session_id
              JOIN closed_bills b   ON b.id = ts.bill_id, params p
              WHERE b.business_date = p.d GROUP BY sg.pool_table_id
            ),
            table_util AS (
              SELECT t.name AS "tableName",
                     round(coalesce(sm.minutes, 0))::int AS "billedMinutes",
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
              'losses',             (SELECT to_jsonb(v) || to_jsonb(o) || to_jsonb(c) || to_jsonb(r) || to_jsonb(f)
                                     FROM voids v, overrides o, comps c, time_reductions r, flats f),
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

    // The rows behind the dashboard's three loss figures.
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
            time_reduction_lines AS (
              SELECT t.name                              AS "poolTableName",
                     ts.billed_minutes                   AS "actualMinutes",
                     ts.billed_minutes_override          AS "chargedMinutes",
                     coalesce(ts.rate_override_per_minute, ts.standard_rate_per_minute)
                                                         AS "ratePerMinute",
                     ((ts.billed_minutes - ts.billed_minutes_override)
                        * coalesce(ts.rate_override_per_minute, ts.standard_rate_per_minute))
                                                         AS "forgoneRevenue",
                     u.username                          AS "actualUsername",
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
}
