package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// One product sold in the period, from the snapshotted line figures -- re-pricing it tomorrow
// changes nothing here. Sorted thinnest margin first.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PeriodProductDTO {

    private String name;
    private BigDecimal quantity;
    private BigDecimal revenue;
    private BigDecimal cost;
    private BigDecimal margin;
    // margin / revenue, one place. Null when revenue is zero.
    private BigDecimal marginPercent;
}
