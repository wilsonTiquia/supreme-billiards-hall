package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// The "should we open on Tuesdays" row. Averaged over the TRADING days of that weekday in the
// period, and null rather than zero when there were none: a weekday that never traded and a
// weekday that averaged nothing are different answers.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DayOfWeekAverageDTO {

    // ISO: 1 is Monday, 7 is Sunday. The week starts Monday.
    private Integer isoDay;
    private Integer tradingDays;
    private BigDecimal avgGross;
    private BigDecimal avgBills;
    private BigDecimal avgNet;
}
