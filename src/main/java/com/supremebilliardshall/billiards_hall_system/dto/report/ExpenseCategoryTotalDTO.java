package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// Grouped on the category name rather than its id: an archived category still has to report
// what was spent under it, and the owner reads a name.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseCategoryTotalDTO {

    private String category;
    private BigDecimal amount;
}
