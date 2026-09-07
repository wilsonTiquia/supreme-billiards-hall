package com.supremebilliardshall.billiards_hall_system.entity;

// Mirrors the Postgres enum type bill_status.
public enum BillStatus {
    OPEN,
    CLOSED,
    // Played, finalised, and not paid for -- the regular who settles next month. A real sale
    // with a receipt number and frozen totals; only the money is outstanding. Distinct from an
    // OPEN bill with no live session, which is a mistake rather than a decision.
    UNSETTLED,
    VOIDED,
    MERGED
}
