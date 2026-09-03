package com.supremebilliardshall.billiards_hall_system.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // NULL means a global admin. Employees are always branch-bound.
    @Column(name = "branch_id")
    private UUID branchId;

    @Column(name = "username", nullable = false, columnDefinition = "text")
    private String username;

    @Column(name = "password_hash", nullable = false, columnDefinition = "text")
    private String passwordHash;

    @Column(name = "full_name", nullable = false, columnDefinition = "text")
    private String fullName;

    // user_role is a native Postgres enum; EnumType.STRING alone would bind as varchar and fail.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "role", nullable = false, columnDefinition = "user_role")
    private UserRole role;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private OffsetDateTime updatedAt;
}
