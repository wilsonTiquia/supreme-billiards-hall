package com.supremebilliardshall.billiards_hall_system.dto.branch;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BranchResponseDTO {
    private UUID id;
    private String code;
    private String name;
    private String address;
    private Boolean isActive;
    private OffsetDateTime createdAt;
}
