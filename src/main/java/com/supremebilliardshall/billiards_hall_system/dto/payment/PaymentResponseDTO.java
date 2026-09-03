package com.supremebilliardshall.billiards_hall_system.dto.payment;

import com.supremebilliardshall.billiards_hall_system.entity.PaymentMethod;
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
public class PaymentResponseDTO {

    private UUID id;
    private UUID billId;
    private PaymentMethod method;
    private BigDecimal amount;
    private BigDecimal tendered;
    private BigDecimal changeGiven;
    private String referenceNo;
    private Long receiptNo;
    private OffsetDateTime takenAt;
    private LocalDate businessDate;
    private boolean duplicateReferenceOverridden;
    // True when this response is a replay of an earlier request carrying the same
    // idempotency key. The money moved once.
    private boolean replayed;
}
