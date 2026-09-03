package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// Attributed to whoever took the payment. These rows sum to the branch totals.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeSalesDTO {

    private String username;
    private String fullName;
    private Integer bills;
    private BigDecimal gross;
    private BigDecimal cost;
    private BigDecimal profit;
}
