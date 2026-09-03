package com.supremebilliardshall.billiards_hall_system.dto.customertype;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerTypeRequestDTO {

    @NotBlank(message = "Customer type name is required")
    @Size(max = 100, message = "Customer type name must be at most 100 characters")
    private String name;

    // When true the POS prompts for a friend rate at session start.
    private Boolean allowsRateOverride;

    private Boolean isDefault;

    private Integer sortOrder;
}
