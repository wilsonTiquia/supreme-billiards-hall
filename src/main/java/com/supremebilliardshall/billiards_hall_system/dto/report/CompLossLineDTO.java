package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// One give-away. The reason is the column that matters: without it the tile is a number the
// owner cannot act on, which is the whole point of this screen.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompLossLineDTO {

    private String productName;
    private BigDecimal quantity;
    private String reason;
    private String actorUsername;
    private OffsetDateTime occurredAt;
    // At the product's CURRENT average cost, so an estimate — same basis as the tile.
    private BigDecimal estimatedCost;
}
