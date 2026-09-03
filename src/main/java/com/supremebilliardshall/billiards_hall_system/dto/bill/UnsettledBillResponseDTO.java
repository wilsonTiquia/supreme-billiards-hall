package com.supremebilliardshall.billiards_hall_system.dto.bill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// A bill left unpaid with nothing running on it. Once its last session closes the table reads
// free again, so without this list the bill is unreachable from the floor and the sale is
// simply lost.
//
// Carries no cost or profit field, so one type serves both roles: there is nothing here an
// employee must not see.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UnsettledBillResponseDTO {
    private UUID id;
    private OffsetDateTime openedAt;
    // When the last session on this bill closed — how long it has been sitting unpaid.
    private OffsetDateTime sessionEndedAt;
    private LocalDate businessDate;
    private String customerTypeName;
    private List<String> tableNames;
    // Computed from the live lines. bill.total_amount is only finalised at checkout, so on an
    // open bill it still reads 0.00 and must not be shown.
    private BigDecimal totalAmount;
}
