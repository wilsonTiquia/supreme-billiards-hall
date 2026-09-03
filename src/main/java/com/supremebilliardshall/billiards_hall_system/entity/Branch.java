package com.supremebilliardshall.billiards_hall_system.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "branch")
public class Branch {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code", nullable = false, columnDefinition = "text")
    private String code;

    @Column(name = "name", nullable = false, columnDefinition = "text")
    private String name;

    @Column(name = "address", columnDefinition = "text")
    private String address;

    // Per-branch receipt counter, allocated under row lock at checkout. Never set from a request.
    @Column(name = "next_receipt_no", nullable = false)
    private Long nextReceiptNo;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    // The schema has no trigger for updated_at, so the application maintains it.
    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private OffsetDateTime updatedAt;
}
