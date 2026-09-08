package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Append-only: a trigger rejects UPDATE and DELETE, so only save() and reads happen here.
public interface AuditLogRepository extends BranchScopedRepository<AuditLog> {

    // Every filter optional. The date range is bounded by the caller rather than left null,
    // so only the two genuinely nullable filters need a cast to give Postgres a type.
    @Query("""
            select a from AuditLog a
            where a.branchId = :#{@branchContext.currentBranchId}
              and (cast(:entityTable as string) is null or a.entityTable = :entityTable)
              and (cast(:actorId as java.util.UUID) is null or a.actorId = :actorId)
              and a.businessDate between :from and :to
            order by a.occurredAt desc
            """)
    Page<AuditLog> search(@Param("entityTable") String entityTable,
                          @Param("actorId") UUID actorId,
                          @Param("from") LocalDate from,
                          @Param("to") LocalDate to,
                          Pageable pageable);

    /*
     * The audit feed: this table and the stock ledger read as one thing.
     *
     * They were always one thing to the owner. "Who took a bottle" and "who edited a price" are
     * the same question asked about different tables, and answering only the second on a screen
     * called Audit made deliveries, corrections and give-aways invisible — not hidden, just
     * never shown.
     *
     * Unioned in SQL rather than merged in Java because the two have to page and sort as one
     * list. A merge after two paged reads gives a feed where page two silently drops rows.
     *
     * Sales are excluded on purpose. Every sold bottle writes a stock movement, so including
     * SALE would put a few thousand rows a night in front of the one that matters — the
     * give-away at 02:00 with a thin reason. SALE_VOID is excluded too: a void already writes
     * its own audit row, and showing both would report one act twice.
     */
    String FEED_CTE = """
            WITH feed AS (
              SELECT a.id,
                     'AUDIT'                                              AS source,
                     a.action                                             AS action,
                     a.entity_table                                       AS entity_table,
                     a.actor_id                                           AS actor_id,
                     a.note                                               AS note,
                     a.occurred_at                                        AS occurred_at,
                     a.business_date                                      AS business_date,
                     NULL::numeric                                        AS quantity_delta,
                     -- A human label for the thing that changed. One LEFT JOIN per table the
                     -- log can point at; entity_table decides which one can match, so at most
                     -- one is ever non-null and coalesce picks it.
                     coalesce(pr.name,
                              pc.name,
                              ct.name,
                              pt.name,
                              rate_table.name,
                              sess_table.name,
                              bl.description,
                              -- Who the staff row is about. Named, because "Role changed" with
                              -- an empty subject is the one line in this log that has to say
                              -- WHOSE role, and the username is what the owner knows them by.
                              au.username,
                              to_char(cc.business_date, 'FMDay DD Mon YYYY'))              AS entity_label,
                     -- The customer type the session was opened on, so a rate override can be
                     -- named after it: "Happy Hour rate", not "Friend rate given" for every
                     -- type the owner has since added.
                     --
                     -- A JOIN and not a wider audit snapshot, for two reasons. audit_log is
                     -- append-only -- audit_log_append_only rejects UPDATE -- so rows already
                     -- written could never be backfilled, whereas customer_type_id is still on
                     -- every table_session row and this repairs the history as well as the new
                     -- rows. And the customer type is not a before/after CHANGE: putting it in
                     -- the diff would render a bogus "Customer type name  —  ->  Happy Hour".
                     sess_ct.name                                         AS customer_type_name,
                     -- Which kind of override, for the same label. Naming an override after
                     -- the customer type is right for a favour and WRONG for a promo: happy
                     -- hour runs on any customer type, so a promo on a Regular walk-in would
                     -- read "Regular rate", which is both plausible and untrue.
                     --
                     -- From the join and not the audit snapshot, for the reason the customer
                     -- type is: audit_log is append-only, so rows already written could never
                     -- be backfilled, whereas rate_override_kind is on every table_session row
                     -- including the ones V18 backfilled -- this labels the history too.
                     ts.rate_override_kind::text                          AS rate_override_kind
              FROM audit_log a
              LEFT JOIN product         pr ON a.entity_table = 'product'          AND pr.id = a.entity_id
              LEFT JOIN product_category pc ON a.entity_table = 'product_category' AND pc.id = a.entity_id
              LEFT JOIN customer_type   ct ON a.entity_table = 'customer_type'    AND ct.id = a.entity_id
              LEFT JOIN pool_table      pt ON a.entity_table = 'pool_table'       AND pt.id = a.entity_id
              LEFT JOIN pool_table_rate ptr ON a.entity_table = 'pool_table_rate' AND ptr.id = a.entity_id
              LEFT JOIN pool_table      rate_table ON rate_table.id = ptr.pool_table_id
              LEFT JOIN table_session   ts ON a.entity_table = 'table_session'    AND ts.id = a.entity_id
              LEFT JOIN pool_table      sess_table ON sess_table.id = ts.pool_table_id
              LEFT JOIN customer_type   sess_ct ON sess_ct.id = ts.customer_type_id
              LEFT JOIN bill_line       bl ON a.entity_table = 'bill_line'        AND bl.id = a.entity_id
              LEFT JOIN cash_count      cc ON a.entity_table = 'cash_count'       AND cc.id = a.entity_id
              -- Archived users included on purpose: the log outlives the account, and a row
              -- reading "Staff member archived ·" with no name would be worse than useless.
              LEFT JOIN app_user        au ON a.entity_table = 'app_user'         AND au.id = a.entity_id
              WHERE a.branch_id = cast(:branchId as uuid)

              UNION ALL

              SELECT sm.id,
                     'STOCK'                                              AS source,
                     -- Rendered as an action so both halves speak the same language. The
                     -- reason is the whole point of surfacing these: a correction and a
                     -- give-away both take stock out, and only this separates a recount from
                     -- someone helping themselves.
                     'STOCK_' || sm.reason::text                          AS action,
                     'stock_movement'                                     AS entity_table,
                     sm.actor_id                                          AS actor_id,
                     sm.note                                              AS note,
                     sm.occurred_at                                       AS occurred_at,
                     sm.business_date                                     AS business_date,
                     sm.quantity_delta                                    AS quantity_delta,
                     smp.name                                             AS entity_label,
                     -- A stock movement has no session and so no customer type. Typed, because
                     -- the UNION ALL has to agree on both the count and the type of every column.
                     NULL::text                                           AS customer_type_name,
                     NULL::text                                           AS rate_override_kind
              FROM stock_movement sm
              JOIN product smp ON smp.id = sm.product_id
              WHERE sm.branch_id = cast(:branchId as uuid)
                AND sm.reason IN ('DELIVERY', 'CORRECTION', 'STAFF_COMP')
            )
            """;

