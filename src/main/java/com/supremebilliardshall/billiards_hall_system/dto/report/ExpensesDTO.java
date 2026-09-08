package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/*
 * What it cost to be open, as distinct from what the goods cost.
 *
 * Deliberately not folded into DailyTotals.cost: that figure is cost of goods, snapshotted onto
 * bill_line at the moment of sale, and adding the rent to it would put the rent inside the
 * margin on a beer. They sit side by side on the dashboard and are never summed by the server.
 *
 * Voided expenses are excluded here exactly as voided lines are excluded from revenue.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExpensesDTO {

    private BigDecimal total;
    // The same figure for the previous business day, so the tile can carry a delta like every
    // other headline.
    private BigDecimal previousTotal;
    private List<ExpenseCategoryTotalDTO> byCategory;
}
