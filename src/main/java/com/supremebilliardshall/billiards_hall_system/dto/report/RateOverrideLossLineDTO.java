package com.supremebilliardshall.billiards_hall_system.dto.report;

import com.supremebilliardshall.billiards_hall_system.entity.RateOverrideKind;
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
    // The same pair as typed, when the friend rate was entered hourly. Null otherwise. Display
    // only — forgoneRevenue is computed from the per-minute pair above.
    private BigDecimal standardRatePerHour;
    private BigDecimal chargedRatePerHour;
    private Integer billedMinutes;
    // (standard - charged) x billed minutes. Exact, not an estimate.
    private BigDecimal forgoneRevenue;
    private String actorUsername;
    private String reason;
    private OffsetDateTime openedAt;
    // Which section this row belongs in. On the row rather than implied by which list it
    // arrived in, so a line is still readable on its own.
    private RateOverrideKind rateOverrideKind;
}
