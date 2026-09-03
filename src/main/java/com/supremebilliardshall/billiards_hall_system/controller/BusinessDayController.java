package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.businessday.BusinessDayResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.businessday.CashCountRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.businessday.CashCountUpdateRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.businessday.CashCountResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.businessday.UncountedDayDTO;
import com.supremebilliardshall.billiards_hall_system.service.BusinessDayService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/business-day")
public class BusinessDayController {

    private final BusinessDayService businessDayService;

    public BusinessDayController(BusinessDayService businessDayService) {
        this.businessDayService = businessDayService;
    }

    @GetMapping("/current")
    public ResponseEntity<APIResponse<BusinessDayResponseDTO>> getCurrent() {
        BusinessDayResponseDTO businessDay = businessDayService.getCurrentBusinessDay();
        return ResponseEntity.
                ok(APIResponse.success(
                        businessDay,
                        "Current business day fetched successfully"));
    }

    @GetMapping("/{date}/open-sessions")
    public ResponseEntity<APIResponse<BusinessDayResponseDTO>> getOpenSessions(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        BusinessDayResponseDTO businessDay = businessDayService.getOpenSessions(date);
        return ResponseEntity.
                ok(APIResponse.success(
                        businessDay,
                        "Open sessions fetched successfully"));
    }

    // The nights nobody counted. Surfaced the same way unsettled bills are: a night that was
    // never reconciled is invisible otherwise, and only noticed when the money is missed.
    @GetMapping("/uncounted")
    public ResponseEntity<APIResponse<List<UncountedDayDTO>>> getUncountedDays() {
        List<UncountedDayDTO> uncounted = businessDayService.getUncountedDays();
        return ResponseEntity.
                ok(APIResponse.success(
                        uncounted,
                        "Uncounted business days fetched successfully"));
    }

    // Null data when the day has not been counted, rather than a 404: "not counted yet" is an
    // ordinary state of the evening, not a missing resource.
    @GetMapping("/{date}/cash-count")
    public ResponseEntity<APIResponse<CashCountResponseDTO>> getCashCount(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        CashCountResponseDTO cashCount = businessDayService.getCashCount(date);
        return ResponseEntity.
                ok(APIResponse.success(
                        cashCount,
                        cashCount == null ? "The drawer has not been counted yet" : "Cash count fetched successfully"));
    }

    @PostMapping("/{date}/cash-count")
    public ResponseEntity<APIResponse<CashCountResponseDTO>> recordCashCount(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody CashCountRequestDTO cashCountRequestDTO) {
        CashCountResponseDTO cashCount = businessDayService.recordCashCount(date, cashCountRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        cashCount,
                        "Cash count recorded successfully"));
    }

    // ADMIN only, and deliberately so: a cashier who can re-count until the variance reads
    // zero has defeated the only check on the drawer.
    @PutMapping("/{date}/cash-count")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<CashCountResponseDTO>> updateCashCount(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody CashCountUpdateRequestDTO cashCountUpdateRequestDTO) {
        CashCountResponseDTO cashCount = businessDayService.updateCashCount(date, cashCountUpdateRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        cashCount,
                        "Cash count corrected successfully"));
    }

    // The way back from a night that was closed and then traded on. Not ADMIN: the shift that
    // closed up has to be able to finish the night without waking the owner.
    @PostMapping("/{date}/recount")
    public ResponseEntity<APIResponse<CashCountResponseDTO>> recountAfterClose(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody CashCountRequestDTO cashCountRequestDTO) {
        CashCountResponseDTO cashCount = businessDayService.recountAfterClose(date, cashCountRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        cashCount,
                        "Drawer recounted; close the day again to sign it off"));
    }

    @PostMapping("/{date}/close")
    public ResponseEntity<APIResponse<BusinessDayResponseDTO>> closeBusinessDay(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        BusinessDayResponseDTO businessDay = businessDayService.closeBusinessDay(date);
        return ResponseEntity.
                ok(APIResponse.success(
                        businessDay,
                        "Business day closed successfully"));
    }

}