    String FEED_WHERE = """
            FROM feed
            WHERE business_date BETWEEN :fromDate AND :toDate
              AND (cast(:action as text) IS NULL OR action = :action)
              AND (cast(:actorId as uuid) IS NULL OR actor_id = cast(:actorId as uuid))
              AND (cast(:entityTable as text) IS NULL OR entity_table = :entityTable)
            """;

    String FEED_COLUMNS = """
            SELECT id                 AS "id",
                   source             AS "source",
                   action             AS "action",
                   entity_table       AS "entityTable",
                   entity_label       AS "entityLabel",
                   actor_id           AS "actorId",
                   note               AS "note",
                   occurred_at        AS "occurredAt",
                   business_date      AS "businessDate",
                   quantity_delta     AS "quantityDelta",
                   customer_type_name AS "customerTypeName",
                   rate_override_kind AS "rateOverrideKind"
            """;

    @Query(value = FEED_CTE + FEED_COLUMNS + FEED_WHERE
            + " ORDER BY occurred_at DESC OFFSET :offset LIMIT :size",
            nativeQuery = true)
    List<AuditFeedProjection> searchFeed(@Param("branchId") UUID branchId,
                                     @Param("action") String action,
                                     @Param("actorId") UUID actorId,
                                     @Param("entityTable") String entityTable,
                                     @Param("fromDate") LocalDate fromDate,
                                     @Param("toDate") LocalDate toDate,
                                     @Param("offset") int offset,
                                     @Param("size") int size);

    @Query(value = FEED_CTE + "SELECT count(*) " + FEED_WHERE, nativeQuery = true)
    long countMatching(@Param("branchId") UUID branchId,
                       @Param("action") String action,
                       @Param("actorId") UUID actorId,
                       @Param("entityTable") String entityTable,
                       @Param("fromDate") LocalDate fromDate,
                       @Param("toDate") LocalDate toDate);

    /**
     * The actions actually present in this branch's history, so the filter can offer a list to
     * pick from instead of a box to type a constant into. A filter you have to already know the
     * answer to use is not a filter.
     */
    @Query(value = """
            SELECT DISTINCT action FROM (
              SELECT action FROM audit_log WHERE branch_id = cast(:branchId as uuid)
              UNION ALL
              SELECT 'STOCK_' || reason::text FROM stock_movement
              WHERE branch_id = cast(:branchId as uuid)
                AND reason IN ('DELIVERY', 'CORRECTION', 'STAFF_COMP')
            ) actions ORDER BY action
            """, nativeQuery = true)
    List<String> findDistinctActions(@Param("branchId") UUID branchId);

    /** Everyone who has ever appeared in the feed, for the same reason. */
    @Query(value = """
            SELECT u.id AS "id", u.full_name AS "fullName", u.username AS "username"
            FROM app_user u
            WHERE u.id IN (
              SELECT actor_id FROM audit_log WHERE branch_id = cast(:branchId as uuid)
                                               AND actor_id IS NOT NULL
              UNION
              SELECT actor_id FROM stock_movement WHERE branch_id = cast(:branchId as uuid)
            )
            ORDER BY u.full_name
            """, nativeQuery = true)
    List<ActorProjection> findActors(@Param("branchId") UUID branchId);

    // Native projections hand back Instant for timestamptz, never OffsetDateTime.
    interface AuditFeedProjection {
        UUID getId();
        String getSource();
        String getAction();
        String getEntityTable();
        String getEntityLabel();
        UUID getActorId();
        String getNote();
        Instant getOccurredAt();
        LocalDate getBusinessDate();
        BigDecimal getQuantityDelta();
        String getCustomerTypeName();
        String getRateOverrideKind();
    }

    interface ActorProjection {
        UUID getId();
        String getFullName();
        String getUsername();
    }
}
