package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.report.DailyReportResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.report.LossesDetailResponseDTO;
import com.supremebilliardshall.billiards_hall_system.service.ReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

// Admin only: every figure here is cost or profit, or leads to it.
@RestController
@RequestMapping("/api/v1/reports")
@PreAuthorize("hasRole('ADMIN')")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/daily")
    public ResponseEntity<APIResponse<DailyReportResponseDTO>> getDailyReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        DailyReportResponseDTO report = reportService.getDailyReport(date);
        return ResponseEntity.
                ok(APIResponse.success(
                        report,
                        "Daily report fetched successfully"));
    }

    // What is behind the losses tile. There is no friend-rate floor and no supervisor role, so
    // the owner reading this afterwards is the only control on comps and overrides — which is
    // why the reason travels with every row rather than living in a tooltip.
    @GetMapping("/losses")
    public ResponseEntity<APIResponse<LossesDetailResponseDTO>> getLossesDetail(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        LossesDetailResponseDTO losses = reportService.getLossesDetail(businessDate);
        return ResponseEntity.
                ok(APIResponse.success(
                        losses,
                        "Losses detail fetched successfully"));
    }

}
