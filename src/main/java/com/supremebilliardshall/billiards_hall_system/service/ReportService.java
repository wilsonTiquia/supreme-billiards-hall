package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.report.DailyReportResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.report.LossesDetailResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.report.PeriodReportResponseDTO;

import java.time.LocalDate;

public interface ReportService {
    DailyReportResponseDTO getDailyReport(LocalDate businessDate);

    // The rows behind the dashboard's three loss figures. Same day, same predicates, so each
    // section total agrees with the tile it came from.
    LossesDetailResponseDTO getLossesDetail(LocalDate businessDate);

    // Any range of business dates, inclusive, against the equivalent range before it. Built
    // from the daily report's definitions, so the nights of a range sum to the range.
    PeriodReportResponseDTO getPeriodReport(LocalDate from, LocalDate to);
}
