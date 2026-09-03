package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyTotalsDTO {

    private Integer bills;
    private BigDecimal gross;
    private BigDecimal cost;
    private BigDecimal profit;
    // The table-time versus product split of the gross.
    private BigDecimal timeRevenue;
    private BigDecimal itemRevenue;
}
