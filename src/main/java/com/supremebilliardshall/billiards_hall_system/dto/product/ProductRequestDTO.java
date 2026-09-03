package com.supremebilliardshall.billiards_hall_system.dto.product;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

// avg_cost and qty_on_hand are deliberately absent: stock moves only through the
// stock_movement ledger, never through a product edit.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductRequestDTO {

    @NotBlank(message = "Product name is required")
    @Size(max = 100, message = "Product name must be at most 100 characters")
    private String name;

    private UUID categoryId;

    @NotNull(message = "Selling price is required")
    @DecimalMin(value = "0.00", message = "Selling price must not be negative")
    private BigDecimal sellingPrice;

    private Boolean isActive;
}
