package com.supremebilliardshall.billiards_hall_system.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

// Per-branch runtime config so the owner can change the low-stock threshold without a deploy.
// Known keys: low_stock_threshold, allow_negative_stock, standard_cash_float,
// payment_photo_retention, payment_photo_visibility.
@Getter
@Setter
@NoArgsConstructor
@Entity
@IdClass(BranchSettingId.class)
@Table(name = "branch_setting")
public class BranchSetting {

    @Id
    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Id
    @Column(name = "key", nullable = false, updatable = false, columnDefinition = "text")
    private String key;

    // jsonb, so a setting can grow structure later. Values are scalars today.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value", nullable = false)
    private String value;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private OffsetDateTime updatedAt;
}
