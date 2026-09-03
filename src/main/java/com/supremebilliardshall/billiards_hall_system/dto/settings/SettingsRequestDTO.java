package com.supremebilliardshall.billiards_hall_system.dto.settings;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// The settings the owner actually changes from a screen. Deliberately not a generic key/value
// endpoint: branch_setting also holds keys the application reasons about (rate floors, negative
// stock) where a typo would change how the till behaves, and those are not a text box.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SettingsRequestDTO {

    @NotNull(message = "The standard float is required")
    @DecimalMin(value = "0.00", message = "The standard float must not be negative")
    private BigDecimal standardCashFloat;

    @NotNull(message = "The checkout animation setting is required")
    private Boolean checkoutAnimation;
}
