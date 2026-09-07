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

    /*
     * The two kinds of rate override, reported apart.
     *
     * Both are (standard rate - charged rate) x billed minutes -- the same arithmetic, and
     * deliberately so, because a promo prices through the same mechanism a friend rate does.
     * What differs is what they mean: a promo is a decision about the night and a friend rate
     * is a decision about one person, and an owner reading one combined figure cannot tell a
     * heavy month of promotion from generosity running away.
     *
     * These two were one field called forgoneRevenue until promos existed. Renamed rather than
     * kept with the promo half quietly removed: "override" at the top level of a report reads
     * as ALL overrides, and a figure that means "some of them" is the lie this change exists
     * to remove.
     */
    private Integer promoSessions;
    private BigDecimal promoForgone;

    private Integer friendSessions;
    private BigDecimal friendForgone;

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
