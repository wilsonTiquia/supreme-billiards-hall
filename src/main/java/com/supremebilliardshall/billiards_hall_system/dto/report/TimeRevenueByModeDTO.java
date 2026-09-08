package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// Table revenue under one pricing mode, and how many sessions were sold that way.
//
// The count sits beside the money because the two answer different halves of the same question:
// PHP 3,000 of promo time is one thing across twenty sessions and quite another across two.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TimeRevenueByModeDTO {

    // STANDARD, PROMO, FRIEND or FLAT. A string rather than an enum, like PaymentMixDTO.method
    // beside it: these are report rows, and the report's job is to hand over what the database
    // grouped on rather than to re-type it.
    private String mode;
    private Integer sessions;
    private BigDecimal amount;
}
