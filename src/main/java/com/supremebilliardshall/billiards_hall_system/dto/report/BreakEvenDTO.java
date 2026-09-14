package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/*
 * The gross the hall must take per trading day for the margin on it to cover the period's
 * operating cost: operatingExpenses / (grossProfit / gross) / tradingDays.
 *
 * Both figures are null and `computable` is false when the arithmetic is undefined -- no
 * sales, or a margin that is not positive -- so the page says "not enough sales to compute"
 * rather than the server dividing by zero.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BreakEvenDTO {

    private BigDecimal requiredGrossPerTradingDay;
    // The same figure as headline.grossPerTradingDay, beside the requirement for the sentence.
    private BigDecimal actualGrossPerTradingDay;
    private Boolean computable;
}
