package com.supremebilliardshall.billiards_hall_system.dto.audit;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogResponseDTO {

    private UUID id;
    private String action;
    private String entityTable;
    private UUID entityId;
    private UUID actorId;
    private String actorUsername;
    private Map<String, Object> before;
    private Map<String, Object> after;
    private String note;
    private OffsetDateTime occurredAt;
    private LocalDate businessDate;
}
