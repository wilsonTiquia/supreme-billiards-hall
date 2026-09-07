package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

// Admin only: every figure here is cost or profit or leads to it.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyReportResponseDTO {

    private LocalDate businessDate;
    // The previous business day, which every headline figure is reported against.
    private LocalDate comparedTo;

    private DailyTotalsDTO totals;
    private DailyTotalsDTO previousTotals;

    private List<HourlySalesDTO> salesByHour;
    /*
     * How the night's table time was priced: standard, promo, friend rate, flat. Always four
     * rows in that order, zeros included.
     *
     * THIS SPLIT RECONSTRUCTS totals.timeRevenue -- the four amounts sum to it exactly, and a
     * test asserts that on a day carrying one of each. Two figures on one screen that disagree
     * about the same money are worse than one figure alone, so if they ever stop reconciling
     * that is a bug to stop on rather than a rounding difference to live with.
     */
    private List<TimeRevenueByModeDTO> timeRevenueByMode;
    private List<TableUtilisationDTO> tableUtilisation;
    private List<TopItemDTO> topItems;
    private List<PaymentMixDTO> paymentMix;
    private LossesDTO losses;
    // Operating cost — water, electricity, rent, supplies. Kept apart from totals.cost, which
    // is cost of goods: summing the two would put the rent inside the margin on a beer.
    private ExpensesDTO expenses;
    private List<LowStockLineDTO> lowStock;
    private List<EmployeeSalesDTO> perEmployee;

    /*
     * The three debt figures, and the distinction between them is the whole of the revenue
     * recognition rule.
     *
     * totals.gross already INCLUDES unsettledTonight: the sale counts on the night it was
     * played. This says how much of that gross has not been collected, so the owner can read
     * the night and the drawer as two different facts rather than one confusing one.
     *
     * collectedToday is money that arrived tonight against an earlier night, and it is NOT in
     * totals.gross — that revenue was recognised when it was earned. It is here because it IS
     * in tonight's drawer, and the cash count would otherwise look inexplicably high.
     *
     * outstanding is every debt across every date, for the Attention band.
     */
    private BillCountAndAmountDTO unsettledTonight;
    private BillCountAndAmountDTO collectedToday;
    private BillCountAndAmountDTO outstanding;
}
