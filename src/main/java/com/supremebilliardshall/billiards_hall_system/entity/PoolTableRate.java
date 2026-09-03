package com.supremebilliardshall.billiards_hall_system.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

// Dated rate history. A rate change closes the current row and opens a new one; the
// exclusion constraint pool_table_rate_no_overlap rejects any overlap at the database.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "pool_table_rate")
public class PoolTableRate implements BranchScoped {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "pool_table_id", nullable = false, updatable = false)
    private UUID poolTableId;

    // numeric(10,4): a per-minute rate needs sub-centavo precision.
    @Column(name = "rate_per_minute", nullable = false, precision = 10, scale = 4)
    private BigDecimal ratePerMinute;

    @Column(name = "effective_from", nullable = false)
    private OffsetDateTime effectiveFrom;

    @Column(name = "effective_to")
    private OffsetDateTime effectiveTo;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;
}
