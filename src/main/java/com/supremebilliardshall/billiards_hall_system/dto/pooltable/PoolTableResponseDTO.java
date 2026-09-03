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
    // The rate in force now, read from the open-ended pool_table_rate row.
    private BigDecimal ratePerMinute;
    // Null when the table is free. Occupancy is a session, never a column on the table.
    private TableSessionSummaryDTO session;
}
