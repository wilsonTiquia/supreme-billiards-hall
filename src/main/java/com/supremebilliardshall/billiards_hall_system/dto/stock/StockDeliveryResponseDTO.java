package com.supremebilliardshall.billiards_hall_system.dto.stock;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockDeliveryResponseDTO {

    private UUID id;
    private String supplierName;
    private String reference;
    private String note;
    private OffsetDateTime receivedAt;
    private List<StockMovementResponseDTO> movements;
}
