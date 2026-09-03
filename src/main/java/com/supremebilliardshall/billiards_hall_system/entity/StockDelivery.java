package com.supremebilliardshall.billiards_hall_system.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

// Header for one received shipment. The quantities are stock_movement rows referencing it.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "stock_delivery")
public class StockDelivery implements BranchScoped {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "supplier_name", columnDefinition = "text")
    private String supplierName;

    @Column(name = "reference", columnDefinition = "text")
    private String reference;

    @Column(name = "received_by", nullable = false, updatable = false)
    private UUID receivedBy;

    @Column(name = "received_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime receivedAt;

    @Column(name = "note", columnDefinition = "text")
    private String note;
}
