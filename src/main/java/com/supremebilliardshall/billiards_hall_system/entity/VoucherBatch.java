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

// One run of giveaway codes: fifty two-hour vouchers expiring 31 October. The batch groups them
// so the owner can see what is still in the wild; it is not what a code is worth. Every voucher
// snapshots the minutes and the expiry off this row at generation and never reads them back.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "voucher_batch")
public class VoucherBatch implements BranchScoped {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    // The owner types hours; this is what is stored. The conversion happens once, on the way in.
    @Column(name = "minutes", nullable = false)
    private Integer minutes;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "expires_on", nullable = false)
    private LocalDate expiresOn;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;
}
