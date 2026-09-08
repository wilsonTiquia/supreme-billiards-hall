package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.bill.AddBillLineRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.CheckoutPreviewResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.LeaveUnpaidRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.ReceiptResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.ReceiptSettlementDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.UnpaidBillResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.payment.PaymentRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.payment.PaymentResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.quicksale.QuickSaleQuoteLineDTO;
import com.supremebilliardshall.billiards_hall_system.dto.quicksale.QuickSaleQuoteRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.quicksale.QuickSaleQuoteResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.quicksale.QuickSaleRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.session.SessionNoteRequestDTO;
import com.supremebilliardshall.billiards_hall_system.entity.*;
import com.supremebilliardshall.billiards_hall_system.exception.BusinessRuleException;
import com.supremebilliardshall.billiards_hall_system.exception.DuplicateReferenceException;
import com.supremebilliardshall.billiards_hall_system.exception.ResourceNotFoundException;
import com.supremebilliardshall.billiards_hall_system.exception.SessionNoteRequiredException;
import com.supremebilliardshall.billiards_hall_system.exception.StaleBillVersionException;
import com.supremebilliardshall.billiards_hall_system.repository.*;
import com.supremebilliardshall.billiards_hall_system.security.BranchContext;
import com.supremebilliardshall.billiards_hall_system.service.AuditService;
import com.supremebilliardshall.billiards_hall_system.service.BillService;
import com.supremebilliardshall.billiards_hall_system.service.CheckoutService;
import com.supremebilliardshall.billiards_hall_system.service.SessionNoteService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CheckoutServiceImpl implements CheckoutService {

    private final BillRepository billRepository;
    private final BillLineRepository billLineRepository;
    private final PaymentRepository paymentRepository;
    private final ReceiptRepository receiptRepository;
    private final BranchRepository branchRepository;
    private final TableSessionRepository tableSessionRepository;
    private final CustomerTypeRepository customerTypeRepository;
    private final ProductRepository productRepository;
    private final AppUserRepository appUserRepository;
    // Read-only, to name the voucher on the receipt. Redemption lives in VoucherService.
    private final VoucherRepository voucherRepository;
    private final BillService billService;
    private final SessionNoteService sessionNoteService;
    private final AuditService auditService;
    private final BranchContext branchContext;

    public CheckoutServiceImpl(BillRepository billRepository,
                               BillLineRepository billLineRepository,
                               PaymentRepository paymentRepository,
                               ReceiptRepository receiptRepository,
                               BranchRepository branchRepository,
                               TableSessionRepository tableSessionRepository,
                               CustomerTypeRepository customerTypeRepository,
                               ProductRepository productRepository,
                               AppUserRepository appUserRepository,
                               VoucherRepository voucherRepository,
                               BillService billService,
                               SessionNoteService sessionNoteService,
                               AuditService auditService,
                               BranchContext branchContext) {
        this.billRepository = billRepository;
        this.billLineRepository = billLineRepository;
        this.paymentRepository = paymentRepository;
        this.receiptRepository = receiptRepository;
        this.branchRepository = branchRepository;
        this.tableSessionRepository = tableSessionRepository;
        this.customerTypeRepository = customerTypeRepository;
        this.productRepository = productRepository;
        this.appUserRepository = appUserRepository;
        this.voucherRepository = voucherRepository;
        this.billService = billService;
        this.sessionNoteService = sessionNoteService;
        this.auditService = auditService;
        this.branchContext = branchContext;
    }


    @Override
    @Transactional(readOnly = true)
    public CheckoutPreviewResponseDTO previewCheckout(UUID billId) {
        Bill bill = requireBill(billId);
        List<String> blockers = checkoutBlockers(bill);
        return new CheckoutPreviewResponseDTO(billService.getBill(billId), blockers.isEmpty(), blockers);
    }

    @Override
    @Transactional
    public PaymentResponseDTO pay(UUID billId, PaymentRequestDTO paymentRequestDTO) {
        Bill bill = requireBillForUpdate(billId);

        // Idempotency first, before any state check: a retry of a request that already
        // succeeded must return the original payment, not complain that the bill is closed.
        Payment replay = paymentRepository.findByIdempotencyKey(paymentRequestDTO.getIdempotencyKey())
                .orElse(null);
        if (replay != null) {
            if (!replay.getBillId().equals(bill.getId())) {
                throw new BusinessRuleException(
                        "That idempotency key was already used to settle a different bill.");
            }
            return toResponseDto(replay, receiptNoFor(replay.getBillId()), true);
        }

        List<String> blockers = checkoutBlockers(bill);
        if (!blockers.isEmpty()) {
            throw new BusinessRuleException(String.join(" ", blockers));
        }
        if (!bill.getVersion().equals(paymentRequestDTO.getBillVersion())) {
            throw new StaleBillVersionException(paymentRequestDTO.getBillVersion(), bill.getVersion());
        }

        return settle(bill, paymentRequestDTO);
    }

    @Override
    @Transactional
    public UnpaidBillResponseDTO leaveUnpaid(UUID billId, LeaveUnpaidRequestDTO leaveUnpaidRequestDTO) {
        Bill bill = requireBillForUpdate(billId);

        // Only a live bill can become a debt. checkoutBlockers admits UNSETTLED because that is
        // payable; here it is the one status that must be refused, and saying so plainly beats
        // "Bill is already UNSETTLED" from a blocker list the operator did not ask for.
        if (bill.getStatus() == BillStatus.UNSETTLED) {
            throw new BusinessRuleException("This bill is already recorded as unpaid.");
        }
        List<String> blockers = checkoutBlockers(bill);
        if (!blockers.isEmpty()) {
            throw new BusinessRuleException(String.join(" ", blockers));
        }
        if (!bill.getVersion().equals(leaveUnpaidRequestDTO.getBillVersion())) {
            throw new StaleBillVersionException(leaveUnpaidRequestDTO.getBillVersion(), bill.getVersion());
        }

        /*
         * A quick sale cannot be left unpaid, and the refusal is structural rather than a
         * policy choice: a quick sale has no session, so there is nowhere to hang the note that
         * says who owes the money, and a debt with no name against it is money nobody can
         * collect. It is also settled in the same transaction it is created in — there is no
         * moment at which a quick sale exists and is unpaid.
         */
        List<TableSession> sessions = tableSessionRepository.findByBillId(bill.getId());
        if (sessions.isEmpty()) {
            throw new BusinessRuleException("A quick sale cannot be left unpaid — there is no session to "
                    + "record who owes it. Take the payment, or void the sale.");
        }

        String note = leaveUnpaidRequestDTO.getNote() == null
                ? "" : leaveUnpaidRequestDTO.getNote().trim();
        /*
         * The name is the whole point, so it is enforced here rather than hoped for.
         *
         * A note already written during the session is enough — staff usually put the names on
         * when the table opens — and in that case the field on this request is optional. Only
         * STAFF notes count: the SYSTEM note settlement writes is the server talking to itself,
         * and letting it satisfy this rule would mean a bill could be left unpaid on the
         * strength of a line nobody typed.
         */
        boolean named = sessionNoteService.getNotesForBill(bill.getId()).stream()
                .anyMatch(existing -> existing.getKind() == SessionNoteKind.STAFF);
        if (note.isEmpty() && !named) {
            throw new SessionNoteRequiredException();
        }

        UUID actorId = branchContext.getCurrentUserId();
        // Stamped before finalise rather than after, so the row is complete and legal the one
        // time it is written: bill_unsettled_consistency_chk requires both of these on an
        // UNSETTLED bill, and finalise flushes.
        bill.setUnsettledAt(OffsetDateTime.now());
        bill.setUnsettledBy(actorId);
        List<BillLine> lines = finalise(bill, actorId, BillStatus.UNSETTLED);
        // A debt of nothing is not a debt. Refused after finalise rather than before, because
        // the total is not known until the live lines are summed -- and the whole request rolls
        // back, so the receipt number finalise allocated is never handed out.
        if (bill.getTotalAmount().signum() == 0) {
            throw new BusinessRuleException("There is nothing owed on this bill, so there is no "
                    + "debt to record. Finish it with \"Nothing to pay\".");
        }

        // Written against the most recent session, which is where CheckoutPage's note box and
        // the settlement note both write: a merged bill has several, and one debt is one event.
        if (!note.isEmpty()) {
            TableSession latest = sessions.stream()
                    .max(Comparator.comparing(TableSession::getOpenedAt))
                    .orElseThrow();
            sessionNoteService.addNote(latest.getId(), new SessionNoteRequestDTO(note));
        }

        // The same snapshot a paid checkout writes, minus the payment block. The customer is
        // handed a numbered chit that says what was played and what is owed.
        Receipt receipt = new Receipt();
        receipt.setBranchId(bill.getBranchId());
        receipt.setBillId(bill.getId());
        receipt.setReceiptNo(bill.getReceiptNo());
        receipt.setPayload(receiptPayload(bill, lines, null));
        receiptRepository.saveAndFlush(receipt);

        /*
         * Audited with the amount, because this is the one way a sale is completed without money
         * appearing in the drawer. The system's posture is detection rather than prevention --
         * friend rates and voids are recorded with their actor rather than blocked -- and a
         * bill left unpaid is the same shape of decision.
         *
         * No SYSTEM note here, deliberately, unlike settlement. Settlement writes one because
         * it is otherwise invisible on the thread; this event is not, because a STAFF note
         * naming who owes it is guaranteed by the check above. A system line beside it would be
         * the only line in the thread that nobody wrote.
         */
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("receiptNo", bill.getReceiptNo());
        after.put("totalAmount", bill.getTotalAmount());
        after.put("businessDate", bill.getBusinessDate());
        auditService.record("BILL_LEFT_UNPAID", "bill", bill.getId(), null, after,
                note.isEmpty() ? null : note);

        return billService.getUnpaidBill(bill.getId());
    }

    /*
     * Finishing a bill that comes to nothing.
     *
     * The prize winner who plays ninety minutes on a two-hour voucher and buys no drinks owes
     * nothing, and this is the only way that night ends. Without it the counter reaches
     * "There is nothing to charge on this bill" with a customer standing in front of them
     * holding a prize, and the bill stays open on the floor for ever.
     *
     * NO PAYMENT ROW, and that is the honest record rather than a gap: payment_amount_chk
     * requires an amount above zero, because a payment of 0.00 is not a thing that happened.
     * bill_closed_consistency_chk asks only for closed_at, closed_by and a receipt number, all
     * of which this writes -- a CLOSED bill has never been required to carry a payment, and
     * BillRepository.findSettledByBusinessDate now joins payment outer for exactly this row.
     *
     * GATED ON THE FIGURE BEING ZERO, checked after finalise where the figure is real. A bill
     * with anything on it must be unreachable from here: this is the one path in the system
     * that completes a sale without money, and the only thing standing between it and a
     * giveaway is that test. Everything else about checkout -- the receipt number under its row
     * lock, closed_at deciding the business date, the version bump -- is finalise's, unchanged
     * and shared, so this cannot drift from the paid path.
     */
    @Override
    @Transactional
    public ReceiptResponseDTO settleWithoutPayment(UUID billId) {
        Bill bill = requireBillForUpdate(billId);

        List<String> blockers = checkoutBlockers(bill);
        if (!blockers.isEmpty()) {
            throw new BusinessRuleException(String.join(" ", blockers));
        }
        // UNSETTLED is payable and therefore not zero -- leaveUnpaid refuses a zero debt -- so a
        // bill arriving here already finalised has money on it and belongs on the paid path.
        if (bill.getStatus() != BillStatus.OPEN) {
            throw new BusinessRuleException("Bill is already " + bill.getStatus() + ".");
        }

        UUID actorId = branchContext.getCurrentUserId();
        List<BillLine> lines = finalise(bill, actorId, BillStatus.CLOSED);

        if (bill.getTotalAmount().signum() != 0) {
            throw new BusinessRuleException("This bill comes to "
                    + bill.getTotalAmount().toPlainString() + ", so it has to be paid. "
                    + "Take the payment, or leave it unpaid.");
        }

        Receipt receipt = new Receipt();
        receipt.setBranchId(bill.getBranchId());
        receipt.setBillId(bill.getId());
        receipt.setReceiptNo(bill.getReceiptNo());
        receipt.setPayload(receiptPayload(bill, lines, null));
        receiptRepository.saveAndFlush(receipt);

        // Audited like every other way money does not reach the drawer. There is no reason
        // field: the voucher or the zero rate that produced the figure carries its own, and
        // asking for a second one here would be asking the counter to justify arithmetic.
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("receiptNo", bill.getReceiptNo());
        after.put("voucherAmount", bill.getVoucherAmount());
        after.put("discountAmount", bill.getDiscountAmount());
        after.put("businessDate", bill.getBusinessDate());
        auditService.record("BILL_CLOSED_NO_CHARGE", "bill", bill.getId(), null, after, null);

        return getReceipt(bill.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public ReceiptResponseDTO getReceipt(UUID billId) {
        Receipt receipt = receiptRepository.findByBillId(billId)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt for bill", billId));

        /*
         * The payload goes back verbatim — it is the record of the night and nothing may edit
         * it. The settlement block is assembled beside it, from payment, and only for a bill
         * that passed through UNSETTLED: on an ordinary sale the payload already names the
         * method, and there was never a gap between the sale and the money to describe.
         */
        ReceiptSettlementDTO settlement = billRepository.findById(billId)
                .filter(bill -> bill.getSettledAt() != null)
                .flatMap(bill -> paymentRepository.findByBillId(billId))
                .map(payment -> new ReceiptSettlementDTO(
                        payment.getMethod(),
                        payment.getAmount(),
                        payment.getTakenAt(),
                        usernameOf(payment.getTakenBy())))
                .orElse(null);

        return new ReceiptResponseDTO(receipt.getId(), receipt.getBillId(), receipt.getReceiptNo(),
                receipt.getIssuedAt(), receipt.getPayload(), settlement);
    }

    @Override
    @Transactional(readOnly = true)
    public QuickSaleQuoteResponseDTO quoteQuickSale(QuickSaleQuoteRequestDTO quickSaleQuoteRequestDTO) {
        List<QuickSaleQuoteLineDTO> quoted = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (AddBillLineRequestDTO line : quickSaleQuoteRequestDTO.getLines()) {
            Product product = productRepository.findById(line.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", line.getProductId()));
            // The same refusal addLine makes, so a quote can never promise a sale that the
            // settle will then reject.
            if (product.getArchivedAt() != null) {
                throw new BusinessRuleException("Product '" + product.getName() + "' is archived.");
            }

            // Priced exactly as addLine will snapshot it. No stock is checked and none moves:
            // selling below zero is allowed, and it is the sale that records the movement.
            BigDecimal lineTotal = product.getSellingPrice().multiply(line.getQuantity());
            quoted.add(new QuickSaleQuoteLineDTO(product.getId(), product.getName(),
                    product.getSellingPrice(), line.getQuantity(), lineTotal));
            total = total.add(lineTotal);
        }

        return new QuickSaleQuoteResponseDTO(quoted, total);
    }

    @Override
    @Transactional
    public PaymentResponseDTO quickSale(QuickSaleRequestDTO quickSaleRequestDTO) {
        CustomerType customerType = customerTypeRepository.findById(quickSaleRequestDTO.getCustomerTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer type", quickSaleRequestDTO.getCustomerTypeId()));

        Bill bill = new Bill();
        bill.setBranchId(branchContext.getCurrentBranchId());
        bill.setStatus(BillStatus.OPEN);
        bill.setCustomerTypeId(customerType.getId());
        bill.setOpenedBy(branchContext.getCurrentUserId());
        bill.setSubtotalTime(BigDecimal.ZERO);
        bill.setSubtotalItems(BigDecimal.ZERO);
        bill.setTotalAmount(BigDecimal.ZERO);
        bill.setTotalCost(BigDecimal.ZERO);
        Bill savedBill = billRepository.saveAndFlush(bill);

        // Same stock rules as any other line, including the row lock and the below-zero warning.
        for (AddBillLineRequestDTO line : quickSaleRequestDTO.getLines()) {
            billService.addLine(savedBill.getId(), line);
        }

        // The bill was created inside this transaction, so there is no earlier version the
        // client could have read; its own version is authoritative.
        return settle(savedBill, quickSaleRequestDTO.getPayment());
    }


    private PaymentResponseDTO settle(Bill bill, PaymentRequestDTO paymentRequestDTO) {
        UUID actorId = branchContext.getCurrentUserId();

        /*
         * Two arrivals at the same counter.
         *
         * A bill still OPEN is being sold now: it has to be finalised first, which is what
         * computes the total the payment is checked against and what allocates its receipt
         * number. A bill already UNSETTLED was finalised weeks ago, when the debt was recorded;
         * its totals are frozen and MUST NOT be recomputed. Re-summing the lines would silently
         * re-price the sale at today's figures for anything that has since changed, and would
         * overwrite the total the customer was quoted on the night.
         */
        boolean settlingDebt = bill.getStatus() == BillStatus.UNSETTLED;
        List<BillLine> lines = settlingDebt
                ? billLineRepository.findByBillId(bill.getId())
                : finalise(bill, actorId, BillStatus.CLOSED);

        BigDecimal totalAmount = bill.getTotalAmount();

        // Nothing to take. payment_amount_chk requires more than zero, so there is no payment
        // row that could record this -- and there does not need to be one: settleWithoutPayment
        // closes such a bill, and the message names it rather than leaving the counter stuck.
        if (totalAmount.signum() == 0) {
            throw new BusinessRuleException("There is nothing to charge on this bill. "
                    + "Finish it with \"Nothing to pay\" instead of taking a payment.");
        }

        if (paymentRequestDTO.getAmount().compareTo(totalAmount) != 0) {
            // Part payment is not a thing this system records. A debt is collected in full or
            // it stays outstanding, and half of ₱654 in the drawer against a bill that still
            // reads ₱654 owed is worse than no record at all.
            throw new BusinessRuleException(settlingDebt
                    ? "This debt is " + totalAmount.toPlainString() + " and must be settled in full. "
                      + "Part payment is not recorded — collect the whole amount or leave it outstanding."
                    // Exact, in both directions. Cash overpayment is expressed as tendered,
                    // never here.
                    : "Amount " + paymentRequestDTO.getAmount().toPlainString()
                      + " does not match the bill total " + totalAmount.toPlainString() + ".");
        }

        Payment payment = new Payment();
        payment.setBranchId(bill.getBranchId());
        payment.setBillId(bill.getId());
        payment.setMethod(paymentRequestDTO.getMethod());
        payment.setAmount(totalAmount);
        payment.setIdempotencyKey(paymentRequestDTO.getIdempotencyKey());
        payment.setTakenBy(actorId);
        applyMethodRules(payment, paymentRequestDTO, totalAmount, actorId);

        if (settlingDebt) {
            bill.setStatus(BillStatus.CLOSED);
            /*
             * The money arriving, recorded WITHOUT touching closed_at.
             *
             * bill.business_date is GENERATED ALWAYS AS business_date_of(COALESCE(closed_at,
             * opened_at)), so stamping closed_at here would drag a September sale onto an
             * October report and rewrite a night the owner has already read and reconciled.
             * The sale is dated by the night it was played; only payment.business_date, which
             * is generated from taken_at, follows the money.
             */
            bill.setSettledAt(OffsetDateTime.now());
            billRepository.saveAndFlush(bill);
        }

        Payment savedPayment = paymentRepository.saveAndFlush(payment);

        // Closes the thread that the unpaid card opened: who collected, when, and by what
        // method, alongside the names of who owed it. Written by the server and marked SYSTEM,
        // so it cannot be produced by typing the same sentence into the note box. On both
        // paths — a debt settled five weeks late needs this line more than a normal sale does.
        sessionNoteService.recordSettlement(savedPayment);

        /*
         * A settled debt writes no receipt, and cannot: the receipt table is append-only by
         * trigger and UNIQUE on bill_id, so the one issued when the bill was left unpaid is the
         * only one this bill will ever have. That is the right answer rather than a limitation
         * worked around — it is the document that was actually handed over on the night, and it
         * said "unpaid" because it was. What happened later is read back from payment.
         */
        if (!settlingDebt) {
            Receipt receipt = new Receipt();
            receipt.setBranchId(bill.getBranchId());
            receipt.setBillId(bill.getId());
            receipt.setReceiptNo(bill.getReceiptNo());
            receipt.setPayload(receiptPayload(bill, lines, savedPayment));
            receiptRepository.saveAndFlush(receipt);
        }

        return toResponseDto(savedPayment, bill.getReceiptNo(), false);
    }

    /*
     * Everything that turns a live bill into a finished sale except the money.
     *
     * Shared by checkout and by leaving a bill unpaid, because those two differ in exactly one
     * respect — whether anybody paid — and in none of the respects handled here. A second copy
     * of the receipt-number allocation in particular would be a second place for two customers
     * to be handed the same number.
     *
     * Mutates the bill in memory rather than returning a carrier of six values, and hands back
     * the live lines, which is the one thing the caller needs and cannot read off the bill.
     * Nothing is flushed: the caller saves the bill once, having set the status only it knows.
     */
    private List<BillLine> finalise(Bill bill, UUID actorId, BillStatus status) {
        List<BillLine> lines = billLineRepository.findByBillId(bill.getId());
        BigDecimal subtotalTime = sumLive(lines, BillLineKind.TIME, false);
        BigDecimal subtotalItems = sumLive(lines, BillLineKind.PRODUCT, false);
        /*
         * Both reductions are taken off HERE and nowhere else, so every downstream reader --
         * the payment check, the receipt, the dashboard's gross -- sees one total.
         *
         * Fixed amounts, applied to whatever the bill has come to by now. A line added after
         * either one raises the subtotal and this figure with it; neither follows it up. Not
         * clamped here: applyDiscount and redeem each refused to overshoot the subtotal at the
         * time, and lines only ever get added, so the subtotal at checkout is at least what it
         * was then.
         */
        BigDecimal discountAmount = bill.getDiscountAmount();
        BigDecimal voucherAmount = bill.getVoucherAmount();
        BigDecimal totalAmount = BillTotals.payable(subtotalTime, subtotalItems,
                discountAmount, voucherAmount);
        BigDecimal totalCost = sumLive(lines, null, true);

        /*
         * NEGATIVE only. Zero is a legitimate outcome and each caller decides what to do with it.
         *
         * This guard used to refuse zero as well, back when nothing could close a bill without
         * a payment. A voucher covering the whole of a bill reaches zero honestly -- a prize
         * winner who plays ninety minutes on a two-hour code and buys nothing owes nothing --
         * so the rule moved to the three callers, which want three different things from the
         * same figure: settle refuses zero because there is no payment to take, leaveUnpaid
         * refuses it because a debt of nothing is not a debt, and settleWithoutPayment requires
         * it. One shared refusal could only ever have been right for one of them.
         *
         * Below zero is not a case, it is a broken invariant: applyDiscount and redeem each
         * bound themselves by the subtotal, and lines only ever get added. It fails loudly here
         * rather than writing a bill the hall owes the customer money on.
         */
        if (totalAmount.signum() < 0) {
            throw new BusinessRuleException("This bill has been reduced below zero — "
                    + subtotalTime.add(subtotalItems).toPlainString() + " charged, "
                    + discountAmount.add(voucherAmount).toPlainString()
                    + " taken off. Remove the discount or the voucher and start again.");
        }

        // Allocated under a row lock, inside this transaction: two simultaneous checkouts
        // must not hand two customers the same receipt number.
        Branch branch = branchRepository.findByIdForUpdate(bill.getBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Branch", bill.getBranchId()));
        long receiptNo = branch.getNextReceiptNo();
        branch.setNextReceiptNo(receiptNo + 1);
        branchRepository.saveAndFlush(branch);

        bill.setSubtotalTime(subtotalTime);
        bill.setSubtotalItems(subtotalItems);
        bill.setTotalAmount(totalAmount);
        bill.setTotalCost(totalCost);
        bill.setReceiptNo(receiptNo);
        /*
         * closed_at is WHEN THE SALE HAPPENED, and it is stamped here on both paths — including
         * the one where no money changes hands. business_date is generated from it, so this is
         * the single write that decides which night's report the sale lands on, and it is never
         * written again. A debt settled next month is recorded in settled_at instead.
         */
        bill.setClosedAt(OffsetDateTime.now());
        bill.setClosedBy(actorId);
        /*
         * The final status is set HERE, and the row is flushed HERE, rather than being left to
         * the caller a few statements later.
         *
         * bill_closed_consistency_chk requires closed_at to be null on any status that is not
         * CLOSED or UNSETTLED, so a bill carrying closed_at while still OPEN is an illegal row.
         * It only has to reach the database once for the insert to fail, and Hibernate flushes
         * the WHOLE persistence context on any saveAndFlush -- so the next unrelated write in
         * the caller (a payment, a session note) would drag this half-finished bill to the
         * database with it. Taking the status as a parameter means that state never exists.
         */
        bill.setStatus(status);
        // Updating the row bumps @Version, which is what a later stale checkout collides with,
        // and re-reads the generated business_date now that closed_at is set.
        billRepository.saveAndFlush(bill);
        return lines;
    }

    private void applyMethodRules(Payment payment, PaymentRequestDTO paymentRequestDTO,
                                  BigDecimal totalAmount, UUID actorId) {
        if (paymentRequestDTO.getMethod() == PaymentMethod.CASH) {
            if (paymentRequestDTO.getTendered() == null) {
                throw new BusinessRuleException("Cash payments require the amount tendered.");
            }
            if (paymentRequestDTO.getTendered().compareTo(totalAmount) < 0) {
                throw new BusinessRuleException("Tendered "
                        + paymentRequestDTO.getTendered().toPlainString() + " is less than the total "
                        + totalAmount.toPlainString() + ".");
            }
            if (paymentRequestDTO.getReferenceNo() != null) {
                throw new BusinessRuleException("A cash payment must not carry a reference number.");
            }
            payment.setTendered(paymentRequestDTO.getTendered());
            // Computed here, never accepted from the client.
            payment.setChangeGiven(paymentRequestDTO.getTendered().subtract(totalAmount));
            return;
        }

        if (paymentRequestDTO.getTendered() != null) {
            throw new BusinessRuleException("A digital payment must not carry a tendered amount.");
        }
        if (paymentRequestDTO.getReferenceNo() == null || paymentRequestDTO.getReferenceNo().isBlank()) {
            throw new BusinessRuleException("Digital payments require a reference number.");
        }

        // A warning, not a rejection: the same reference can legitimately repeat.
        if (paymentRepository.existsByReferenceNo(paymentRequestDTO.getReferenceNo())) {
            if (!Boolean.TRUE.equals(paymentRequestDTO.getDuplicateOverride())) {
                throw new DuplicateReferenceException(paymentRequestDTO.getReferenceNo());
            }
            payment.setDuplicateOverrideBy(actorId);
        }
        payment.setReferenceNo(paymentRequestDTO.getReferenceNo());
    }

    private List<String> checkoutBlockers(Bill bill) {
        List<String> blockers = new ArrayList<>();
        // UNSETTLED is payable: that is a debt waiting to be collected, and collecting it is
        // this same endpoint. Only OPEN and UNSETTLED can take money; VOIDED, MERGED and an
        // already-CLOSED bill cannot.
        if (bill.getStatus() != BillStatus.OPEN && bill.getStatus() != BillStatus.UNSETTLED) {
            blockers.add("Bill is already " + bill.getStatus() + ".");
        }
        // A running table has no final time charge yet, so settling it would undercharge.
        List<TableSession> live = tableSessionRepository.findByBillId(bill.getId())
                .stream()
                .filter(session -> session.getStatus() == SessionStatus.OPEN
                        || session.getStatus() == SessionStatus.PAUSED)
                .toList();
        if (!live.isEmpty()) {
            blockers.add("Close the table session before charging: " + live.size() + " still running.");
        }
        return blockers;
    }

    private BigDecimal sumLive(List<BillLine> lines, BillLineKind kind, boolean cost) {
        return lines.stream()
                .filter(line -> line.getVoidedAt() == null)
                .filter(line -> kind == null || line.getLineKind() == kind)
                .map(line -> {
                    BigDecimal value = cost ? line.getLineCost() : line.getLineTotal();
                    return value == null ? BigDecimal.ZERO : value;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /*
     * The frozen document, written once and never touched again — receipt is append-only by
     * trigger. It records what the customer was handed at the counter, which is why an unpaid
     * bill's receipt carries no payment block: nothing was paid, and inventing a blank one
     * would make the record say something that did not happen.
     *
     * `payment` is null on the unpaid path. `status` is written on BOTH paths rather than only
     * the unpaid one, so a reader answers "was this paid?" by reading a field rather than by
     * inferring it from a missing key — the inference that breaks the first time a payload
     * grows or loses an unrelated column.
     */
    private Map<String, Object> receiptPayload(Bill bill, List<BillLine> lines, Payment payment) {
        List<Map<String, Object>> linePayloads = new ArrayList<>();
        for (BillLine line : lines) {
            if (line.getVoidedAt() != null) {
                continue;
            }
            Map<String, Object> linePayload = new LinkedHashMap<>();
            linePayload.put("description", line.getDescription());
            linePayload.put("quantity", line.getQuantity());
            linePayload.put("unitPrice", line.getUnitPrice());
            linePayload.put("lineTotal", line.getLineTotal());
            linePayloads.add(linePayload);
        }

        // No cost anywhere: this is what the customer is shown.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("receiptNo", bill.getReceiptNo());
        payload.put("billId", bill.getId().toString());
        payload.put("status", bill.getStatus().name());
        payload.put("lines", linePayloads);
        payload.put("subtotalTime", bill.getSubtotalTime());
        payload.put("subtotalItems", bill.getSubtotalItems());
        /*
         * Written only when there was one, so an undiscounted chit reads exactly as it always
         * has and no reader has to explain a line saying "Discount 0.00".
         *
         * The customer should be able to see the 54 pesos they were given. The two subtotals
         * above are the bill before it, totalAmount below is the bill after it, and this is
         * the difference between them stated rather than left to be inferred -- a favour the
         * hall did somebody is worth printing.
         */
        if (bill.getDiscountAmount().signum() > 0) {
            payload.put("discountAmount", bill.getDiscountAmount());
            payload.put("discountReason", bill.getDiscountReason());
        }
        /*
         * The voucher line, on the same terms as the discount above: written only when there
         * was one, so an ordinary chit reads exactly as it always has.
         *
         * The code is printed because it is the customer's own -- they read it out at the
         * counter -- and because it is what makes the chit checkable against the giveaway if
         * anybody ever asks. The hours are stated as HOURS here and not minutes: the prize was
         * advertised in hours, and this is the one document the winner takes away.
         */
        if (bill.getVoucherAmount().signum() > 0) {
            payload.put("voucherCode", voucherCodeFor(bill.getVoucherId()));
            payload.put("voucherMinutesCovered", bill.getVoucherMinutesCovered());
            payload.put("voucherHoursCovered", hoursCovered(bill.getVoucherMinutesCovered()));
            payload.put("voucherAmount", bill.getVoucherAmount());
        }
        payload.put("totalAmount", bill.getTotalAmount());
        /*
         * Said on the chit, rather than left as an empty payment block.
         *
         * A receipt reading 0.00 with a dash where the method goes is the one a staff member
         * telephones about at midnight. The voucher line above already names the code; this
         * says the consequence -- there was nothing to pay -- and it is written from the FIGURE
         * rather than from the absence of a payment, so it means the same thing on a bill
         * zeroed by a comped flat rate as on one covered by a prize.
         */
        if (payment == null && bill.getStatus() == BillStatus.CLOSED
                && bill.getTotalAmount().signum() == 0) {
            payload.put("noCharge", true);
        }
        if (payment != null) {
            payload.put("method", payment.getMethod().name());
            payload.put("tendered", payment.getTendered());
            payload.put("changeGiven", payment.getChangeGiven());
            payload.put("referenceNo", payment.getReferenceNo());
        }
        return payload;
    }

    private PaymentResponseDTO toResponseDto(Payment payment, Long receiptNo, boolean replayed) {
        return new PaymentResponseDTO(
                payment.getId(),
                payment.getBillId(),
                payment.getMethod(),
                payment.getAmount(),
                payment.getTendered(),
                payment.getChangeGiven(),
                payment.getReferenceNo(),
                receiptNo,
                payment.getTakenAt(),
                payment.getBusinessDate(),
                payment.getDuplicateOverrideBy() != null,
                replayed);
    }

    private String usernameOf(UUID userId) {
        if (userId == null) {
            return null;
        }
        return appUserRepository.findById(userId).map(AppUser::getUsername).orElse("someone");
    }

    private String voucherCodeFor(UUID voucherId) {
        if (voucherId == null) {
            return null;
        }
        return voucherRepository.findById(voucherId)
                .map(voucher -> VoucherCodes.display(voucher.getCode()))
                .orElse(null);
    }

    // "1.5" for 90 minutes, "2" for 120. Trailing zeros dropped, because a receipt reading
    // "2.0 hours" is a receipt written by a program rather than by the hall.
    private String hoursCovered(Integer minutes) {
        if (minutes == null) {
            return null;
        }
        return BigDecimal.valueOf(minutes)
                .divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private Long receiptNoFor(UUID billId) {
        return receiptRepository.findByBillId(billId).map(Receipt::getReceiptNo).orElse(null);
    }

    private Bill requireBill(UUID id) {
        return billRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bill", id));
    }

    /*
     * The same lookup, holding the row lock, for the paths that go on to write.
     *
     * Kept separate from requireBill rather than folded into it: previewCheckout is
     * @Transactional(readOnly = true) and runs on every keystroke of the checkout screen, so
     * locking there would serialise readers against the till taking money.
     *
     * See BillRepository.findByIdForUpdate for why the lock exists and why the bill must be
     * locked before the branch.
     */
    private Bill requireBillForUpdate(UUID id) {
        return billRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bill", id));
    }
}
