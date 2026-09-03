package com.supremebilliardshall.billiards_hall_system.dto.payment;

import com.supremebilliardshall.billiards_hall_system.entity.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRequestDTO {

    @NotNull(message = "Payment method is required")
    private PaymentMethod method;

    // Must equal the bill total exactly. Cash overpayment is expressed as tendered.
    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    private BigDecimal amount;

    // Cash only. The server computes the change.
    private BigDecimal tendered;

    // Digital only.
    private String referenceNo;

    // Set true to proceed past a duplicate-reference warning; the actor is recorded.
    private Boolean duplicateOverride;

    // Client-generated per checkout attempt. A replay returns the original payment.
    @NotBlank(message = "Idempotency key is required")
    private String idempotencyKey;

    // The version the client read. A mismatch means another tab has moved the bill.
    @NotNull(message = "Bill version is required")
    private Integer billVersion;
}
