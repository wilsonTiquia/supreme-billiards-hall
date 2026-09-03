package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.StockMovement;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// Append-only: only save() is ever called here.
public interface StockMovementRepository extends BranchScopedRepository<StockMovement> {

    // The "why is this count wrong" screen: one product's ledger, newest first.
    @Query("""
            select m from StockMovement m
            where m.branchId = :#{@branchContext.currentBranchId}
              and m.productId = :productId
            order by m.occurredAt desc
            """)
    List<StockMovement> findByProductId(@Param("productId") UUID productId);

    // The reconciliation check: the ledger is authoritative, qty_on_hand is only a cache.
    @Query("""
            select coalesce(sum(m.quantityDelta), 0) from StockMovement m
            where m.branchId = :#{@branchContext.currentBranchId}
              and m.productId = :productId
            """)
    BigDecimal sumQuantityDelta(@Param("productId") UUID productId);
}
