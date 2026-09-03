package com.supremebilliardshall.billiards_hall_system.dto.stock;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class StockMovementAdminResponseDTO extends StockMovementResponseDTO {

    private BigDecimal unitCost;
}
