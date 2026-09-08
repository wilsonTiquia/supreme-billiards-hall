package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.Bill;
import com.supremebilliardshall.billiards_hall_system.entity.BillStatus;
import com.supremebilliardshall.billiards_hall_system.entity.SessionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BillRepository extends BranchScopedRepository<Bill> {

    // Bills that are still open with nothing running on them: every session has closed, so
    // the floor shows the table free and no longer points anywhere at this bill.
    //
    // Deliberately NOT scoped to the current business day. Closing a day checks for open
    // sessions, not unpaid bills, so an unsettled bill survives the close — and if this only
    // listed tonight's, that bill would vanish from every screen the moment the date rolled
    // and the money would simply never be collected.
    //
    // The enum values are bound as parameters rather than written inline, for the same reason
    // TableSessionRepository does it: Hibernate renders an inline enum literal as
    // 'OPEN'::BillStatus, naming the Java class rather than the Postgres type.
    @Query("""
            select b from Bill b
            where b.branchId = :#{@branchContext.currentBranchId}
              and b.status = :status
              and not exists (
                    select 1 from TableSession s
                    where s.billId = b.id
                      and s.status in :liveStatuses)
            order by b.openedAt desc
            """)
    List<Bill> findByStatusWithNoLiveSession(@Param("status") BillStatus status,
                                             @Param("liveStatuses") Collection<SessionStatus> liveStatuses);

    default List<Bill> findUnsettled() {
        return findByStatusWithNoLiveSession(
                BillStatus.OPEN, List.of(SessionStatus.OPEN, SessionStatus.PAUSED));
    }

    // Every debt in this branch, newest first — bills deliberately left unpaid and awaiting
    // collection. Across ALL business dates, for the reason findUnsettled gives above: a debt
    // that disappeared at the date roll would never be collected, and this one can legitimately
    // sit for a month.
    //
    // Ordered on unsettled_at, which is when the debt was recorded, rather than opened_at: that
    // is the order the partial index bill_unsettled_idx holds them in, and it is the order the
    // question "what is still outstanding" is asked in.
    //
    // The status is bound as a parameter rather than written inline, for the reason
    // findByStatusWithNoLiveSession gives: Hibernate renders an inline enum literal as
    // 'UNSETTLED'::BillStatus, naming the Java class rather than the Postgres type.
    @Query("""
            select b from Bill b
            where b.branchId = :#{@branchContext.currentBranchId}
              and b.status = :status
            order by b.unsettledAt desc
            """)
    List<Bill> findByStatusOrderByUnsettledAtDesc(@Param("status") BillStatus status);

    default List<Bill> findUnpaid() {
        return findByStatusOrderByUnsettledAtDesc(BillStatus.UNSETTLED);
    }

    // One night's settled sales, newest first, for the owner browsing receipts.
    /*
     * Native because business_date is a generated column that JPQL cannot see, and because the
     * payment method is a Postgres enum that has to be cast to text before it will bind back.
     *
     * THE JOIN TO PAYMENT IS OUTER, AND WAS INNER UNTIL VOUCHERS EXISTED. It said then that a
     * CLOSED bill always has exactly one, and a bill without one is not a sale anybody can
     * produce a receipt for. Both halves were true when they were written and the first stopped
     * being true the moment a bill could be closed with nothing to pay -- a prize winner who
     * plays ninety minutes on a two-hour voucher and buys nothing owes nothing, so no payment
     * row is written, and there is nothing for an inner join to match.
     *
     * Left as an inner join it did not error: the sale simply vanished from the owner's list,
     * and the countQuery agreed with it, so the page count was consistent and wrong together.
     * That is the failure worth naming here -- a silently short list is the one a reader
     * believes.
     *
     * `method` and `takenByUsername` are therefore NULL on such a row, and the service maps
     * them as null rather than defaulting them to something that looks like a payment.
     */
    @Query(value = """
            SELECT b.id                AS id,
                   b.receipt_no        AS receiptNo,
                   b.closed_at         AS closedAt,
                   b.total_amount      AS totalAmount,
                   p.method::text      AS method,
                   u.username          AS takenByUsername,
                   NOT EXISTS (SELECT 1 FROM table_session s WHERE s.bill_id = b.id) AS quickSale
            FROM bill b
            LEFT JOIN payment p ON p.bill_id = b.id
            LEFT JOIN app_user u ON u.id = p.taken_by
            WHERE b.branch_id = cast(:branchId as uuid)
              AND b.status = 'CLOSED'
              AND b.business_date = cast(:businessDate as date)
            ORDER BY b.receipt_no DESC
            """,
            countQuery = """
            SELECT count(*)
            FROM bill b
            LEFT JOIN payment p ON p.bill_id = b.id
            WHERE b.branch_id = cast(:branchId as uuid)
              AND b.status = 'CLOSED'
              AND b.business_date = cast(:businessDate as date)
            """,
            nativeQuery = true)
    Page<BillSummaryProjection> findSettledByBusinessDate(@Param("branchId") UUID branchId,
                                                          @Param("businessDate") LocalDate businessDate,
                                                          Pageable pageable);

    interface BillSummaryProjection {
        UUID getId();
        Long getReceiptNo();
        // Instant, not OffsetDateTime: a native-query projection hands back what the JDBC
        // driver produced for timestamptz, and Spring Data will not convert between the two.
        // The service attaches UTC, which is the offset the column is stored in.
        Instant getClosedAt();
        BigDecimal getTotalAmount();
        String getMethod();
        String getTakenByUsername();
        boolean getQuickSale();
    }
}
