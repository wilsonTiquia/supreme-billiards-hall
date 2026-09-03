package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.CashCount;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CashCountRepository extends BranchScopedRepository<CashCount> {

    // cash_count_day_key is unique on (branch_id, business_date).
    @Query("""
            select c from CashCount c
            where c.branchId = :#{@branchContext.currentBranchId}
              and c.businessDate = :businessDate
            """)
    Optional<CashCount> findByBusinessDate(@Param("businessDate") LocalDate businessDate);

    // Nights that traded and were never counted. The 05:00 job closes leftover sessions but not
    // the day itself, so an uncounted night simply stays open and silent — no drawer
    // reconciliation, and nothing anywhere saying so.
    //
    // Native, because business_date is a generated column and the cut-off is business_date_of(),
    // which only the database knows. Returns dates plus a bill count and nothing else: the cash
    // total is deliberately absent so this can never become a preview of the expected figure.
    @Query(value = """
            SELECT b.business_date AS "businessDate", count(*)::int AS bills
            FROM bill b
            WHERE b.branch_id = cast(:branchId as uuid)
              AND b.business_date < business_date_of(now())
              AND NOT EXISTS (
                    SELECT 1 FROM cash_count c
                    WHERE c.branch_id = b.branch_id
                      AND c.business_date = b.business_date)
            GROUP BY b.business_date
            ORDER BY b.business_date
            """, nativeQuery = true)
    List<UncountedDayProjection> findUncountedDaysBefore(@Param("branchId") UUID branchId);

    interface UncountedDayProjection {
        LocalDate getBusinessDate();
        int getBills();
    }
}
