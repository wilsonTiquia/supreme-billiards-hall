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
    private Integer billedMinutes;
    // Against a 19-hour business day.
    private BigDecimal utilisationPercent;
}
