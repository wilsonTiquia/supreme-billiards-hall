package com.supremebilliardshall.billiards_hall_system.repository;

import com.supremebilliardshall.billiards_hall_system.entity.Category;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CategoryRepository extends BranchScopedRepository<Category> {

    @Query("""
            select c from Category c
            where c.branchId = :#{@branchContext.currentBranchId}
              and c.archivedAt is null
            order by c.sortOrder, c.name
            """)
    List<Category> findAllActive();

    // Mirrors product_category_name_key: unique on (branch_id, lower(name)) where not archived.
    @Query("""
            select count(c) > 0 from Category c
            where c.branchId = :#{@branchContext.currentBranchId}
              and lower(c.name) = lower(:name)
              and c.archivedAt is null
            """)
    boolean existsByName(@Param("name") String name);

    // Checks if a category with the same name exists but ignores the current category by ID
    @Query("""
            select count(c) > 0 from Category c
            where c.branchId = :#{@branchContext.currentBranchId}
              and lower(c.name) = lower(:name)
              and c.archivedAt is null
              and c.id <> :id
            """)
    boolean existsByNameAndIdNot(@Param("name") String name, @Param("id") UUID id);
}
