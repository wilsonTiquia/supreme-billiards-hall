package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends BranchScopedRepository<Product> {

    // The POS product list: optional category, optional name search, optional active-only.
    //
    // includeArchived exists for the admin catalogue only. Archiving used to make a product
    // vanish from the owner's own screen with no way back, so the one place that can undo it
    // has to be able to see it. The counter never passes this — the service refuses it for a
    // non-admin — so an archived product can still never reach the POS grid.
    @Query("""
            select p from Product p
            where p.branchId = :#{@branchContext.currentBranchId}
              and (:includeArchived = true or p.archivedAt is null)
              and (cast(:categoryId as java.util.UUID) is null or p.categoryId = :categoryId)
              and (cast(:q as string) is null
                   or lower(p.name) like lower(concat('%', cast(:q as string), '%')))
              and (:activeOnly = false or p.isActive = true)
            order by p.archivedAt nulls first, p.name
            """)
    List<Product> search(@Param("categoryId") UUID categoryId,
                         @Param("q") String q,
                         @Param("activeOnly") boolean activeOnly,
                         @Param("includeArchived") boolean includeArchived);

    // SELECT ... FOR UPDATE. qty_after on a stock movement is a running balance, so two
    // concurrent sales that read the quantity before either writes would both compute the same
    // balance and the ledger would stop reconciling. The lock is what serialises them.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select p from Product p
            where p.branchId = :#{@branchContext.currentBranchId}
              and p.id = :id
            """)
    Optional<Product> findByIdForUpdate(@Param("id") UUID id);

    // At or below the branch threshold, which sweeps up anything negative too.
    @Query("""
            select p from Product p
            where p.branchId = :#{@branchContext.currentBranchId}
              and p.archivedAt is null
              and p.qtyOnHand <= :threshold
            order by p.qtyOnHand, p.name
            """)
    List<Product> findLowStock(@Param("threshold") BigDecimal threshold);

    // Mirrors product_name_key: unique on (branch_id, lower(name)) where not archived.
    @Query("""
            select count(p) > 0 from Product p
            where p.branchId = :#{@branchContext.currentBranchId}
              and lower(p.name) = lower(:name)
              and p.archivedAt is null
            """)
    boolean existsByName(@Param("name") String name);

    // Checks if a product with the same name exists but ignores the current product by ID
    @Query("""
            select count(p) > 0 from Product p
            where p.branchId = :#{@branchContext.currentBranchId}
              and lower(p.name) = lower(:name)
              and p.archivedAt is null
              and p.id <> :id
            """)
    boolean existsByNameAndIdNot(@Param("name") String name, @Param("id") UUID id);
}
