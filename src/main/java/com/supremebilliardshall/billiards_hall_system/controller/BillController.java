package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.PagedResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.*;
import com.supremebilliardshall.billiards_hall_system.dto.payment.PaymentRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.payment.PaymentResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.session.SessionNoteResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.voucher.RedeemVoucherRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.voucher.VoucherRedemptionResponseDTO;
import com.supremebilliardshall.billiards_hall_system.service.BillService;
import com.supremebilliardshall.billiards_hall_system.service.CheckoutService;
import com.supremebilliardshall.billiards_hall_system.service.SessionNoteService;
import com.supremebilliardshall.billiards_hall_system.service.VoucherService;
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
    private final VoucherService voucherService;

    public BillController(BillService billService,
                          CheckoutService checkoutService,
                          SessionNoteService sessionNoteService,
                          VoucherService voucherService) {
        this.billService = billService;
        this.checkoutService = checkoutService;
        this.sessionNoteService = sessionNoteService;
        this.voucherService = voucherService;
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

    // The debt list, and a different question from /unsettled above: that one is bills nobody
    // checked out, this one is bills the hall agreed to wait for. Both roles — collecting a debt
    // is counter work, and the shape carries no cost or profit.
    @GetMapping("/unpaid")
    public ResponseEntity<APIResponse<List<UnpaidBillResponseDTO>>> getUnpaidBills() {
        List<UnpaidBillResponseDTO> unpaid = billService.getUnpaidBills();
        return ResponseEntity.
                ok(APIResponse.success(
                        unpaid,
                        "Unpaid bills fetched successfully"));
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


    /*
     * Knocking money off the whole bill. Not admin-only, by decision: the owner is not at the
     * hall most nights, and a discount that needed him would either not happen or would happen
     * off the books. The control is the mandatory reason, the recorded actor and the figure on
     * the owner's dashboard the next morning — detection rather than prevention, the same
     * posture the friend rate takes.
     *
     * The body carries what is being CHARGED, not what is being taken off. The server does the
     * subtraction.
     */
    @PostMapping("/{id}/discount")
    public ResponseEntity<APIResponse<BillResponseDTO>> applyDiscount(@PathVariable UUID id,
                                                                     @Valid @RequestBody BillDiscountRequestDTO billDiscountRequestDTO) {
        BillResponseDTO bill = billService.applyDiscount(id, billDiscountRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        bill,
                        "Discount applied successfully"));
    }

    @DeleteMapping("/{id}/discount")
    public ResponseEntity<APIResponse<BillResponseDTO>> clearDiscount(@PathVariable UUID id) {
        BillResponseDTO bill = billService.clearDiscount(id);
        return ResponseEntity.
                ok(APIResponse.success(
                        bill,
                        "Discount cleared successfully"));
    }

    /*
     * Spending a giveaway voucher, beside the discount rather than under the admin routes that
     * create them. Making a batch is the owner's job; spending one is the cashier's, at the
     * counter, with the customer's phone in front of them — so this is one of the few voucher
     * routes that is not ADMIN.
     *
     * That split is the security boundary: whoever can READ an unredeemed code can spend it, so
     * the code list stays admin-only while redemption, which needs a code somebody already
     * holds, does not.
     */
    @PostMapping("/{id}/voucher")
    public ResponseEntity<APIResponse<VoucherRedemptionResponseDTO>> redeemVoucher(@PathVariable UUID id,
                                                                                   @Valid @RequestBody RedeemVoucherRequestDTO redeemVoucherRequestDTO) {
        VoucherRedemptionResponseDTO redemption = voucherService.redeem(id, redeemVoucherRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        redemption,
                        "Voucher redeemed successfully"));
    }

    // For a code entered against the wrong bill. Puts it back in the pot, unredeemed, and the
    // bill back to its full amount. Audited both ways.
    @DeleteMapping("/{id}/voucher")
    public ResponseEntity<APIResponse<VoucherRedemptionResponseDTO>> releaseVoucher(@PathVariable UUID id) {
        VoucherRedemptionResponseDTO redemption = voucherService.release(id);
        return ResponseEntity.
                ok(APIResponse.success(
                        redemption,
                        "Voucher released successfully"));
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

    /*
     * Finishing a bill that comes to nothing.
     *
     * Its own route rather than a payment of 0.00, because a payment of 0.00 is not something
     * that happened: payment_amount_chk refuses one and PaymentRequestDTO will not carry one.
     * The bill closes, takes its receipt number and lands on the night's report like any other
     * sale — it simply had nothing to collect.
     *
     * Refuses any bill with a figure on it, which is what keeps it out of the way of the paid
     * path. The counter reaches it only when the screen already reads 0.00.
     */
    @PostMapping("/{id}/no-charge")
    public ResponseEntity<APIResponse<ReceiptResponseDTO>> settleWithoutPayment(@PathVariable UUID id) {
        ReceiptResponseDTO receipt = checkoutService.settleWithoutPayment(id);
        return ResponseEntity.
                ok(APIResponse.success(
                        receipt,
                        "Bill closed with nothing to pay"));
    }

    // Recording the sale without the money. Not a variant of payment and deliberately not on
    // the same path: this is the one route by which a bill is completed and nothing goes in the
    // drawer, and it is audited as such.
    @PostMapping("/{id}/leave-unpaid")
    public ResponseEntity<APIResponse<UnpaidBillResponseDTO>> leaveUnpaid(@PathVariable UUID id,
                                                                          @Valid @RequestBody LeaveUnpaidRequestDTO leaveUnpaidRequestDTO) {
        UnpaidBillResponseDTO unpaid = checkoutService.leaveUnpaid(id, leaveUnpaidRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        unpaid,
                        "Bill left unpaid successfully"));
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
