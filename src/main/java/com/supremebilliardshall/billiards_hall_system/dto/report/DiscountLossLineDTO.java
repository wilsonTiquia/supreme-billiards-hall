package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

// One bill the counter knocked money off. The subtraction is shown in full — what it came to,
// what came off, what was charged — because that is the sentence the owner is checking, and
// making him do the arithmetic is how a wrong figure goes unnoticed.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiscountLossLineDTO {

    private UUID billId;
    // Always present, unlike a void's: a discount can only be taken on an OPEN bill, and every
    // bill on this report has been through checkout and carries a number.
    private Long receiptNo;
    private BigDecimal subtotal;
    private BigDecimal discountAmount;
    private BigDecimal chargedAmount;
    private String reason;
    private String actorUsername;
    private OffsetDateTime discountAt;
}
