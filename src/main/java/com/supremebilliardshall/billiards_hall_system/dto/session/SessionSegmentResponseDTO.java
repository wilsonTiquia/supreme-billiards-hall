package com.supremebilliardshall.billiards_hall_system.dto.session;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionSegmentResponseDTO {

    private UUID id;
    private UUID poolTableId;
    private String poolTableName;
    private Integer seq;
    private BigDecimal ratePerMinute;
    private OffsetDateTime startedAt;
    private OffsetDateTime endedAt;
    // Net of any pause overlapping this segment, floored to whole minutes.
    private Integer billedMinutes;
    private BigDecimal amount;
}
