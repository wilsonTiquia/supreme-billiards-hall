package com.supremebilliardshall.billiards_hall_system.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

// The owner's own vocabulary for what the hall spends on. Shaped exactly like Category
// because it is the same kind of thing — an admin-managed lookup the owner extends at
// runtime — but kept separate: "Rent" has no business appearing in the POS product filter.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "expense_category")
public class ExpenseCategory implements BranchScoped {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "name", nullable = false, columnDefinition = "text")
    private String name;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    // Soft delete. expense_category_name_key is partial on archived_at IS NULL, so archiving a
    // category frees its name for reuse and leaves every expense that referenced it intact.
    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private OffsetDateTime updatedAt;
}
