package com.supremebilliardshall.billiards_hall_system.dto.bill;

import com.supremebilliardshall.billiards_hall_system.entity.PaymentMethod;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

// One settled sale, as the owner browses a night's takings.
//
// No cost and no profit, even though this route is ADMIN-only: the list is for finding a
// receipt, and the margin on a single sale belongs on the dashboard, not beside a receipt
// number the owner may be reading out to a customer.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BillSummaryResponseDTO {

    private UUID id;
    private Long receiptNo;
    private OffsetDateTime closedAt;
    private BigDecimal totalAmount;
    private PaymentMethod method;
    // Who took the payment.
    private String takenByUsername;
    // A table sale carried a session; a quick sale never did.
    private boolean quickSale;
}
