package com.supremebilliardshall.billiards_hall_system.dto.session;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

// No start time and no duration: the server is the only clock.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpenSessionRequestDTO {

    @NotNull(message = "Table is required")
    private UUID tableId;

    @NotNull(message = "Customer type is required")
    private UUID customerTypeId;

    // The friend rate. Any value, no floor and no approval step, but only for a customer
    // type that allows it. Absent means bill at the table's standard rate.
    @DecimalMin(value = "0.0000", message = "Rate override must not be negative")
    private BigDecimal rateOverridePerMinute;

    private String rateOverrideReason;
}
