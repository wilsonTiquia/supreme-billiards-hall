package com.supremebilliardshall.billiards_hall_system.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

// Unbilled intervals, deducted from billed minutes. Logged with the actor so pausing cannot
// be used quietly to comp a friend.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "session_pause")
public class SessionPause implements BranchScoped {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "paused_at", nullable = false)
    private OffsetDateTime pausedAt;

    @Column(name = "resumed_at")
    private OffsetDateTime resumedAt;

    @Column(name = "paused_by", nullable = false, updatable = false)
    private UUID pausedBy;

    @Column(name = "resumed_by")
    private UUID resumedBy;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;
}
