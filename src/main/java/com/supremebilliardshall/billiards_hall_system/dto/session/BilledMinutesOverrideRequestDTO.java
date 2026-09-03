package com.supremebilliardshall.billiards_hall_system.dto.session;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Charging less table time than was played. The reason is not optional: with no supervisor
// role and no floor on the reduction, the note and the actor are the entire control.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BilledMinutesOverrideRequestDTO {

    @NotNull(message = "Minutes to charge is required")
    @Min(value = 0, message = "Minutes to charge must not be negative")
    private Integer billedMinutes;

    @NotBlank(message = "A reason is required to reduce the time charged")
    private String reason;
}
