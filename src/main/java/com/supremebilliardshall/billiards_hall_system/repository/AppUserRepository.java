package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.AppUser;
import com.supremebilliardshall.billiards_hall_system.entity.UserRole;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Not branch-scoped: a global admin has no branch, and login happens before a branch is known.
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByUsernameIgnoreCaseAndArchivedAtIsNull(String username);

    List<AppUser> findByBranchIdAndArchivedAtIsNullOrderByUsername(UUID branchId);

    /*
     * The Staff screen: this branch's people, plus every global admin.
     *
     * The second half is deliberate and is not a leak. app_user_branch_required_for_employee
     * says an EMPLOYEE must belong to a branch and an ADMIN need not, so a global admin is
     * staff of every branch rather than of none — listing them on each branch's page is the
     * truth about who can sign in and act there. A future reader with three branches will see
     * the same two admins on all three pages; that is the answer, not an oversight.
     *
     * Without this the screen could not manage the accounts it exists to manage: the seeded
     * owner is global, so a branch-only list showed no admin at all, and Edit, Archive and the
     * last-admin warning were dead for exactly those rows.
     */
    @Query("""
            select u from AppUser u
             where u.archivedAt is null
               and (u.branchId = :branchId or (u.branchId is null and u.role = 'ADMIN'))
             order by u.username
            """)
    List<AppUser> findBranchStaffAndGlobalAdmins(@Param("branchId") UUID branchId);

    /*
     * Every admin who can still sign in, locked for update.
     *
     * The last-admin guard is a read-then-write, and without this two admins archiving each
     * other in the same instant would both see two admins and both proceed, leaving none --
     * no admin, no route to DELETE /users/lockouts, and break-glass as the only way back. A
     * guard that holds only when nobody races it is not a guard.
     *
     * Locking the admin ROWS rather than counting under an advisory lock keeps the mechanism
     * the same one the rest of the codebase uses (BillRepository, BranchRepository). The
     * second transaction blocks, then re-reads and sees the set as the first one left it.
     *
     * LOCK ORDER: this is taken alone. No path that touches app_user also locks a bill or a
     * branch row, so there is no ordering to observe today -- if one is ever added, this lock
     * comes first, because it is the outermost thing a user-administration request does.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select u from AppUser u
             where u.role = :role
               and u.archivedAt is null
               and u.isActive = true
            """)
    List<AppUser> findActiveAdminsForUpdate(@Param("role") UserRole role);
}
