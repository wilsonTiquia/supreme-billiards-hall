package com.supremebilliardshall.billiards_hall_system.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

// A line on the payable. description, unit_price and unit_cost are SNAPSHOTS: they are why a
// sale from three months ago still reports the correct profit after a re-price.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "bill_line")
public class BillLine implements BranchScoped {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "bill_id", nullable = false, updatable = false)
    private UUID billId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "line_kind", nullable = false, updatable = false, columnDefinition = "bill_line_kind")
    private BillLineKind lineKind;

    @Column(name = "seq", nullable = false)
    private Integer seq;

    // Reference for reporting only. Never joined to compute money.
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "unit_cost", nullable = false, precision = 12, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    // Time lines only.
    @Column(name = "billed_minutes")
    private Integer billedMinutes;

    // Generated columns: the database computes them, and @Generated makes Hibernate read the
    // result back on insert rather than leaving null in the entity for the rest of the transaction.
    @Generated(event = EventType.INSERT)
    @Column(name = "line_total", insertable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    @Generated(event = EventType.INSERT)
    @Column(name = "line_cost", insertable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal lineCost;

    @Column(name = "voided_at")
    private OffsetDateTime voidedAt;

    @Column(name = "voided_by")
    private UUID voidedBy;

    @Column(name = "void_reason", columnDefinition = "text")
    private String voidReason;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;
}
