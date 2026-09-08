package com.supremebilliardshall.billiards_hall_system.dto.bill;

import com.supremebilliardshall.billiards_hall_system.dto.session.SessionNoteResponseDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// A debt: a bill deliberately left unpaid, finalised and awaiting collection.
//
// Not the same thing as UnsettledBillResponseDTO, and the two must never be merged. That one
// is a MISTAKE — an OPEN bill whose session closed without anyone taking payment, which the
// floor strip exists to catch. This one is a DECISION, taken by a named member of staff with a
// name attached to it. Collapsing them would put "staff forgot" and "Jun pays next month" in
// one list and teach the counter to ignore both.
//
// No cost and no profit, so one type serves both roles.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UnpaidBillResponseDTO {
    private UUID id;
    // Allocated when the bill was left unpaid, from the same counter a paid checkout draws on.
    private Long receiptNo;
    // The night it was PLAYED, not the night it will be collected. Generated from closed_at,
    // which is stamped at leave-unpaid and never moves again.
    private LocalDate businessDate;
    private OffsetDateTime unsettledAt;
    private String unsettledByUsername;
    // Whole days since the sale, so the list can read "37 days" without the client deciding
    // what a day is when the business day runs to 05:00.
    private int daysOutstanding;
    private List<String> tableNames;
    // Frozen at leave-unpaid. Unlike the unsettled strip's figure this is bill.total_amount
    // itself, because finalisation has already run — and it is the exact amount that must be
    // tendered to settle.
    private BigDecimal totalAmount;
    // Who owes it. The same shape the floor strip already renders.
    private SessionNoteResponseDTO latestNote;
}
