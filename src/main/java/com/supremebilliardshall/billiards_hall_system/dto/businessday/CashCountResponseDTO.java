package com.supremebilliardshall.billiards_hall_system.dto.businessday;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CashCountResponseDTO {

    private UUID id;
    private LocalDate businessDate;
    /*
     * The three figures are all sent, not just the total, because a variance nobody can check is
     * a number people learn to nod at. "Expected 1,050" invites the question "why 1,050"; float
     * 1,000 + takings 50 answers it on the same screen, months later, without a second query.
     */
    private BigDecimal openingFloat;
    private BigDecimal cashSales;
    // Cash paid out of the till tonight, frozen like the two above. Sent so the end-of-day
    // panel can say why the expected figure dropped, rather than leaving the counter to
    // discover that the drawer is 850 light and assume the worst.
    private BigDecimal cashExpenses;
    // openingFloat + cashSales - cashExpenses: what the drawer should have held.
    private BigDecimal expectedCash;
    private boolean floatOverridden;
    private BigDecimal countedCash;
    // counted - expected. Negative is a shortfall.
    private BigDecimal variance;
    private OffsetDateTime countedAt;
    private String note;
    // Null while the drawer is counted but the night is not yet closed. Once set, closing
    // again is a 409 — the screen reads these to say who finished the day rather than
    // offering a button that silently does nothing.
    private OffsetDateTime closedAt;
    private String closedByUsername;

    /*
     * Trading recorded on this business day AFTER it was closed.
     *
     * The night runs to 05:00, so a sale at 03:30 lands on a day signed off at 03:00. When
     * that happens expectedCash — frozen at the moment of counting — no longer describes the
     * drawer, and a variance of 0.00 stops meaning "balanced" and starts meaning "balanced
     * against a total that has since moved". These fields exist so no screen can show that
     * figure without also showing what has changed underneath it.
     */
    private int salesAfterClose;
    private BigDecimal amountAfterClose;
    // Only the cash half moves the drawer, so it is the figure a re-count has to reconcile to.
    private BigDecimal cashAfterClose;

    /*
     * And money that left the till after the close, for exactly the same reason.
     *
     * Adding expenses to the variance formula created a second way for the frozen figure to
     * stop being true, and unlike a late sale it would have been invisible: a sale after the
     * close is already surfaced here, a payout after it would not have been.
     */
    private int expensesAfterClose;
    private BigDecimal cashExpensesAfterClose;

    public boolean isStale() {
        return closedAt != null && (salesAfterClose > 0 || expensesAfterClose > 0);
    }
}
