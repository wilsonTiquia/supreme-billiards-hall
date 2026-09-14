package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseMonthRowDTO {

    private String category;
    // Positional against ExpenseMonthGridDTO.months, zeros filled.
    private List<BigDecimal> amounts;
    private BigDecimal total;
}
