package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// How well the drawer was kept over the period, and what is still owed.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CashDisciplineDTO {

    // Sum of cash_count.variance over the period's counted nights. Negative is short.
    private BigDecimal varianceTotal;
    private Integer nightsWithVariance;
    private Integer countedNights;
    // Trading days in the period with no cash_count row.
    private Integer uncountedTradingDays;
    private UnsettledAgingDTO unsettled;
}
