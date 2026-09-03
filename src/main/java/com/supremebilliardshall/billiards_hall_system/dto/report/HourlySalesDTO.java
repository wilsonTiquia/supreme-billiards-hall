package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// Hour of the Asia/Manila clock, across the 10:00 to 05:00 business day.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HourlySalesDTO {

    private Integer hour;
    private Integer bills;
    private BigDecimal amount;
}
