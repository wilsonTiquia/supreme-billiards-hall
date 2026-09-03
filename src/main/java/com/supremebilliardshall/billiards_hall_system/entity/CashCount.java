package com.supremebilliardshall.billiards_hall_system.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

// The one control that survives from the hall's paper process: count the drawer, compare it
// to what the system says should be there. One row per branch per business day.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "cash_count")
public class CashCount implements BranchScoped {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    // SUM(payment.amount) for CASH on this business date, frozen at the moment of counting
    // and always computed by the server. Takings only — the drawer also holds the float.
    @Column(name = "cash_sales", nullable = false, precision = 12, scale = 2)
    private BigDecimal cashSales;

    // The change float as applied to THIS night, copied from the branch standard rather than
    // joined to it: the standard can change next month, and a night reconciled in March has to
    // still add up in March's terms.
    @Column(name = "opening_float", nullable = false, precision = 12, scale = 2)
    private BigDecimal openingFloat;

    // True when this night ran something other than the branch standard. The why is on the
    // audit row; this exists so "which nights were unusual" is answerable from the counts.
    @Column(name = "float_overridden", nullable = false)
    private boolean floatOverridden;

    @Column(name = "counted_cash", nullable = false, precision = 12, scale = 2)
    private BigDecimal countedCash;

    // Generated column: counted_cash - (cash_sales + opening_float). Negative is a shortfall.
    // Read back on UPDATE as well as INSERT: an admin correcting a mistyped count changes
    // counted_cash, and without this the variance in the response would be the old one.
    @Generated(event = { EventType.INSERT, EventType.UPDATE })
    @Column(name = "variance", insertable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal variance;

    @Column(name = "counted_by", nullable = false, updatable = false)
    private UUID countedBy;

    @Column(name = "counted_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime countedAt;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    // Set together, never separately — the database enforces that with a check constraint.
    // Null here means the drawer is counted but the night is not yet closed.
    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "closed_by")
    private UUID closedBy;
}
