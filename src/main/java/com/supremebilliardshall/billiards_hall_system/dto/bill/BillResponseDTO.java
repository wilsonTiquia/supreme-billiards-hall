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

    /*
     * On the base type and not on BillAdminResponseDTO, deliberately. A discount is not a cost
     * or a profit figure -- it is what the customer is being charged -- and the counter who
     * typed it has to be able to read it back off the screen they typed it on.
     *
     * Zero, never null, so the client can always subtract. The other three are null together
     * when nothing was given away.
     */
    private BigDecimal discountAmount;
    private String discountReason;
    private String discountByUsername;
    private OffsetDateTime discountAt;

    /*
     * The voucher, on the base type for the same reason the discount is: it is what the
     * customer is being charged, and the counter has to read it back.
     *
     * `voucherCode` is the DISPLAY form, SB-7K4-M2Q. That is safe to return here where the
     * whole code list is not -- this one has already been spent, on this bill, in front of the
     * person reading it, and the receipt has to name it anyway.
     */
    private BigDecimal voucherAmount;
    private String voucherCode;
    private Integer voucherMinutes;
    private Integer voucherMinutesCovered;

    // subtotalTime + subtotalItems - discountAmount - voucherAmount. What is actually being
    // charged. Both reductions are fixed amounts; adding a line raises this and leaves them.
    private BigDecimal totalAmount;

    private List<? extends BillLineResponseDTO> lines;
    private List<TableSessionSummaryDTO> sessions;
}
