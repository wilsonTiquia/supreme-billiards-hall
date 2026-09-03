package com.supremebilliardshall.billiards_hall_system.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "product")
public class Product implements BranchScoped {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    // Plain column, not an association: the schema reaches product_category through a
    // composite FK on (branch_id, category_id), and mapping branch_id twice would make
    // Hibernate write it twice.
    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "name", nullable = false, columnDefinition = "text")
    private String name;

    // Current price list only. Historical sales read the snapshot on bill_line, never this.
    @Column(name = "selling_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal sellingPrice;

    // Moving weighted average, recalculated on each DELIVERY. Admin-only at the DTO level.
    @Column(name = "avg_cost", nullable = false, precision = 12, scale = 4)
    private BigDecimal avgCost;

    // Cache of SUM(stock_movement.quantity_delta). The ledger is authoritative.
    @Column(name = "qty_on_hand", nullable = false, precision = 12, scale = 3)
    private BigDecimal qtyOnHand;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    // The file itself lives on the app volume, not in this row. Path plus checksum, so a
    // backup that lost the volume is detectable rather than silently empty. All three are
    // null together — a product without a picture is the normal case.
    @Column(name = "image_path", columnDefinition = "text")
    private String imagePath;

    @Column(name = "image_sha256", columnDefinition = "text")
    private String imageSha256;

    @Column(name = "image_bytes")
    private Long imageBytes;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private OffsetDateTime updatedAt;
}
