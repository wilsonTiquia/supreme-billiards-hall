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
        LossesDetailResponseDTO detail = objectMapper.readValue(json, LossesDetailResponseDTO.class);

        /*
         * The one thing this method does to the report rather than pass through.
         *
         * Voucher codes are STORED normalised -- SB7K4M2Q -- and shown with separators, and the
         * SQL cannot do the second without a second copy of the code's layout living in a
         * string expression. VoucherCodes owns that layout, and the whole point of it owning
         * both halves is that no other place gets to have an opinion on where the dashes go.
         */
        if (detail.getVouchers() != null && detail.getVouchers().getLines() != null) {
            detail.getVouchers().getLines()
                    .forEach(line -> line.setCode(VoucherCodes.display(line.getCode())));
        }
        return detail;
    }
}
