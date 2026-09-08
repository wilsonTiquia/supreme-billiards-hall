package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.Expense;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface ExpenseRepository extends BranchScopedRepository<Expense> {

    // The night's list. Voided rows included deliberately — they are flagged in the response
    // and stay on screen struck through, because "we recorded this and took it back" is what
    // happened and hiding it is how a mistake gets recorded twice.
    @Query("""
            select e from Expense e
            where e.branchId = :#{@branchContext.currentBranchId}
              and e.businessDate = :businessDate
            order by e.incurredAt desc
            """)
    List<Expense> findByBusinessDate(@Param("businessDate") LocalDate businessDate);

    // What left the till on this night. Cash only: an expense settled by bank transfer is
    // operating cost but never touched the drawer, so it cannot explain a variance.
    @Query("""
            select coalesce(sum(e.amount), 0) from Expense e
            where e.branchId = :#{@branchContext.currentBranchId}
              and e.businessDate = :businessDate
              and e.paidFromDrawer = true
              and e.voidedAt is null
            """)
    BigDecimal sumPaidFromDrawer(@Param("businessDate") LocalDate businessDate);

    /*
     * Cash expenses recorded on this business day AFTER the day was signed off.
     *
     * The mirror of PaymentRepository.findActivityAfter, and needed for the same reason: the
     * night runs to 05:00, so money can leave the till after the count. cash_expenses is
     * frozen at the moment of counting, exactly as cash_sales is, and once either moves the
     * variance is measured against a total that no longer describes the drawer.
     *
     * Dated by created_at, not incurred_at: what matters is when the row appeared relative to
     * the close, and incurred_at is the one the recorder could have back-dated.
     *
     * Native because business_date is a generated column. Branch passed explicitly — this
     * reads the count's own branch rather than the session's, matching findActivityAfter.
     */
    @Query(value = """
            SELECT count(*)::int AS "expenses",
                   coalesce(sum(e.amount), 0) AS "amount"
            FROM expense e
            WHERE e.branch_id = cast(:branchId as uuid)
              AND e.business_date = cast(:businessDate as date)
              AND e.paid_from_drawer
              AND e.voided_at IS NULL
              AND e.created_at > cast(:closedAt as timestamptz)
            """, nativeQuery = true)
    AfterCloseProjection findCashExpensesAfter(@Param("branchId") UUID branchId,
                                               @Param("businessDate") LocalDate businessDate,
                                               @Param("closedAt") OffsetDateTime closedAt);

    interface AfterCloseProjection {
        int getExpenses();
        BigDecimal getAmount();
    }
}
