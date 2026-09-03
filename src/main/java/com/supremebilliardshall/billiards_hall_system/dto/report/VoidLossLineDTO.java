package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

// One voided line, retained on its bill and excluded from every total.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoidLossLineDTO {

    private String description;
    private BigDecimal quantity;
    private BigDecimal lineTotal;
    private String reason;
    private String actorUsername;
    private OffsetDateTime voidedAt;
    private UUID billId;
    // Null while the bill is still open — a void does not wait for the bill to be settled.
    private Long receiptNo;
}
