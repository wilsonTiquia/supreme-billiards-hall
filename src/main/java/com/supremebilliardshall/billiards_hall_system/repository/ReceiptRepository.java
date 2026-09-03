package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.Receipt;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

// Append-only by trigger: only save() is ever called here.
public interface ReceiptRepository extends BranchScopedRepository<Receipt> {

    @Query("""
            select r from Receipt r
            where r.branchId = :#{@branchContext.currentBranchId}
              and r.billId = :billId
            """)
    Optional<Receipt> findByBillId(@Param("billId") UUID billId);
}
