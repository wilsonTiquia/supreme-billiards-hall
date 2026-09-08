package com.supremebilliardshall.billiards_hall_system.dto.bill;

import com.supremebilliardshall.billiards_hall_system.entity.PaymentMethod;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// How a debt was eventually collected, read back from the payment row.
//
// Kept OUTSIDE the receipt payload rather than merged into it, and that separation is the whole
// point. The payload is the frozen document handed over on the night: it is append-only in the
// database, it said "unpaid" because the bill was unpaid, and annotating it later would make the
// record claim something that was not true when it was issued. This is the answer to a different
// question — "does he still owe this?" — which is the one the next member of staff to open the
// screen is actually asking.
//
// Null on a receipt for a bill that was paid at the counter: there the payload already carries
// the method, and there was never a gap between the sale and the money.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReceiptSettlementDTO {
    private PaymentMethod method;
    private BigDecimal amount;
    private OffsetDateTime takenAt;
    private String takenByUsername;
}
