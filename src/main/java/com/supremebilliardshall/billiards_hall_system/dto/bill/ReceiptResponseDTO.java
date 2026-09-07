package com.supremebilliardshall.billiards_hall_system.dto.bill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

// The stored snapshot, returned as it was written. Never re-rendered.
//
// `settlement` is the one thing here that is NOT part of that snapshot: a bill left unpaid takes
// its receipt number and its frozen chit on the night, and the money can arrive weeks later.
// Rendering the payload alone would leave the screen reading "unpaid" for ever, which is the
// wrong answer to the question the reader is asking it.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReceiptResponseDTO {

    private UUID id;
    private UUID billId;
    private Long receiptNo;
    private OffsetDateTime issuedAt;
    private Map<String, Object> payload;
    // Only ever set on a debt that was collected later. Derived from payment at read time; the
    // payload above is never touched.
    private ReceiptSettlementDTO settlement;
}
