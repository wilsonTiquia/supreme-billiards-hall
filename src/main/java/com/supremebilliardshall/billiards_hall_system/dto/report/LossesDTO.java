package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// The ways money leaves without a sale, reported together because they are the same class of
// loss and only comparable side by side.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LossesDTO {

    private Integer voidCount;
    private BigDecimal voidAmount;

    private Integer overrideSessions;
    // (standard rate - friend rate) x billed minutes: revenue the hall chose not to take.
    private BigDecimal forgoneRevenue;

    private BigDecimal compQuantity;
    // Valued at the product's CURRENT average cost. stock_movement carries no unit cost for a
    // comp, so this is an estimate, unlike the other two figures which are exact.
    private BigDecimal compEstimatedCost;

    // Table time played but not charged. The third giveaway route, alongside friend rates and
    // comps — the owner needs all three on one screen or the control is worthless.
    private Integer reducedSessions;
    private BigDecimal timeReductionForgone;

    // Tournament pricing. A flat fee below what the meter would have charged is a giveaway like
    // any other, and without this it would be invisible: the friend-rate figures above are
    // computed only over sessions carrying a per-minute override, which a flat session never
    // does. Each row is clamped at zero before summing, so a fee above the metered figure
    // contributes nothing rather than cancelling out a real loss elsewhere.
    private Integer flatSessions;
    private BigDecimal flatForgone;
}
