package com.supremebilliardshall.billiards_hall_system.dto.pooltable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
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

    // A table without a rate cannot host a session, so a rate is still required to add one —
    // that guarantee moved from @NotNull on this field to the isRateGiven check below, because
    // either this or ratePerHour satisfies it. On update, a changed value opens a new rate
    // period exactly as PUT /rate does.
    @DecimalMin(value = "0.0001", message = "Rate per minute must be greater than zero")
    private BigDecimal ratePerMinute;

    // The same input convenience PUT /rate offers, so an edit that only renames a table
    // configured hourly can send back the hourly figure rather than silently reverting it.
    @DecimalMin(value = "0.01", message = "Rate per hour must be greater than zero")
    private BigDecimal ratePerHour;

    private Boolean isActive;

    // Mirrors PoolTableRateRequestDTO: two checks, so the message names which mistake was made.
    @JsonIgnore
    @AssertTrue(message = "A rate is required: give either a rate per minute or a rate per hour")
    public boolean isRateGiven() {
        return ratePerMinute != null || ratePerHour != null;
    }

    @JsonIgnore
    @AssertTrue(message = "Give a rate per minute or a rate per hour, not both")
    public boolean isOnlyOneRateGiven() {
        return ratePerMinute == null || ratePerHour == null;
    }
}
