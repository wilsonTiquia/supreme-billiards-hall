package com.supremebilliardshall.billiards_hall_system.dto.quicksale;

import com.supremebilliardshall.billiards_hall_system.dto.bill.AddBillLineRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.payment.PaymentRequestDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

// A bill with no session, settled immediately. Same stock and payment rules as any other.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuickSaleRequestDTO {

    @NotNull(message = "Customer type is required")
    private UUID customerTypeId;

    @NotEmpty(message = "A quick sale must have at least one line")
    @Valid
    private List<AddBillLineRequestDTO> lines;

    // billVersion is ignored here: the bill is created and settled in the same transaction,
    // so there is no earlier version for the client to have read.
    @NotNull(message = "Payment is required")
    @Valid
    private PaymentRequestDTO payment;
}
