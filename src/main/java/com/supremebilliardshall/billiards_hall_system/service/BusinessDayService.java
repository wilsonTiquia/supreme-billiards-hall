package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.businessday.BusinessDayResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.businessday.CashCountRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.businessday.CashCountUpdateRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.businessday.CashCountResponseDTO;

import com.supremebilliardshall.billiards_hall_system.dto.businessday.UncountedDayDTO;

import java.time.LocalDate;
import java.util.List;

public interface BusinessDayService {
    BusinessDayResponseDTO getCurrentBusinessDay();

    // What is blocking the close.
    BusinessDayResponseDTO getOpenSessions(LocalDate businessDate);

    // expected_cash is computed here from the cash payments of that business date, and frozen.
    // The drawer count as recorded, or null when the day has not been counted yet. Without
    // this the closer cannot see the variance that was entered — including one entered by
    // whoever was on shift before them.
    CashCountResponseDTO getCashCount(LocalDate businessDate);

    // ADMIN only. Corrects a mistyped count, writing the old and new values to the audit log.
    // Refused once the day is closed.
    CashCountResponseDTO updateCashCount(LocalDate businessDate, CashCountUpdateRequestDTO cashCountUpdateRequestDTO);

    CashCountResponseDTO recordCashCount(LocalDate businessDate, CashCountRequestDTO cashCountRequestDTO);

    // Counts a night again after it was closed and then traded on. The original count goes to
    // the audit log and the day reopens so the ordinary close runs a second time.
    CashCountResponseDTO recountAfterClose(LocalDate businessDate, CashCountRequestDTO cashCountRequestDTO);

    // Earlier nights that traded and were never counted. Informational only — it never blocks
    // tonight's close.
    List<UncountedDayDTO> getUncountedDays();

    // Rejected while any session is open, listing them.
    BusinessDayResponseDTO closeBusinessDay(LocalDate businessDate);
}
