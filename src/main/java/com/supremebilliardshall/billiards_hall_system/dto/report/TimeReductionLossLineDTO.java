package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// One session charged for less time than it ran. The third way stock or time leaves without
// being paid for, and it belongs beside the other two.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TimeReductionLossLineDTO {

    private String poolTableName;
    private Integer actualMinutes;
    private Integer chargedMinutes;
    private BigDecimal ratePerMinute;
    // (actual - charged) x the rate that was billed. Exact, not an estimate.
    private BigDecimal forgoneRevenue;
    private String actualUsername;
    private String reason;
    private OffsetDateTime closedAt;
}
