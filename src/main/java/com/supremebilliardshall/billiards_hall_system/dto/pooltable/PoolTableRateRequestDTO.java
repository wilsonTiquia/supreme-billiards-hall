package com.supremebilliardshall.billiards_hall_system.dto.pooltable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PoolTableRateRequestDTO {

    // Exactly one of these two is given; see the checks below. Not @NotNull, because either
    // one satisfies the requirement that a rate be present.
    @DecimalMin(value = "0.0001", message = "Rate per minute must be greater than zero")
    private BigDecimal ratePerMinute;

    // What the owner actually thinks in. An input convenience: the service divides it by 60 to
    // get the rate that bills, and stores both. Money is never computed from this figure.
    @DecimalMin(value = "0.01", message = "Rate per hour must be greater than zero")
    private BigDecimal ratePerHour;

    // Defaults to now. The previous period is closed at this instant, never overwritten.
    private OffsetDateTime effectiveFrom;

    // Two checks rather than one, so the message names which mistake was made. Both run, so a
    // request giving neither gets the first and a request giving both gets the second.
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
