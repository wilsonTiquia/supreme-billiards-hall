package com.supremebilliardshall.billiards_hall_system.dto.session;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionPauseResponseDTO {

    private UUID id;
    private OffsetDateTime pausedAt;
    private OffsetDateTime resumedAt;
    private String reason;
}
