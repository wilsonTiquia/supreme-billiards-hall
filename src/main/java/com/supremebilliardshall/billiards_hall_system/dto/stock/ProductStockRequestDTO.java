package com.supremebilliardshall.billiards_hall_system.dto.stock;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductStockRequestDTO {
    @NotNull(message = "Product is required")
    private UUID productId;

    @NotNull(message = "Quantity is required")
    @DecimalMin(value = "0.001", message = "Quantity must be greater than zero")
    @Digits(integer = 9, fraction = 3)
    private BigDecimal quantity;

    // A stale-screen guard, never the source of the ledger's unit cost.
    @NotNull(message = "Review the default purchase cost before adding stock")
    @DecimalMin("0")
    @Digits(integer = 8, fraction = 4)
    private BigDecimal expectedDefaultPurchaseCost;
}
