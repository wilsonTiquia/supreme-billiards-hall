package com.supremebilliardshall.billiards_hall_system.dto.bill;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

// No price: the server snapshots selling_price and avg_cost itself.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddBillLineRequestDTO {

    @NotNull(message = "Product is required")
    private UUID productId;

    @NotNull(message = "Quantity is required")
    @DecimalMin(value = "0.001", message = "Quantity must be greater than zero")
    private BigDecimal quantity;
}
