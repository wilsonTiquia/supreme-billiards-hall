package com.supremebilliardshall.billiards_hall_system.dto.pooltable;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PoolTableResponseDTO {

    private UUID id;
    private String name;
    private Integer tableNumber;
    private Boolean isActive;
    // The rate in force now, read from the open-ended pool_table_rate row. This is the figure
    // that bills; the two below are for the admin screen and charge nothing.
    private BigDecimal ratePerMinute;
    // What the admin typed, when they configured this rate hourly. Null on a table configured
    // per minute, which is every table that predates the hourly input mode.
    private BigDecimal ratePerHour;
    // 60 x ratePerMinute — what an hour on this table is actually priced at, as opposed to what
    // was typed. The two differ whenever the hourly figure does not divide by 60 exactly
    // (PHP 200/hour stores 3.3333/min, whose effective hourly rate is PHP 199.9980), and the
    // screen is expected to say so. Computed here rather than in the browser: 60 x a rate is
    // arithmetic on money, and this codebase does that server-side. Null when there is no rate.
    private BigDecimal effectiveRatePerHour;
    // Null when the table is free. Occupancy is a session, never a column on the table.
    private TableSessionSummaryDTO session;
}
