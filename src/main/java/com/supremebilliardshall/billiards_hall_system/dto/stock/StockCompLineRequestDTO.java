package com.supremebilliardshall.billiards_hall_system.dto.stock;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

// One product on a give-away. No price: nothing is being charged.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockCompLineRequestDTO {

    @NotNull(message = "Product is required")
    private UUID productId;

    @NotNull(message = "Quantity is required")
    @DecimalMin(value = "0.001", message = "Quantity must be greater than zero")
    private BigDecimal quantity;
}
