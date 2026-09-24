package com.supremebilliardshall.billiards_hall_system.dto.setup;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SetupItemResponseDTO {
    private UUID id;
    private String name;
    private OffsetDateTime archivedAt;
    private boolean canDelete;
    private String deletionReason;
    private String blockedReason;
}
