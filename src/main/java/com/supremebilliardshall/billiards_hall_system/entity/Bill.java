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

// The payable. Created when a session opens, in the same transaction, so orders taken
// mid-session have somewhere to live before checkout.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "bill")
public class Bill implements BranchScoped {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    // Allocated from branch.next_receipt_no under row lock at checkout. Not Day 2's business.
    @Column(name = "receipt_no")
    private Long receiptNo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "bill_status")
    private BillStatus status;

    @Column(name = "customer_type_id")
    private UUID customerTypeId;

    @Column(name = "merged_into_bill_id")
    private UUID mergedIntoBillId;

    @Column(name = "opened_by", nullable = false, updatable = false)
    private UUID openedBy;

    @Column(name = "opened_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime openedAt;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "voided_by")
    private UUID voidedBy;

    @Column(name = "voided_at")
    private OffsetDateTime voidedAt;

    @Column(name = "void_reason", columnDefinition = "text")
    private String voidReason;

    @Column(name = "subtotal_time", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotalTime;

    @Column(name = "subtotal_items", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotalItems;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "total_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalCost;

    // Optimistic lock. Hibernate owns this column; never set it by hand.
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    // Generated from COALESCE(closed_at, opened_at). The database computes it.
    @Generated(event = EventType.INSERT)
    @Column(name = "business_date", insertable = false, updatable = false)
    private LocalDate businessDate;
}
