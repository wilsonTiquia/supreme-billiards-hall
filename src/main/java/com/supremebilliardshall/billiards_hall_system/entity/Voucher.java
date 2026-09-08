package com.supremebilliardshall.billiards_hall_system.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * One giveaway code, worth up to `minutes` of standard-rate table time on one bill.
 *
 * The redemption fields are read here but WRITTEN BY A CONDITIONAL UPDATE in the repository,
 * never by setting them on a loaded entity. Two tills racing the same code is exactly the case
 * a load-check-save loses, and the setters are left in place only because releasing reads the
 * row back afterwards. See VoucherRepository.redeem.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "voucher")
public class Voucher implements BranchScoped {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    // Normalised: uppercase, prefixed, no separators. VoucherCodes owns both halves, so what is
    // generated and what a typed code is normalised to cannot drift apart.
    @Column(name = "code", nullable = false, updatable = false, columnDefinition = "text")
    private String code;

    // Snapshots off the batch, taken once at generation. An owner editing the batch next month
    // must not change what a code printed last month is worth.
    @Column(name = "minutes", nullable = false, updatable = false)
    private Integer minutes;

    @Column(name = "expires_on", nullable = false, updatable = false)
    private LocalDate expiresOn;

    @Column(name = "redeemed_at")
    private OffsetDateTime redeemedAt;

    @Column(name = "redeemed_by")
    private UUID redeemedBy;

    @Column(name = "redeemed_bill_id")
    private UUID redeemedBillId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;
}
