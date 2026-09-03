package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Not branch-scoped: a global admin has no branch, and login happens before a branch is known.
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByUsernameIgnoreCaseAndArchivedAtIsNull(String username);

    // The staff list for one branch. Global admins (branch_id NULL) are not staff of any
    // branch and are deliberately absent — the reset endpoint refuses them anyway.
    List<AppUser> findByBranchIdAndArchivedAtIsNullOrderByUsername(UUID branchId);
}
