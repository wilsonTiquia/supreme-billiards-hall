package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.PoolTable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PoolTableRepository extends BranchScopedRepository<PoolTable> {

    @Query("""
            select t from PoolTable t
            where t.branchId = :#{@branchContext.currentBranchId}
              and t.archivedAt is null
            order by t.tableNumber, t.name
            """)
    List<PoolTable> findAllActive();

    // Native, because table_session has no entity until Day 2 and this rule is needed now:
    // a table cannot be archived while it is occupied.
    @Query(value = """
            select count(*) from table_session
            where pool_table_id = :poolTableId
              and status in ('OPEN', 'PAUSED')
            """, nativeQuery = true)
    long countOpenSessions(@Param("poolTableId") UUID poolTableId);

    // Mirrors pool_table_name_key: unique on (branch_id, lower(name)) where not archived.
    @Query("""
            select count(t) > 0 from PoolTable t
            where t.branchId = :#{@branchContext.currentBranchId}
              and lower(t.name) = lower(:name)
              and t.archivedAt is null
            """)
    boolean existsByName(@Param("name") String name);

    // Checks if a table with the same name exists but ignores the current table by ID
    @Query("""
            select count(t) > 0 from PoolTable t
            where t.branchId = :#{@branchContext.currentBranchId}
              and lower(t.name) = lower(:name)
              and t.archivedAt is null
              and t.id <> :id
            """)
    boolean existsByNameAndIdNot(@Param("name") String name, @Param("id") UUID id);
}
