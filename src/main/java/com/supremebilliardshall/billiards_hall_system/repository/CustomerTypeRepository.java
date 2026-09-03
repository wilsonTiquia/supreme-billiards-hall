package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.CustomerType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CustomerTypeRepository extends BranchScopedRepository<CustomerType> {

    @Query("""
            select t from CustomerType t
            where t.branchId = :#{@branchContext.currentBranchId}
              and t.archivedAt is null
            order by t.sortOrder, t.name
            """)
    List<CustomerType> findAllActive();

    // Mirrors customer_type_name_key: unique on (branch_id, lower(name)) where not archived.
    @Query("""
            select count(t) > 0 from CustomerType t
            where t.branchId = :#{@branchContext.currentBranchId}
              and lower(t.name) = lower(:name)
              and t.archivedAt is null
            """)
    boolean existsByName(@Param("name") String name);

    // Checks if a customer type with the same name exists but ignores the current one by ID
    @Query("""
            select count(t) > 0 from CustomerType t
            where t.branchId = :#{@branchContext.currentBranchId}
              and lower(t.name) = lower(:name)
              and t.archivedAt is null
              and t.id <> :id
            """)
    boolean existsByNameAndIdNot(@Param("name") String name, @Param("id") UUID id);

    // At most one default per branch, enforced by customer_type_one_default_key.
    @Query("""
            select t from CustomerType t
            where t.branchId = :#{@branchContext.currentBranchId}
              and t.isDefault = true
              and t.archivedAt is null
            """)
    List<CustomerType> findDefaults();
}
