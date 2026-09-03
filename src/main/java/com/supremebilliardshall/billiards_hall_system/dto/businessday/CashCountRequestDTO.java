package com.supremebilliardshall.billiards_hall_system.dto.businessday;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// The cash takings are deliberately absent: the server computes them from the cash payments of
// that business day. Accepting them from the client would make the variance meaningless.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CashCountRequestDTO {

    @NotNull(message = "Counted cash is required")
    @DecimalMin(value = "0.00", message = "Counted cash must not be negative")
    private BigDecimal countedCash;

    // Null means "the branch standard", which is the answer on almost every night. Sending it
    // explicitly is how a night that ran a different float says so; the server compares it to
    // the standard and records the difference rather than trusting a flag from the client.
    @DecimalMin(value = "0.00", message = "The float must not be negative")
    private BigDecimal openingFloat;

    private String note;
}
