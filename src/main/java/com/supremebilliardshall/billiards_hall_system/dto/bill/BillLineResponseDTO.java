package com.supremebilliardshall.billiards_hall_system.dto.bill;

import com.supremebilliardshall.billiards_hall_system.entity.BillLineKind;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

// What an EMPLOYEE receives: no unit_cost, no line_cost. description, unitPrice and quantity
// are the snapshot taken at the moment of sale, never re-read from the product.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BillLineResponseDTO {

    private UUID id;
    private BillLineKind lineKind;
    private Integer seq;
    private UUID productId;
    private UUID sessionId;
    private String description;
    private BigDecimal unitPrice;
    private BigDecimal quantity;
    private Integer billedMinutes;
    private BigDecimal lineTotal;
    private OffsetDateTime voidedAt;
    private String voidReason;
}
