package com.supremebilliardshall.billiards_hall_system.dto.quicksale;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

// Priced the way the sale will price it, and carrying no cost — the counter reads this.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuickSaleQuoteLineDTO {

    private UUID productId;
    private String description;
    private BigDecimal unitPrice;
    private BigDecimal quantity;
    private BigDecimal lineTotal;
}
