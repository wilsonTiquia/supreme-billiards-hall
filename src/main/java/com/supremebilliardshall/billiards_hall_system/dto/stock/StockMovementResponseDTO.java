package com.supremebilliardshall.billiards_hall_system.dto.stock;

import com.supremebilliardshall.billiards_hall_system.entity.StockReason;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

// What an EMPLOYEE receives: no unit_cost. The admin view adds it.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockMovementResponseDTO {

    private UUID id;
    private UUID productId;
    private String productName;
    private StockReason reason;
    private BigDecimal quantityDelta;
    private BigDecimal qtyAfter;
    private UUID billLineId;
    private UUID deliveryId;
    private String note;
    private OffsetDateTime occurredAt;
    private LocalDate businessDate;
}
