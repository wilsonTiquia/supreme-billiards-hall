package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.report.DailyReportResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.report.LossesDetailResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.report.PeriodReportResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.report.PeriodReportResponseDTO.PeriodComparison;
import com.supremebilliardshall.billiards_hall_system.exception.InvalidDateRangeException;
import com.supremebilliardshall.billiards_hall_system.repository.BranchRepository;
import com.supremebilliardshall.billiards_hall_system.repository.ReportRepository;
import com.supremebilliardshall.billiards_hall_system.security.BranchContext;
import com.supremebilliardshall.billiards_hall_system.service.ReportService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

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

    // A year plus a leap day. The SQL walks every night of both windows, and two years of
    // nights is where "one statement" stops being the right shape.
    static final int MAX_RANGE_DAYS = 366;

    @Override
    @Transactional(readOnly = true)
    public PeriodReportResponseDTO getPeriodReport(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new InvalidDateRangeException("The end of the range is before its start");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new InvalidDateRangeException(
                    "A period report covers at most " + MAX_RANGE_DAYS + " days; this range is " + days);
        }

        PreviousPeriod previous = previousPeriod(from, to);
        String json = reportRepository.periodReport(branchContext.getCurrentBranchId(),
                from, to, previous.from(), previous.to());
        PeriodReportResponseDTO report = objectMapper.readValue(json, PeriodReportResponseDTO.class);
        report.setComparison(previous.comparison());
        return report;
    }

    record PreviousPeriod(LocalDate from, LocalDate to, PeriodComparison comparison) {}

    /*
     * The period this one is compared against, decided from the shape of the range so that a
     * bookmarked from/to always reproduces the same comparison.
     *
     * LIKE FOR LIKE is the point. A month-to-date of 1-14 September against the whole of
     * August is the kind of report that gets ignored after one reading, so a range that starts
     * on the 1st and ends inside the same month compares to the same day-numbers of the month
     * before -- 1-14 Aug. A COMPLETE month compares to the complete month before it: September
     * against all 31 days of August, February against all 31 of January. Truncating those to
     * the same day count would drop 31 August from every September report and three days of
     * January from every February one, and a month that quietly loses a Saturday is not the
     * previous month. A range that starts on a Monday and ends inside that week compares to
     * the same weekdays a week earlier, which for a whole week is also simply the seven days
     * before it. Anything else compares to the same number of days immediately before `from`.
     *
     * Calendar arithmetic only. Which business_date a sale belongs to is the database's
     * decision, and this never touches it.
     */
    static PreviousPeriod previousPeriod(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;

        if (from.getDayOfMonth() == 1 && YearMonth.from(from).equals(YearMonth.from(to))) {
            YearMonth thisMonth = YearMonth.from(from);
            YearMonth previousMonth = thisMonth.minusMonths(1);
            LocalDate previousFrom = previousMonth.atDay(1);
            LocalDate previousTo = to.equals(thisMonth.atEndOfMonth())
                    ? previousMonth.atEndOfMonth()
                    : previousFrom.plusDays(days - 1);
            return new PreviousPeriod(previousFrom, previousTo, PeriodComparison.SAME_DAYS_OF_PREVIOUS_MONTH);
        }

        if (from.getDayOfWeek() == DayOfWeek.MONDAY && days <= 7) {
            return new PreviousPeriod(from.minusWeeks(1), to.minusWeeks(1), PeriodComparison.SAME_DAYS_OF_PREVIOUS_WEEK);
        }

        return new PreviousPeriod(from.minusDays(days), from.minusDays(1), PeriodComparison.PRECEDING_DAYS);
    }
}
