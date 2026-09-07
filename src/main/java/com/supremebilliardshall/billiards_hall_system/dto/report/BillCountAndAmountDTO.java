package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// A number of bills and what they come to. One shape for the three debt figures on the
// dashboard, because they differ only in which bills they count: what was left unpaid tonight,
// what was collected tonight against earlier nights, and what is still outstanding altogether.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BillCountAndAmountDTO {

    private Integer count;
    private BigDecimal amount;
}
