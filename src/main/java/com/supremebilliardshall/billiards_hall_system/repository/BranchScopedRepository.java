package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.BranchScoped;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Base repository for every table that carries branch_id. The inherited CRUD reads are
// overridden here so the branch predicate is opt-out rather than opt-in: a repository that
// extends this cannot accidentally read another branch's rows through findAll or findById.
// Derived queries declared in subinterfaces are NOT covered by these overrides, so they
// repeat the same @branchContext predicate explicitly.
@NoRepositoryBean
public interface BranchScopedRepository<T extends BranchScoped> extends JpaRepository<T, UUID> {

    @Override
    @Query("select e from #{#entityName} e where e.branchId = :#{@branchContext.currentBranchId}")
    List<T> findAll();

    @Override
    @Query("select e from #{#entityName} e where e.id = :id and e.branchId = :#{@branchContext.currentBranchId}")
    Optional<T> findById(@Param("id") UUID id);

    @Override
    @Query("select count(e) > 0 from #{#entityName} e where e.id = :id and e.branchId = :#{@branchContext.currentBranchId}")
    boolean existsById(@Param("id") UUID id);

    @Override
    @Query("select count(e) from #{#entityName} e where e.branchId = :#{@branchContext.currentBranchId}")
    long count();
}
