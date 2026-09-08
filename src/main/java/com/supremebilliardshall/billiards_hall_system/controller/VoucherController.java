package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.voucher.VoucherBatchRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.voucher.VoucherBatchResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.voucher.VoucherResponseDTO;
import com.supremebilliardshall.billiards_hall_system.service.VoucherService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/*
 * Making the giveaway, which is the owner's job alone.
 *
 * ADMIN on every route here, and that is a security boundary rather than a tidy default: a
 * staff member who can read a list of unredeemed codes can redeem them. Spending one is counter
 * work and lives on BillController, where it belongs -- beside the discount, at the moment of
 * payment.
 */
@RestController
@RequestMapping("/api/v1")
public class VoucherController {

    private final VoucherService voucherService;

    public VoucherController(VoucherService voucherService) {
        this.voucherService = voucherService;
    }

    // Generates the whole batch in one transaction and returns the codes, so the owner can copy
    // or print them. This is the only response that ever carries a list of live codes.
    @PostMapping("/voucher-batches")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<VoucherBatchResponseDTO>> createBatch(
            @Valid @RequestBody VoucherBatchRequestDTO voucherBatchRequestDTO) {
        VoucherBatchResponseDTO batch = voucherService.createBatch(voucherBatchRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        batch,
                        "Voucher batch created successfully"));
    }

    // Each batch with its issued / redeemed / expired / outstanding counts — what is still in
    // the wild. No codes.
    @GetMapping("/voucher-batches")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<List<VoucherBatchResponseDTO>>> getBatches() {
        List<VoucherBatchResponseDTO> batches = voucherService.getBatches();
        return ResponseEntity.
                ok(APIResponse.success(
                        batches,
                        "Voucher batches fetched successfully"));
    }

    // The individual codes. Both filters optional; status is OUTSTANDING, REDEEMED or EXPIRED.
    @GetMapping("/vouchers")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<List<VoucherResponseDTO>>> getVouchers(
            @RequestParam(required = false) UUID batchId,
            @RequestParam(required = false) String status) {
        List<VoucherResponseDTO> vouchers = voucherService.getVouchers(batchId, status);
        return ResponseEntity.
                ok(APIResponse.success(
                        vouchers,
                        "Vouchers fetched successfully"));
    }
}
