package com.supremebilliardshall.billiards_hall_system.dto.customertype;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerTypeResponseDTO {

    private UUID id;
    private String name;
    private Boolean allowsRateOverride;
    private Boolean isDefault;
    private Integer sortOrder;
}
