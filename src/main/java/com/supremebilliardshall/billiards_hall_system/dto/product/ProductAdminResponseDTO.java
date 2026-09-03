package com.supremebilliardshall.billiards_hall_system.dto.product;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// What an ADMIN receives: the employee view plus cost. The service picks the type by role,
// so the extra field exists only on instances an admin asked for.
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class ProductAdminResponseDTO extends ProductResponseDTO {

    private BigDecimal avgCost;
}
