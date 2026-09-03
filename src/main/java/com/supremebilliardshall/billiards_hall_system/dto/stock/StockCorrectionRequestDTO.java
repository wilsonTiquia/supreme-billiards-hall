package com.supremebilliardshall.billiards_hall_system.dto.stock;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockCorrectionRequestDTO {

    @NotNull(message = "Product is required")
    private UUID productId;

    // The counted quantity. The ledger records the difference, not this number.
    @NotNull(message = "New quantity is required")
    private BigDecimal newQuantity;

    // A correction with no explanation is not traceable; the database rejects it too.
    @NotBlank(message = "Note is required for a stock correction")
    private String note;
}
