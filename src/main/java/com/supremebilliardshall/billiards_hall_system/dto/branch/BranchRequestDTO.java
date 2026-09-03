package com.supremebilliardshall.billiards_hall_system.dto.branch;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class BranchRequestDTO {

    // The schema is unique on code, not on name.
    @NotBlank(message = "Branch code is required")
    @Size(max = 20, message = "Branch code must be at most 20 characters")
    private String code;

    @NotBlank(message = "Branch name is required")
    @Size(max = 100, message = "Branch name must be at most 100 characters")
    private String name;

    @NotBlank(message = "Branch address is required")
    @Size(max = 255, message = "Branch address must be at most 255 characters")
    private String address;

    private Boolean isActive;
}
