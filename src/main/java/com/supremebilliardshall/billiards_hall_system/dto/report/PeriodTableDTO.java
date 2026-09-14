package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// One pool table over the period. Sorted weakest first by the service's SQL, so a premium
// table earning less per hour than a standard one is the first row the owner reads.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PeriodTableDTO {

    private String tableName;
    // Wall clock the table was held, pauses included -- the daily's definition, and the long
    // comment on its segment_minutes CTE says why. Not the charged figure.
    private Integer occupiedMinutes;
    // Against 19 hours for each of the period's TRADING days. Null when there were none.
    private BigDecimal utilisationPercent;
    // What the time on this table was charged: the sessions' TIME lines, apportioned across a
    // moved session's segments by minutes. Sums across tables to the period's time revenue.
    private BigDecimal timeRevenue;
    // timeRevenue over occupied hours. Null for a table nobody played.
    private BigDecimal revenuePerOccupiedHour;
}
