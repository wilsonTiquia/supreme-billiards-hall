package com.supremebilliardshall.billiards_hall_system.dto.session;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
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

    // The same friend rate in the unit the owner thinks in. The service divides it by 60 to get
    // the rate that bills and stores both; money is never computed from this figure.
    @DecimalMin(value = "0.00", message = "Rate override must not be negative")
    private BigDecimal rateOverridePerHour;

    private String rateOverrideReason;

    // AT MOST one, not exactly one — unlike the table rate, where a rate is mandatory. Neither
    // is the ordinary case and means "charge the standard rate"; both is the client asking for
    // two different giveaways at once, which has no sensible reading.
    @JsonIgnore
    @AssertTrue(message = "Give a friend rate per minute or per hour, not both")
    public boolean isOnlyOneOverrideGiven() {
        return rateOverridePerMinute == null || rateOverridePerHour == null;
    }
}
