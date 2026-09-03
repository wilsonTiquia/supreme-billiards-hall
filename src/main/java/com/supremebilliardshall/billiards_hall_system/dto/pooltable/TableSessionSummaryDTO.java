package com.supremebilliardshall.billiards_hall_system.dto.pooltable;

import com.supremebilliardshall.billiards_hall_system.entity.SessionStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

// What the floor view shows on an occupied table.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TableSessionSummaryDTO {

    private UUID sessionId;
    private UUID billId;
    // The table this session is on. Redundant on the floor view, where the card already says
    // so, but the end-of-day list and the bill view show these summaries out of that context —
    // and "go and close it" is useless if it cannot say which table to walk to.
    private String poolTableName;
    private SessionStatus status;
    private UUID customerTypeId;
    private String customerTypeName;
    private OffsetDateTime openedAt;
    // Elapsed less any pause, floored — the figure the customer will be charged for.
    private Integer billedMinutes;
    // The same elapsed, exact to the second and NOT floored. Nothing is billed on it: it
    // exists so the browser can animate a counter from an exact number instead of a rounded
    // one. Anchoring a local tick on the floored minute puts the display up to 59 seconds
    // behind, which shows up as the counter jumping backwards on every refresh.
    private Integer billedSeconds;
    private BigDecimal ratePerMinute;
    private BigDecimal timeAmount;
    // Units of product on the bill so far. The floor card leads with the money, and this is
    // what tells the counter whether that money is one round or six.
    private BigDecimal itemCount;
    private BigDecimal itemTotal;
    // Time charge plus non-voided items, added here rather than in the browser. "How much so
    // far?" is the question asked at every table; without a server figure the counter does the
    // sum in its head, which is the arithmetic this system exists to remove.
    private BigDecimal runningTotal;
}
