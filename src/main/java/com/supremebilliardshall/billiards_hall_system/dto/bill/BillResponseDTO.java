package com.supremebilliardshall.billiards_hall_system.dto.bill;

import com.supremebilliardshall.billiards_hall_system.dto.pooltable.TableSessionSummaryDTO;
import com.supremebilliardshall.billiards_hall_system.entity.BillStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// Totals here are computed from the live lines as they stand now. They are finalised onto the
// bill row at checkout; voided lines are present in the list and excluded from every total.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BillResponseDTO {

    private UUID id;
    private BillStatus status;
    private UUID customerTypeId;
    private String customerTypeName;
    private OffsetDateTime openedAt;
    private OffsetDateTime closedAt;
    private LocalDate businessDate;
    private Integer version;

    private BigDecimal subtotalTime;
    private BigDecimal subtotalItems;
    private BigDecimal totalAmount;

    private List<? extends BillLineResponseDTO> lines;
    private List<TableSessionSummaryDTO> sessions;
}
