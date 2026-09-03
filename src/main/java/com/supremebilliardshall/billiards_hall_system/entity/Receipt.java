package com.supremebilliardshall.billiards_hall_system.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

// Stored rather than regenerated, so what the customer was shown can be reproduced exactly
// even after the catalog or the rendering code changes. Append-only by trigger.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "receipt")
public class Receipt implements BranchScoped {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "bill_id", nullable = false, updatable = false)
    private UUID billId;

    @Column(name = "receipt_no", nullable = false, updatable = false)
    private Long receiptNo;

    @Column(name = "issued_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime issuedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private Map<String, Object> payload;
}
