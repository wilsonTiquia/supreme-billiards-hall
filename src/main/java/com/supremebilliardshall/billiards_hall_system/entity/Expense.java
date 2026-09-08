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

// One thing the hall paid for. Operating cost, never cost of goods: the two are added
// together nowhere, or the rent would end up inside the margin on a beer.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "expense")
public class Expense implements BranchScoped {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "expense_category_id", nullable = false)
    private UUID expenseCategoryId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    // Whether the cash physically left the till. Only these rows move the drawer arithmetic;
    // rent paid by transfer is operating cost all the same.
    @Column(name = "paid_from_drawer", nullable = false)
    private boolean paidFromDrawer;

    @Column(name = "incurred_at", nullable = false, updatable = false)
    private OffsetDateTime incurredAt;

    // Generated column: business_date_of(incurred_at). Read back after insert, because the
    // response says which night the expense landed on and only the database knows.
    @Generated(event = EventType.INSERT)
    @Column(name = "business_date", insertable = false, updatable = false)
    private LocalDate businessDate;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private UUID recordedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    // Retained, never deleted, and excluded from every total — the same rule bill_line
    // carries. Set together with voidedBy and a reason; the database enforces all three.
    @Column(name = "voided_at")
    private OffsetDateTime voidedAt;

    @Column(name = "voided_by")
    private UUID voidedBy;

    @Column(name = "void_reason", columnDefinition = "text")
    private String voidReason;
}
