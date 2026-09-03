package com.supremebilliardshall.billiards_hall_system.dto.bill;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class BillLineAdminResponseDTO extends BillLineResponseDTO {

    private BigDecimal unitCost;
    private BigDecimal lineCost;
}
