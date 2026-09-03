package com.supremebilliardshall.billiards_hall_system.dto.pooltable;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PoolTableRateRequestDTO {

    @NotNull(message = "Rate per minute is required")
    @DecimalMin(value = "0.0001", message = "Rate per minute must be greater than zero")
    private BigDecimal ratePerMinute;

    // Defaults to now. The previous period is closed at this instant, never overwritten.
    private OffsetDateTime effectiveFrom;
}
