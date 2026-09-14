package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// One expense category across both windows. A category paid in only one of the two still
// appears, with a zero on the other side, so a bill that stopped or started is visible.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PeriodExpenseCategoryDTO {

    private String category;
    private BigDecimal amount;
    private BigDecimal previousAmount;
    // amount as a share of the period's gross, one place. Null when gross is zero.
    private BigDecimal percentOfGross;
}
