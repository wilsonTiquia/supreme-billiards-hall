package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TableUtilisationDTO {

    private String tableName;
    // Wall clock the table was held, pauses included -- occupancy, not the charged figure. A
    // paused table is still nobody else's, and the percentage below divides by 19 hours of wall
    // clock, so the numerator has to be the same kind of minute. See the segment_minutes CTE.
    private Integer occupiedMinutes;
    // Against a 19-hour business day.
    private BigDecimal utilisationPercent;
}
