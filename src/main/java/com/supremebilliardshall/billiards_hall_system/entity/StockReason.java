package com.supremebilliardshall.billiards_hall_system.entity;

// Mirrors the Postgres enum type stock_reason.
public enum StockReason {
    SALE,        // deducted by a sale line
    SALE_VOID,   // returned to stock when that line is voided
    DELIVERY,    // goods received
    CORRECTION,  // manual count correction; note is mandatory
    STAFF_COMP   // consumed by staff or given away without a sale
}
