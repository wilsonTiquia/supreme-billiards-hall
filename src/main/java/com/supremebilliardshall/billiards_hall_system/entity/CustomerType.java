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

// Admin-managed, e.g. Regular / Friend of Owner. Only the table rate is overridable;
// food and drink always charge full price.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "customer_type")
public class CustomerType implements BranchScoped {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "name", nullable = false, columnDefinition = "text")
    private String name;

    @Column(name = "allows_rate_override", nullable = false)
    private Boolean allowsRateOverride;

    @Column(name = "is_default", nullable = false)
    private Boolean isDefault;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private OffsetDateTime updatedAt;
}
