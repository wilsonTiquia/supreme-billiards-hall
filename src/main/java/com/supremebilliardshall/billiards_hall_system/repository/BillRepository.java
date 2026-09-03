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

    // One night's settled sales, newest first, for the owner browsing receipts.
    //
    // Native because business_date is a generated column that JPQL cannot see, and because the
    // payment method is a Postgres enum that has to be cast to text before it will bind back.
    // The join to payment is inner: a CLOSED bill always has exactly one, and a bill without
    // one is not a sale anybody can produce a receipt for.
    @Query(value = """
            SELECT b.id                AS id,
                   b.receipt_no        AS receiptNo,
                   b.closed_at         AS closedAt,
                   b.total_amount      AS totalAmount,
                   p.method::text      AS method,
                   u.username          AS takenByUsername,
                   NOT EXISTS (SELECT 1 FROM table_session s WHERE s.bill_id = b.id) AS quickSale
            FROM bill b
            JOIN payment p ON p.bill_id = b.id
            LEFT JOIN app_user u ON u.id = p.taken_by
            WHERE b.branch_id = cast(:branchId as uuid)
              AND b.status = 'CLOSED'
              AND b.business_date = cast(:businessDate as date)
            ORDER BY b.receipt_no DESC
            """,
            countQuery = """
            SELECT count(*)
            FROM bill b
            JOIN payment p ON p.bill_id = b.id
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
