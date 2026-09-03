package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.report.DailyReportResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.report.LossesDetailResponseDTO;
import com.supremebilliardshall.billiards_hall_system.repository.BranchRepository;
import com.supremebilliardshall.billiards_hall_system.repository.ReportRepository;
import com.supremebilliardshall.billiards_hall_system.security.BranchContext;
import com.supremebilliardshall.billiards_hall_system.service.ReportService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;

@Service
public class ReportServiceImpl implements ReportService {

    private final ReportRepository reportRepository;
    private final BranchRepository branchRepository;
    private final BranchContext branchContext;
    private final ObjectMapper objectMapper;

    public ReportServiceImpl(ReportRepository reportRepository,
                             BranchRepository branchRepository,
                             BranchContext branchContext,
                             ObjectMapper objectMapper) {
        this.reportRepository = reportRepository;
        this.branchRepository = branchRepository;
        this.branchContext = branchContext;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public DailyReportResponseDTO getDailyReport(LocalDate businessDate) {
        // No date means the night currently running, and only the database decides which that is.
        LocalDate reportDate = businessDate != null ? businessDate : branchRepository.currentBusinessDate();

        String json = reportRepository.dailyReport(branchContext.getCurrentBranchId(), reportDate);
        return objectMapper.readValue(json, DailyReportResponseDTO.class);
    }

    @Override
    @Transactional(readOnly = true)
    public LossesDetailResponseDTO getLossesDetail(LocalDate businessDate) {
        LocalDate reportDate = businessDate != null ? businessDate : branchRepository.currentBusinessDate();

        String json = reportRepository.lossesDetail(branchContext.getCurrentBranchId(), reportDate);
        return objectMapper.readValue(json, LossesDetailResponseDTO.class);
    }
}
