package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// One tournament table. Same shape and the same purpose as RateOverrideLossLineDTO: there is no
// approval step and no floor on the fee, so the owner reading this afterwards IS the control.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlatRateLossLineDTO {

    private String poolTableName;
    private Integer billedMinutes;
    private BigDecimal standardRatePerMinute;
    // What the meter would have charged for those minutes, stated beside what was actually
    // charged so the giveaway reads without the reader doing the multiplication.
    private BigDecimal meteredRevenue;
    private BigDecimal flatAmount;
    // metered less flat, CLAMPED AT ZERO on this row. A flat fee above the metered figure is not
    // a loss and must not net off against a real one, so the clamp is per row and not on the sum.
    private BigDecimal forgoneRevenue;
    private String actorUsername;
    private String reason;
    private OffsetDateTime openedAt;
}
