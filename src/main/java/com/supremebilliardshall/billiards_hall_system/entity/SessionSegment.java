package com.supremebilliardshall.billiards_hall_system.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

// The billing truth for time. One row per table the session occupied; a session with no
// transfers has exactly one. rate_per_minute is a snapshot, never a lookup.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "session_segment")
public class SessionSegment implements BranchScoped {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "pool_table_id", nullable = false, updatable = false)
    private UUID poolTableId;

    @Column(name = "seq", nullable = false)
    private Integer seq;

    // The rate this segment is billed at: the friend rate when one was set, otherwise the
    // table's standard rate at the moment the segment opened.
    @Column(name = "rate_per_minute", nullable = false, precision = 10, scale = 4)
    private BigDecimal ratePerMinute;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "moved_by")
    private UUID movedBy;

    @Column(name = "move_reason", columnDefinition = "text")
    private String moveReason;
}
