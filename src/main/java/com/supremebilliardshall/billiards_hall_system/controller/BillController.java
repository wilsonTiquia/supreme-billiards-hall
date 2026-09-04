package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.PagedResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.*;
import com.supremebilliardshall.billiards_hall_system.dto.payment.PaymentRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.payment.PaymentResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.session.SessionNoteResponseDTO;
import com.supremebilliardshall.billiards_hall_system.service.BillService;
import com.supremebilliardshall.billiards_hall_system.service.CheckoutService;
import com.supremebilliardshall.billiards_hall_system.service.SessionNoteService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Ringing up and voting off a drink is the counter's job, so these are not admin-only. Cost
// fields are withheld by the response type instead.
@RestController
@RequestMapping("/api/v1/bills")
public class BillController {

    private final BillService billService;
    private final CheckoutService checkoutService;
    private final SessionNoteService sessionNoteService;

    public BillController(BillService billService,
                          CheckoutService checkoutService,
                          SessionNoteService sessionNoteService) {
        this.billService = billService;
        this.checkoutService = checkoutService;
        this.sessionNoteService = sessionNoteService;
    }

    // One business day's settled sales, newest first. ADMIN, because browsing the night's
    // takings is the owner's job, not the counter's — and because it names who took each one.
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<PagedResponseDTO<BillSummaryResponseDTO>>> getSettledBills(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        PagedResponseDTO<BillSummaryResponseDTO> bills = billService.getSettledBills(businessDate, page, size);
        return ResponseEntity.
                ok(APIResponse.success(
                        bills,
                        "Settled bills fetched successfully"));
    }

    // Declared ahead of /{id} for the next reader's sake. Spring matches the literal path
    // before the pattern either way, so "unsettled" is never taken for a bill id.
    @GetMapping("/unsettled")
    public ResponseEntity<APIResponse<List<UnsettledBillResponseDTO>>> getUnsettledBills() {
        List<UnsettledBillResponseDTO> unsettled = billService.getUnsettledBills();
        return ResponseEntity.
                ok(APIResponse.success(
                        unsettled,
                        "Unsettled bills fetched successfully"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<APIResponse<BillResponseDTO>> getBill(@PathVariable UUID id) {
        BillResponseDTO bill = billService.getBill(id);
        return ResponseEntity.
                ok(APIResponse.success(
                        bill,
                        "Bill fetched successfully"));
    }

    @PostMapping("/{id}/lines")
    public ResponseEntity<APIResponse<AddBillLineResponseDTO>> addLine(@PathVariable UUID id,
                                                                      @Valid @RequestBody AddBillLineRequestDTO addBillLineRequestDTO) {
        AddBillLineResponseDTO addedLine = billService.addLine(id, addBillLineRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        addedLine,
                        addedLine.getWarning() != null ? addedLine.getWarning() : "Line added successfully"));
    }

    @PostMapping("/{id}/lines/{lineId}/void")
    public ResponseEntity<APIResponse<BillLineResponseDTO>> voidLine(@PathVariable UUID id,
                                                                    @PathVariable UUID lineId,
                                                                    @Valid @RequestBody VoidBillLineRequestDTO voidBillLineRequestDTO) {
        BillLineResponseDTO voidedLine = billService.voidLine(id, lineId, voidBillLineRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        voidedLine,
                        "Line voided successfully"));
    }


    // Preview only: reading this writes nothing.
    @GetMapping("/{id}/checkout")
    public ResponseEntity<APIResponse<CheckoutPreviewResponseDTO>> previewCheckout(@PathVariable UUID id) {
        CheckoutPreviewResponseDTO preview = checkoutService.previewCheckout(id);
        return ResponseEntity.
                ok(APIResponse.success(
                        preview,
                        "Checkout preview fetched successfully"));
    }

    @PostMapping("/{id}/payment")
    public ResponseEntity<APIResponse<PaymentResponseDTO>> pay(@PathVariable UUID id,
                                                               @Valid @RequestBody PaymentRequestDTO paymentRequestDTO) {
        PaymentResponseDTO payment = checkoutService.pay(id, paymentRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        payment,
                        payment.isReplayed()
                                ? "This checkout was already recorded; returning the original payment"
                                : "Payment recorded successfully"));
    }

    // The whole thread for this bill, oldest first: every note written on every session it
    // carried, plus the settlement note. Nothing is dropped when the debt is collected —
    // permanence is the point, and a year of these is what answers who plays on credit.
    @GetMapping("/{id}/notes")
    public ResponseEntity<APIResponse<List<SessionNoteResponseDTO>>> getNotes(@PathVariable UUID id) {
        List<SessionNoteResponseDTO> notes = sessionNoteService.getNotesForBill(id);
        return ResponseEntity.
                ok(APIResponse.success(
                        notes,
                        "Bill notes fetched successfully"));
    }

    @GetMapping("/{id}/receipt")
    public ResponseEntity<APIResponse<ReceiptResponseDTO>> getReceipt(@PathVariable UUID id) {
        ReceiptResponseDTO receipt = checkoutService.getReceipt(id);
        return ResponseEntity.
                ok(APIResponse.success(
                        receipt,
                        "Receipt fetched successfully"));
    }

}
