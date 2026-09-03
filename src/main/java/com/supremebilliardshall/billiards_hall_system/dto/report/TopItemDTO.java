package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// Grouped on the snapshot description, so a renamed product still reports as what was sold.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TopItemDTO {

    private String description;
    private BigDecimal quantity;
    private BigDecimal revenue;
}
