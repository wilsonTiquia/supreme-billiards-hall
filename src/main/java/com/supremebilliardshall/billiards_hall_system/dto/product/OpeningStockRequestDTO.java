package com.supremebilliardshall.billiards_hall_system.dto.product;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpeningStockRequestDTO {

    @NotNull(message = "Opening quantity is required with unit cost")
    @DecimalMin(value = "0.001", message = "Opening quantity must be greater than zero")
    @Digits(integer = 9, fraction = 3)
    private BigDecimal quantity;

    @NotNull(message = "Opening unit cost is required with quantity")
    @DecimalMin(value = "0", message = "Opening unit cost must not be negative")
    @Digits(integer = 8, fraction = 4)
    private BigDecimal unitCost;
}
