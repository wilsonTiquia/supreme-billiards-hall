package com.supremebilliardshall.billiards_hall_system.dto.businessday;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// Correcting a mistyped drawer count. ADMIN only, and never once the day is closed: a cashier
// who can re-count until the variance reads zero has defeated the only check on the drawer.
// The original figure is not destroyed — it survives in the audit row the correction writes.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CashCountUpdateRequestDTO {

    @NotNull(message = "Counted cash is required")
    @DecimalMin(value = "0.00", message = "Counted cash cannot be negative")
    private BigDecimal countedCash;

    // The float is correctable for the same reason the count is: "we ran 500 last night, not
    // 1,000" is exactly as likely a mistake as a mistyped total, and leaving it out would mean
    // the only way to fix it is to not fix it. Null leaves the recorded float alone.
    @DecimalMin(value = "0.00", message = "The float cannot be negative")
    private BigDecimal openingFloat;

    // Why the original figure was wrong. Recorded on the audit row.
    private String note;
}
