package com.supremebilliardshall.billiards_hall_system.dto.voucher;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

// Fifty two-hour vouchers expiring 31 October, with a note saying what the giveaway was.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoucherBatchRequestDTO {

    /*
     * HOURS, because that is what the prize was advertised as -- "win 2 hours of free table
     * time". The server multiplies by 60 and stores minutes; nothing downstream ever sees this
     * figure again.
     *
     * Decimal, so half an hour is expressible, but the service refuses anything that is not a
     * whole number of minutes rather than rounding it: 0.7 hours is 42 minutes and 0.71 is
     * 42.6, and a voucher worth two thirds of a minute more than the owner intended is a figure
     * nobody can explain later.
     */
    @NotNull(message = "Hours are required")
    @DecimalMin(value = "0.01", message = "Hours must be more than zero")
    @Digits(integer = 2, fraction = 2, message = "Hours must be a number of hours")
    private BigDecimal hours;

    // Matches voucher_batch_quantity_chk. The ceiling is on one batch, not on the hall's
    // generosity -- the codes are generated and returned in one transaction to be printed.
    @NotNull(message = "A quantity is required")
    @Min(value = 1, message = "A batch must have at least one voucher")
    @Max(value = 500, message = "A batch can hold at most 500 vouchers")
    private Integer quantity;

    // Required, and in the future: a code that expires today would be worthless by the time it
    // was handed out, and one with no expiry is a liability with no end date on it.
    @NotNull(message = "An expiry date is required")
    @Future(message = "The expiry date must be in the future")
    private LocalDate expiresOn;

    // What the giveaway was, which is the only thing that tells one batch from another on the
    // owner's screen and in the losses drill-down.
    @Size(max = 280, message = "Note must be at most 280 characters")
    private String note;
}
