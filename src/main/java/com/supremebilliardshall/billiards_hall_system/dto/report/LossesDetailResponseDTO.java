package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// What is behind the dashboard's three loss figures.
//
// Each section repeats its own total using the SAME field name the tile uses, computed from the
// same rows the list below it shows. If a section total and its tile ever disagree, that is a
// bug and the screen is meant to make it obvious rather than quietly average it away.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LossesDetailResponseDTO {

    private LocalDate businessDate;
    private CompSection comps;
    private VoidSection voids;
    // The same shape twice, because they are the same kind of giveaway told apart by its kind.
    // Both keep the scoped field names an override section has always had: inside `promos`,
    // "forgoneRevenue" can only mean the promos' own. It is the tile figures on LossesDTO that
    // had to say which they meant, having nothing to scope them.
    private RateOverrideSection promos;
    private RateOverrideSection friendRates;
    private TimeReductionSection timeReductions;
    private FlatRateSection flatRates;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompSection {
        private BigDecimal compQuantity;
        private BigDecimal compEstimatedCost;
        private List<CompLossLineDTO> lines;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VoidSection {
        private Integer voidCount;
        private BigDecimal voidAmount;
        private List<VoidLossLineDTO> lines;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeReductionSection {
        private Integer reducedSessions;
        private BigDecimal forgoneRevenue;
        private List<TimeReductionLossLineDTO> lines;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RateOverrideSection {
        private Integer overrideSessions;
        private BigDecimal forgoneRevenue;
        private List<RateOverrideLossLineDTO> lines;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FlatRateSection {
        private Integer flatSessions;
        private BigDecimal flatForgone;
        private List<FlatRateLossLineDTO> lines;
    }
}
