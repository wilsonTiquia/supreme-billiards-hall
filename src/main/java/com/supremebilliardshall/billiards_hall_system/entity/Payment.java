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

// One payment per bill, enforced by payment_one_per_bill_key. The idempotency key is what
// stops a double-clicked submit taking the money twice.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "payment")
public class Payment implements BranchScoped {

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
    @Column(name = "method", nullable = false, updatable = false)
    private PaymentMethod method;

    @Column(name = "amount", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    // Cash only. change_given is computed by the server, never sent by the client.
    @Column(name = "tendered", updatable = false, precision = 12, scale = 2)
    private BigDecimal tendered;

    @Column(name = "change_given", updatable = false, precision = 12, scale = 2)
    private BigDecimal changeGiven;

    // Digital only.
    @Column(name = "reference_no", updatable = false, columnDefinition = "text")
    private String referenceNo;

    // Set when staff confirmed a duplicate-reference warning and proceeded anyway.
    @Column(name = "duplicate_override_by", updatable = false)
    private UUID duplicateOverrideBy;

    // Path plus checksum, so a backup that loses the volume is detectable rather than
    // silently empty.
    @Column(name = "photo_path", columnDefinition = "text")
    private String photoPath;

    @Column(name = "photo_sha256", columnDefinition = "text")
    private String photoSha256;

    @Column(name = "photo_bytes")
    private Long photoBytes;

    @Column(name = "idempotency_key", nullable = false, updatable = false, columnDefinition = "text")
    private String idempotencyKey;

    @Column(name = "taken_by", nullable = false, updatable = false)
    private UUID takenBy;

    @Column(name = "taken_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime takenAt;

    // Generated column: business_date_of(taken_at).
    @Generated(event = EventType.INSERT)
    @Column(name = "business_date", insertable = false, updatable = false)
    private LocalDate businessDate;
}
