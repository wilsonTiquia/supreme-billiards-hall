package com.supremebilliardshall.billiards_hall_system.dto.bill;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class BillAdminResponseDTO extends BillResponseDTO {

    private BigDecimal totalCost;
    private BigDecimal grossProfit;
}
