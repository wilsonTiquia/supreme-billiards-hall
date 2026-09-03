package com.supremebilliardshall.billiards_hall_system.dto.bill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// Selling below zero stock is allowed with a warning, never blocked: a stale count must not
// stop a paying customer.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddBillLineResponseDTO {

    private BillLineResponseDTO line;
    private boolean belowZeroStock;
    private BigDecimal qtyOnHand;
    private String warning;
}
