package com.supremebilliardshall.billiards_hall_system.dto.bill;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/*
 * Knocking money off the whole bill.
 *
 * The counter types WHAT THEY ARE CHARGING, not what they are taking off, because that is the
 * number the conversation at the counter actually produced -- "make it 600" -- and it is the
 * number the customer will hand over. The server does the subtraction, which is the same rule
 * that keeps every other peso figure off the client.
 *
 * The reason is not optional. Any role may do this and nobody has to approve it, so the note
 * and the actor are the entire control -- exactly as they are on BilledMinutesOverrideRequestDTO.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BillDiscountRequestDTO {

    // Above zero, not merely non-negative. A bill charged at 0.00 could never be settled --
    // payment requires at least 0.01 -- so it would sit on the floor for ever. The service
    // says so in words, and points at the route that does exist.
    @NotNull(message = "The amount to charge is required")
    @DecimalMin(value = "0.01", message = "The amount to charge must be greater than zero")
    private BigDecimal chargeAmount;

    @NotBlank(message = "A reason is required to discount a bill")
    private String reason;
}
