package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.Payment;
import com.supremebilliardshall.billiards_hall_system.entity.PaymentMethod;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends BranchScopedRepository<Payment> {

    @Query("""
            select p from Payment p
            where p.branchId = :#{@branchContext.currentBranchId}
              and p.billId = :billId
            """)
    Optional<Payment> findByBillId(@Param("billId") UUID billId);

    // The replay lookup. payment_idempotency_key is unique on (branch_id, idempotency_key).
    @Query("""
            select p from Payment p
            where p.branchId = :#{@branchContext.currentBranchId}
              and p.idempotencyKey = :idempotencyKey
            """)
    Optional<Payment> findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    // Duplicate-reference warning at checkout: the same GCash reference typed twice.
    @Query("""
            select count(p) > 0 from Payment p
            where p.branchId = :#{@branchContext.currentBranchId}
              and p.referenceNo = :referenceNo
            """)
    boolean existsByReferenceNo(@Param("referenceNo") String referenceNo);

    // Expected drawer contents for a business day. Grouped on the generated business_date
    // column, never on a date computed in Java.
    @Query("""
            select coalesce(sum(p.amount), 0) from Payment p
            where p.branchId = :#{@branchContext.currentBranchId}
              and p.businessDate = :businessDate
              and p.method = :method
            """)
    BigDecimal sumAmountByBusinessDateAndMethod(@Param("businessDate") LocalDate businessDate,
                                                @Param("method") PaymentMethod method);

    default BigDecimal sumCashForBusinessDate(LocalDate businessDate) {
        return sumAmountByBusinessDateAndMethod(businessDate, PaymentMethod.CASH);
    }

    // Trading that happened on a business day AFTER that day was signed off. The night's
    // takings keep landing on the same business_date until 05:00, so a straggler served at
    // 03:30 joins a day closed at 03:00 — and the frozen expected_cash stops being true.
    //
    // Derived rather than flagged on the row: a stored "superseded" boolean would be one more
    // thing that can disagree with the ledger, and this cannot.
    @Query(value = """
            SELECT count(*)::int                                                  AS sales,
                   coalesce(sum(p.amount), 0)                                     AS amount,
                   coalesce(sum(p.amount) FILTER (WHERE p.method = 'CASH'), 0)    AS cash
            FROM payment p
            WHERE p.branch_id = cast(:branchId as uuid)
              AND p.business_date = cast(:businessDate as date)
              AND p.taken_at > cast(:since as timestamptz)
            """, nativeQuery = true)
    AfterCloseProjection findActivityAfter(@Param("branchId") java.util.UUID branchId,
                                           @Param("businessDate") LocalDate businessDate,
                                           @Param("since") java.time.OffsetDateTime since);

    interface AfterCloseProjection {
        int getSales();
        BigDecimal getAmount();
        BigDecimal getCash();
    }
}
