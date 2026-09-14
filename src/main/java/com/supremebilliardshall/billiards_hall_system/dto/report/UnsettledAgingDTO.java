package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/*
 * Debts still open, aged by the night they were played relative to the period.
 *
 * LIVE by the bill's current status, like the daily's `outstanding`: this answers "what is
 * still owed", so it moves when a debt is collected. Bills dated after `to` are excluded -- a
 * debt from this week is not a fact about last month.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UnsettledAgingDTO {

    private BillCountAndAmountDTO thisPeriod;
    // Played in the 28 days before `from`.
    private BillCountAndAmountDTO oneToFourWeeksBefore;
    private BillCountAndAmountDTO older;
}
