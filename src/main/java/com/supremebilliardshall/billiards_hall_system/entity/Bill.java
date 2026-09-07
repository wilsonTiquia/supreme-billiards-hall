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

    // When this sale was recorded as a debt, and by whom. Written in the same statement as
    // closed_at, which is what dates the sale; these two say why it closed without a payment.
    @Column(name = "unsettled_at")
    private OffsetDateTime unsettledAt;

    @Column(name = "unsettled_by")
    private UUID unsettledBy;

    // When the debt was collected. Never closed_at: business_date is generated from that, so
    // settling five weeks later would move the original night's revenue onto the wrong report.
    @Column(name = "settled_at")
    private OffsetDateTime settledAt;

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

    /*
     * Pesos taken off the whole bill, food and drink included. Distinct from a session's
     * billed_minutes_override, which only ever reaches table time; a bill can carry both.
     *
     * A FIXED amount rather than a rate. Adding a line after the discount raises the total and
     * leaves this exactly where it was put -- the round number agreed at the counter does not
     * quietly stop being round because somebody ordered another beer.
     *
     * Defaulted in the FIELD rather than by each caller. The column is NOT NULL with a database
     * default, but Hibernate writes every mapped column on insert, so an unset field goes down
     * as an explicit null and the insert fails. Every bill starts undiscounted, so the zero
     * belongs here rather than in each of the places that build one.
     */
    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "discount_reason", columnDefinition = "text")
    private String discountReason;

    @Column(name = "discount_by")
    private UUID discountBy;

    @Column(name = "discount_at")
    private OffsetDateTime discountAt;

    // Optimistic lock. Hibernate owns this column; never set it by hand.
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    /*
     * Generated from COALESCE(closed_at, opened_at). The database computes it.
     *
     * Read back on UPDATE as well as INSERT. Without the UPDATE half, stamping closed_at leaves
     * this field holding the opened_at-derived date that came back at insert, while the row in
     * the database says something else. The divergence is narrow -- it needs a bill that opened
     * before 05:00 and closed after it, so the two dates differ -- which is exactly why it went
     * unnoticed on the checkout path for as long as it did. The cost is one extra SELECT after
     * each bill update, which at a hall's volume is nothing.
     */
    @Generated(event = { EventType.INSERT, EventType.UPDATE })
    @Column(name = "business_date", insertable = false, updatable = false)
    private LocalDate businessDate;
}
