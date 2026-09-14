package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

// One night of the trend line. Present for every calendar night in the period, zeros on a
// night the hall did not trade, so a closed Tuesday shows as a gap rather than disappearing.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PeriodDayDTO {

    private LocalDate businessDate;
    private Boolean trading;
    private Integer bills;
    private BigDecimal gross;
    private BigDecimal costOfGoods;
    private BigDecimal grossProfit;
    private BigDecimal operatingExpenses;
    private BigDecimal net;
}
