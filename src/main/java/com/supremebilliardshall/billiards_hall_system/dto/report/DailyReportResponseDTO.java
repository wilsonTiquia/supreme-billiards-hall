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
    private List<TableUtilisationDTO> tableUtilisation;
    private List<TopItemDTO> topItems;
    private List<PaymentMixDTO> paymentMix;
    private LossesDTO losses;
    // Operating cost — water, electricity, rent, supplies. Kept apart from totals.cost, which
    // is cost of goods: summing the two would put the rent inside the margin on a beer.
    private ExpensesDTO expenses;
    private List<LowStockLineDTO> lowStock;
    private List<EmployeeSalesDTO> perEmployee;
}
