package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// The headline for one window of the period report. The same shape for the period and the
// previous period, so the page can put a delta beside every figure.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PeriodTotalsDTO {

    private Integer bills;
    private BigDecimal gross;
    // Cost of goods, snapshotted on the lines at the moment of sale. Never the rent.
    private BigDecimal costOfGoods;
    // gross - costOfGoods. What the dashboard calls profit.
    private BigDecimal grossProfit;
    // Operating cost for the window, dated by the expense's own business_date, voided excluded.
    private BigDecimal operatingExpenses;
    // grossProfit - operatingExpenses. The figure the owner is actually asking for.
    private BigDecimal net;
    // Business dates with at least one sale or a cash_count row. The denominator of every
    // per-day figure below.
    private Integer tradingDays;
    // Null, not zero, when there were no trading days: nothing to divide by is not "nothing".
    private BigDecimal grossPerTradingDay;
    private BigDecimal netPerTradingDay;
    // (gross - costOfGoods) / gross, as a percentage to one place. Null when gross is zero.
    private BigDecimal grossMarginPercent;
}
