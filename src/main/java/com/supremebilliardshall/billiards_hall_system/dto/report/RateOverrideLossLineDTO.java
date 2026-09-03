package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// One friend rate. There is no supervisor approval and no floor on the rate, so the owner
// reading this afterwards IS the control.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RateOverrideLossLineDTO {

    private String poolTableName;
    private BigDecimal standardRatePerMinute;
    private BigDecimal chargedRatePerMinute;
    private Integer billedMinutes;
    // (standard - charged) x billed minutes. Exact, not an estimate.
    private BigDecimal forgoneRevenue;
    private String actorUsername;
    private String reason;
    private OffsetDateTime openedAt;
}
