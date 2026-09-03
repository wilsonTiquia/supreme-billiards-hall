package com.supremebilliardshall.billiards_hall_system.dto.pooltable;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PoolTableRequestDTO {

    @NotBlank(message = "Table name is required")
    @Size(max = 100, message = "Table name must be at most 100 characters")
    private String name;

    private Integer tableNumber;

    // A table without a rate cannot host a session, so a rate is required to add one.
    // On update, a changed value opens a new rate period exactly as PUT /rate does.
    @NotNull(message = "Rate per minute is required")
    @DecimalMin(value = "0.0001", message = "Rate per minute must be greater than zero")
    private BigDecimal ratePerMinute;

    private Boolean isActive;
}
