package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// A product with stock on hand that did not sell once in the period. Cut-the-SKU candidates,
// most capital first.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UnsoldProductDTO {

    private String name;
    private BigDecimal qtyOnHand;
    private BigDecimal avgCost;
    // qtyOnHand x avgCost, at the product's CURRENT average cost.
    private BigDecimal capitalOnShelf;
}
