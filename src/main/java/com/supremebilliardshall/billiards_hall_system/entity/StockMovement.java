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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

// The inventory ledger, and the authority on stock. Append-only: a trigger rejects UPDATE and
// DELETE, so a mistake is corrected by posting a compensating row, never by editing history.
// Never map a cascade that could reach this table.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "stock_movement")
public class StockMovement implements BranchScoped {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "reason", nullable = false, updatable = false)
    private StockReason reason;

    // Negative for outflow, positive for inflow. Never zero.
    @Column(name = "quantity_delta", nullable = false, updatable = false, precision = 12, scale = 3)
    private BigDecimal quantityDelta;

    // Balance immediately after this movement, so a single row can be audited without
    // replaying the ledger from the beginning. Computed under a row lock on the product.
    @Column(name = "qty_after", nullable = false, updatable = false, precision = 12, scale = 3)
    private BigDecimal qtyAfter;

    // Deliveries only: what this stock cost, feeding the moving average.
    @Column(name = "unit_cost", updatable = false, precision = 12, scale = 4)
    private BigDecimal unitCost;

    // Cause. Exactly one of these is set, matching the reason.
    @Column(name = "bill_line_id", updatable = false)
    private UUID billLineId;

    @Column(name = "delivery_id", updatable = false)
    private UUID deliveryId;

    @Column(name = "note", updatable = false, columnDefinition = "text")
    private String note;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime occurredAt;

    // Generated column: business_date_of(occurred_at), read back on insert.
    @Generated(event = EventType.INSERT)
    @Column(name = "business_date", insertable = false, updatable = false)
    private LocalDate businessDate;
}
