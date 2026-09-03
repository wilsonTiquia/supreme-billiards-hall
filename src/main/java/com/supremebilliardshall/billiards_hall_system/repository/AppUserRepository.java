package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

// Not branch-scoped: a global admin has no branch, and login happens before a branch is known.
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByUsernameIgnoreCaseAndArchivedAtIsNull(String username);
}
