package com.supremebilliardshall.billiards_hall_system.dto.stock;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

// Quantities only, no cost, so the counter can act on it.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LowStockResponseDTO {

    private UUID productId;
    private String name;
    private BigDecimal qtyOnHand;
    private BigDecimal threshold;
}
