package com.supremebilliardshall.billiards_hall_system.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

// One occupancy of one table. The unique index table_session_one_open_per_table_key stops a
// second live session on the same table -- enforced by the database, not by a pre-check.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "table_session")
public class TableSession implements BranchScoped {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "bill_id", nullable = false, updatable = false)
    private UUID billId;

    @Column(name = "pool_table_id", nullable = false)
    private UUID poolTableId;

    @Column(name = "customer_type_id")
    private UUID customerTypeId;

    // columnDefinition names the Postgres enum type: without it Hibernate builds the SQL
    // literal cast from the Java class name and emits 'OPEN'::SessionStatus, which does not exist.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "session_status")
    private SessionStatus status;

    @Column(name = "opened_by", nullable = false, updatable = false)
    private UUID openedBy;

    @Column(name = "opened_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime openedAt;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "close_kind", columnDefinition = "session_close_kind")
    private SessionCloseKind closeKind;

    @Column(name = "needs_review", nullable = false)
    private Boolean needsReview;

    // The friend rate actually charged, and the rate it replaced. Both are kept so the
    // foregone revenue is computable rather than merely suspected.
    @Column(name = "rate_override_per_minute", precision = 10, scale = 4)
    private BigDecimal rateOverridePerMinute;

    @Column(name = "standard_rate_per_minute", precision = 10, scale = 4)
    private BigDecimal standardRatePerMinute;

    @Column(name = "rate_override_by")
    private UUID rateOverrideBy;

    // Reserved: the owner allows any friend rate with no approval step.
    @Column(name = "rate_override_approved_by")
    private UUID rateOverrideApprovedBy;

    @Column(name = "rate_override_reason", columnDefinition = "text")
    private String rateOverrideReason;

    // What was CHARGED, when the counter charged less time than was played. billedMinutes
    // above stays what actually happened; this never overwrites it.
    @Column(name = "billed_minutes_override")
    private Integer billedMinutesOverride;

    @Column(name = "billed_minutes_override_by")
    private UUID billedMinutesOverrideBy;

    @Column(name = "billed_minutes_override_reason", columnDefinition = "text")
    private String billedMinutesOverrideReason;

    // Finalised at close from segments minus pauses, so the receipt and the reports never
    // recompute and never disagree.
    @Column(name = "billed_minutes")
    private Integer billedMinutes;

    @Column(name = "time_amount", precision = 12, scale = 2)
    private BigDecimal timeAmount;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private OffsetDateTime updatedAt;
}
