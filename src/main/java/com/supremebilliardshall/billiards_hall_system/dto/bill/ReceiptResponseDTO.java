package com.supremebilliardshall.billiards_hall_system.dto.bill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

// The stored snapshot, returned as it was written. Never re-rendered.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReceiptResponseDTO {

    private UUID id;
    private UUID billId;
    private Long receiptNo;
    private OffsetDateTime issuedAt;
    private Map<String, Object> payload;
}
