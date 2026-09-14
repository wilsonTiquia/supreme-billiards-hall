package com.supremebilliardshall.billiards_hall_system.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/*
 * The owner's month: any range of business dates, against the equivalent range before it.
 *
 * Admin only, like the daily report, and built from the SAME definitions -- a sale is a bill
 * in CLOSED or UNSETTLED by its business_date, a live line or expense is one with voided_at
 * NULL -- so the sum of the nightly reports over a range equals this report for the range, to
 * the centavo. A test holds that.
 *
 * Two words are used precisely throughout. GROSS PROFIT is gross less cost of goods, which is
 * what the dashboard calls profit. NET is gross profit less operating expenses, and it is the
 * figure this report exists for: it lives nowhere else in the system.
 *
 * A TRADING DAY is a business_date with at least one sale or a cash_count row. Every "per day"
 * figure here divides by trading days, never calendar days, or a hall closed on Tuesdays would
 * report a fifth of its takings as missing.
 *
 * The previous period is decided by the server and returned here so the client never computes
 * a date. The rule is by the shape of the range: from the 1st and ending inside the same month
 * compares to the same day-numbers of the month before, and a complete month to the complete
 * month before it; from a Monday and ending inside that week compares to the same weekdays a
 * week earlier; anything else compares to the same number of days immediately before `from`.
 * Like for like -- 1-14 Sep against 1-14 Aug, never against the whole of August, and September
 * against the whole of August rather than 30 days of it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PeriodReportResponseDTO {

    private LocalDate from;
    private LocalDate to;
    private LocalDate previousFrom;
    private LocalDate previousTo;
    // Which of the three rules above chose the previous period, so the page can say so.
    private PeriodComparison comparison;

    private PeriodTotalsDTO headline;
    private PeriodTotalsDTO previousHeadline;
    private BreakEvenDTO breakEven;

    // One row per calendar night in the period, trading or not, in date order.
    private List<PeriodDayDTO> byDay;
    // Seven rows, Monday first, averaged over trading days of that weekday only.
    private List<DayOfWeekAverageDTO> byDayOfWeek;
    // Asia/Manila hour, summed across the period. Same extract() as the daily salesByHour.
    private List<HourlySalesDTO> byHour;

    private List<PeriodExpenseCategoryDTO> expensesByCategory;
    private ExpenseMonthGridDTO expensesByMonth;

    // Weakest table first: lowest revenue per occupied hour, unplayed tables before all.
    private List<PeriodTableDTO> tables;
    // Thinnest margin first, so anything sold at or below cost is at the top.
    private List<PeriodProductDTO> products;
    // Stock that did not sell once in the period. Capital on the shelf.
    private List<UnsoldProductDTO> unsoldProducts;

    private PeriodLossesDTO givenAway;
    private CashDisciplineDTO cash;

    public enum PeriodComparison {
        // Same day-numbers of the previous month; a complete month against the complete month.
        SAME_DAYS_OF_PREVIOUS_MONTH,
        // Same weekdays of the previous week.
        SAME_DAYS_OF_PREVIOUS_WEEK,
        // The same number of days immediately before `from`.
        PRECEDING_DAYS
    }
}
