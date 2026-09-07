package com.supremebilliardshall.billiards_hall_system.dto.session;

import com.supremebilliardshall.billiards_hall_system.entity.SessionCloseKind;
import com.supremebilliardshall.billiards_hall_system.entity.SessionStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionResponseDTO {

    private UUID id;
    private UUID billId;
    private UUID poolTableId;
    private String poolTableName;
    private UUID customerTypeId;
    private String customerTypeName;
    private SessionStatus status;
    private OffsetDateTime openedAt;
    private OffsetDateTime closedAt;
    private SessionCloseKind closeKind;

    // The table's standard rate at open, kept alongside the friend rate so the foregone
    // revenue is arithmetic rather than guesswork.
    private BigDecimal standardRatePerMinute;
    private BigDecimal rateOverridePerMinute;
    // The same pair as typed, when either was entered hourly. Null otherwise — a per-minute
    // table and a per-minute friend rate carry no hourly figure, and inventing one would put a
    // number on the screen nobody entered. Display only; the money comes from the pair above.
    private BigDecimal standardRatePerHour;
    private BigDecimal rateOverridePerHour;

    // While the session is live these are recomputed server-side on every read; at close they
    // are the values stored on table_session. The client never sends either one.
    private Integer billedMinutes;
    // Exact billable elapsed in seconds, unfloored. For the client's counter only; the money
    // is always computed from billedMinutes.
    private Integer billedSeconds;
    private BigDecimal timeAmount;
    // Units of product on the bill so far, matching the floor summary.
    private BigDecimal itemCount;
    private BigDecimal itemTotal;
    // Time charge plus non-voided items. The bill row carries no total until checkout writes
    // the TIME lines, so this is the only trustworthy "so far" figure while a table is live.
    private BigDecimal runningTotal;

    private List<SessionSegmentResponseDTO> segments;
    private List<SessionPauseResponseDTO> pauses;

    // The client renders its counter from this, and computes its own clock offset from it.
    private OffsetDateTime serverNow;
}
