package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.BranchSetting;
import com.supremebilliardshall.billiards_hall_system.entity.BranchSettingId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

// Not a BranchScopedRepository: that base is keyed on a single uuid, and branch_setting is
// keyed on (branch_id, key). The branch predicate is therefore written out here.
public interface BranchSettingRepository extends JpaRepository<BranchSetting, BranchSettingId> {

    // '#>> {}' extracts a jsonb value as text whatever its type, so a numeric setting and a
    // string setting read the same way.
    @Query(value = """
            select value #>> '{}' from branch_setting
            where branch_id = :#{@branchContext.currentBranchId}
              and key = :key
            """, nativeQuery = true)
    Optional<String> findValueByKey(@Param("key") String key);
}
