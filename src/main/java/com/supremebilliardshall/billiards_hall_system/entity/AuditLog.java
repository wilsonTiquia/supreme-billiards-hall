package com.supremebilliardshall.billiards_hall_system.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

// Append-only: a trigger rejects UPDATE and DELETE. Never cascade to this entity and
// never load it for modification.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "audit_log")
public class AuditLog implements BranchScoped {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "action", nullable = false, updatable = false, columnDefinition = "text")
    private String action;

    @Column(name = "entity_table", nullable = false, updatable = false, columnDefinition = "text")
    private String entityTable;

    @Column(name = "entity_id", updatable = false)
    private UUID entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before", updatable = false)
    private Map<String, Object> before;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after", updatable = false)
    private Map<String, Object> after;

    @Column(name = "note", updatable = false, columnDefinition = "text")
    private String note;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime occurredAt;

    // Generated column: business_date_of(occurred_at). The database computes it; writing it fails.
    @Column(name = "business_date", insertable = false, updatable = false)
    private LocalDate businessDate;
}
