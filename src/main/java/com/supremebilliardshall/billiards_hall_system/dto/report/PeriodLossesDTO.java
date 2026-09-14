package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// The dashboard's eight giveaway lines summed over the period -- the same LossesDTO fields,
// from the same CTEs with the date widened -- with their total and the total as a share of
// gross. The comps estimate is inside the total; the other seven are exact.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PeriodLossesDTO {

    private Integer voidCount;
    private BigDecimal voidAmount;
    private Integer promoSessions;
    private BigDecimal promoForgone;
    private Integer friendSessions;
    private BigDecimal friendForgone;
    private Integer flatSessions;
    private BigDecimal flatForgone;
    private Integer reducedSessions;
    private BigDecimal timeReductionForgone;
    private Integer discountBills;
    private BigDecimal discountAmount;
    private Integer voucherCount;
    private BigDecimal voucherAmount;
    private BigDecimal compQuantity;
    private BigDecimal compEstimatedCost;

    private BigDecimal total;
    // Null when gross is zero.
    private BigDecimal percentOfGross;
}
