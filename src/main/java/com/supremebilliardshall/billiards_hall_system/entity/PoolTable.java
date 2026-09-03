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

// Durable equipment, no session state. "Is table 3 busy?" is a query for an open
// table_session, not a column here.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "pool_table")
public class PoolTable implements BranchScoped {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "name", nullable = false, columnDefinition = "text")
    private String name;

    @Column(name = "table_number")
    private Integer tableNumber;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private OffsetDateTime updatedAt;
}
