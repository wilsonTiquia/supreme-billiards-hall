package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.PoolTableRate;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PoolTableRateRepository extends BranchScopedRepository<PoolTableRate> {

    // The rate in force now: the open-ended row.
    @Query("""
            select r from PoolTableRate r
            where r.branchId = :#{@branchContext.currentBranchId}
              and r.poolTableId = :poolTableId
              and r.effectiveTo is null
            """)
    Optional<PoolTableRate> findCurrentByPoolTableId(@Param("poolTableId") UUID poolTableId);

    // Current rate for every table in the branch, so the table list is one query, not N.
    @Query("""
            select r from PoolTableRate r
            where r.branchId = :#{@branchContext.currentBranchId}
              and r.effectiveTo is null
            """)
    List<PoolTableRate> findAllCurrent();
}
