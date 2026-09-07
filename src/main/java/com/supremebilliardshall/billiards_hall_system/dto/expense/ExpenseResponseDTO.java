package com.supremebilliardshall.billiards_hall_system.dto.expense;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * One type for both roles, like UnsettledBillResponseDTO.
 *
 * An operating expense is not product cost and not profit: there is no unit_cost here, no
 * margin, nothing that reveals what the hall pays for what it sells. The counter needs to read
 * back what they just typed in order to spot a mistyped figure, and a screen that records money
 * but cannot show it is one people stop trusting.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseResponseDTO {

    private UUID id;
    private UUID expenseCategoryId;
    private String categoryName;
    private BigDecimal amount;
    private String note;
    private boolean paidFromDrawer;
    private OffsetDateTime incurredAt;
    // Which night it landed on. Computed by the database from incurred_at, so an expense
    // recorded at 02:00 belongs to the night still running.
    private LocalDate businessDate;
    private String recordedByUsername;

    // Voided rows are returned with the live ones and flagged, never filtered out: they stay
    // on screen struck through so a mistake reads as a mistake rather than disappearing.
    private OffsetDateTime voidedAt;
    private String voidedByUsername;
    private String voidReason;

    public boolean isVoided() {
        return voidedAt != null;
    }
}
