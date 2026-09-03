package com.supremebilliardshall.billiards_hall_system.dto.auth;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SelectBranchRequestDTO {

    @NotNull(message = "Branch id is required")
    private UUID branchId;
}
