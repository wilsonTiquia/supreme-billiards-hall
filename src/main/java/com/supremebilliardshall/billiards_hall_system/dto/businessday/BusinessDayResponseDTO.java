package com.supremebilliardshall.billiards_hall_system.dto.businessday;

import com.supremebilliardshall.billiards_hall_system.dto.pooltable.TableSessionSummaryDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BusinessDayResponseDTO {

    // Derived by the database via business_date_of(), never computed in Java.
    private LocalDate businessDate;
    private boolean canClose;
    private List<TableSessionSummaryDTO> openSessions;
    private OffsetDateTime serverNow;

    /*
     * The branch's standard change float, so the close-out can default to it and the counter is
     * never asked a question they would answer identically every night.
     *
     * Sending this to an EMPLOYEE does not weaken the blind count. The float is a constant they
     * put in the drawer themselves at open; what stays hidden is the takings, and knowing the
     * float tells you nothing about those.
     */
    private BigDecimal standardCashFloat;
}
