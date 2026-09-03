package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.BillLine;
import com.supremebilliardshall.billiards_hall_system.entity.BillLineKind;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BillLineRepository extends BranchScopedRepository<BillLine> {

    @Query("""
            select l from BillLine l
            where l.branchId = :#{@branchContext.currentBranchId}
              and l.billId = :billId
            order by l.seq
            """)
    List<BillLine> findByBillId(@Param("billId") UUID billId);

    // bill_line_seq_key is unique on (bill_id, seq), so a new line takes the next number.
    @Query("""
            select coalesce(max(l.seq), 0) from BillLine l
            where l.branchId = :#{@branchContext.currentBranchId}
              and l.billId = :billId
            """)
    int findMaxSeq(@Param("billId") UUID billId);

    // Running item total for the floor view: live PRODUCT lines only, grouped so the view
    // stays one query no matter how many tables are occupied. Voided lines are retained in
    // the table but excluded from money.
    @Query("""
            select l.billId, coalesce(sum(l.lineTotal), 0) from BillLine l
            where l.branchId = :#{@branchContext.currentBranchId}
              and l.billId in :billIds
              and l.lineKind = :lineKind
              and l.voidedAt is null
            group by l.billId
            """)
    List<Object[]> sumTotalsByBill(@Param("billIds") Collection<UUID> billIds,
                                   @Param("lineKind") BillLineKind lineKind);

    default List<Object[]> sumLiveProductTotalsByBill(Collection<UUID> billIds) {
        return sumTotalsByBill(billIds, BillLineKind.PRODUCT);
    }

    @Query("""
            select coalesce(sum(l.lineTotal), 0) from BillLine l
            where l.branchId = :#{@branchContext.currentBranchId}
              and l.billId = :billId
              and l.lineKind = :lineKind
              and l.voidedAt is null
            """)
    BigDecimal sumTotals(@Param("billId") UUID billId, @Param("lineKind") BillLineKind lineKind);

    // How many items are on the bill — the floor card says "3 items · P600" and the value
    // alone cannot tell you whether that is one round or six.
    //
    // The kind is BOUND, not written inline, for the same reason every other query here binds
    // it: Hibernate renders an inline enum literal as 'PRODUCT'::BillLineKind, naming the Java
    // class instead of the Postgres type, and the statement fails at runtime.
    @Query("""
            select coalesce(sum(l.quantity), 0) from BillLine l
            where l.branchId = :#{@branchContext.currentBranchId}
              and l.billId in :billIds
              and l.lineKind = :lineKind
              and l.voidedAt is null
            """)
    BigDecimal sumQuantityByBills(@Param("billIds") Collection<UUID> billIds,
                                  @Param("lineKind") BillLineKind lineKind);

    default BigDecimal sumLiveProductQuantity(Collection<UUID> billIds) {
        return sumQuantityByBills(billIds, BillLineKind.PRODUCT);
    }

    default BigDecimal sumLiveProductTotals(UUID billId) {
        return sumTotals(billId, BillLineKind.PRODUCT);
    }
}
