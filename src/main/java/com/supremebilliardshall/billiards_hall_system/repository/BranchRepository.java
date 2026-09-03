package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.Branch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Not branch-scoped: this IS the branch table.
public interface BranchRepository extends JpaRepository<Branch, UUID> {
    // to find a branch by name
    List<Branch> findByNameContainingIgnoreCase(String name);

    // The schema is unique on code, not on name.
    boolean existsByCode(String code);

    // Checks if a branch with the same code exists but ignores the current branch by ID
    boolean existsByCodeAndIdNot(String code, UUID id);

    // Drives the global admin's branch resolution: exactly one means no ambiguity.
    List<Branch> findByIsActiveTrue();

    // SELECT ... FOR UPDATE for allocating next_receipt_no inside the checkout transaction.
    // Same class of race as the product row lock: two simultaneous checkouts must not take
    // the same human-facing receipt number.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Branch b where b.id = :id")
    Optional<Branch> findByIdForUpdate(@Param("id") UUID id);

    // business_date is derived by the database, never computed in Java.
    @Query(value = "select business_date_of(now())", nativeQuery = true)
    java.time.LocalDate currentBusinessDate();
}
