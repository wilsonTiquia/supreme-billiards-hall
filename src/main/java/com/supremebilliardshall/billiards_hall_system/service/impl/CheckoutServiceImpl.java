package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.bill.AddBillLineRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.CheckoutPreviewResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.ReceiptResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.payment.PaymentRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.payment.PaymentResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.quicksale.QuickSaleQuoteLineDTO;
import com.supremebilliardshall.billiards_hall_system.dto.quicksale.QuickSaleQuoteRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.quicksale.QuickSaleQuoteResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.quicksale.QuickSaleRequestDTO;
import com.supremebilliardshall.billiards_hall_system.entity.*;
import com.supremebilliardshall.billiards_hall_system.exception.BusinessRuleException;
import com.supremebilliardshall.billiards_hall_system.exception.DuplicateReferenceException;
import com.supremebilliardshall.billiards_hall_system.exception.ResourceNotFoundException;
import com.supremebilliardshall.billiards_hall_system.exception.StaleBillVersionException;
import com.supremebilliardshall.billiards_hall_system.repository.*;
import com.supremebilliardshall.billiards_hall_system.security.BranchContext;
import com.supremebilliardshall.billiards_hall_system.service.BillService;
import com.supremebilliardshall.billiards_hall_system.service.CheckoutService;
import com.supremebilliardshall.billiards_hall_system.service.SessionNoteService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
    private final BillService billService;
    private final SessionNoteService sessionNoteService;
    private final BranchContext branchContext;

    public CheckoutServiceImpl(BillRepository billRepository,
                               BillLineRepository billLineRepository,
                               PaymentRepository paymentRepository,
                               ReceiptRepository receiptRepository,
                               BranchRepository branchRepository,
                               TableSessionRepository tableSessionRepository,
                               CustomerTypeRepository customerTypeRepository,
                               ProductRepository productRepository,
                               BillService billService,
                               SessionNoteService sessionNoteService,
                               BranchContext branchContext) {
        this.billRepository = billRepository;
        this.billLineRepository = billLineRepository;
        this.paymentRepository = paymentRepository;
        this.receiptRepository = receiptRepository;
        this.branchRepository = branchRepository;
        this.tableSessionRepository = tableSessionRepository;
        this.customerTypeRepository = customerTypeRepository;
        this.productRepository = productRepository;
        this.billService = billService;
        this.sessionNoteService = sessionNoteService;
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
        Bill bill = requireBill(billId);

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
    @Transactional(readOnly = true)
    public ReceiptResponseDTO getReceipt(UUID billId) {
        Receipt receipt = receiptRepository.findByBillId(billId)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt for bill", billId));
        return new ReceiptResponseDTO(receipt.getId(), receipt.getBillId(), receipt.getReceiptNo(),
                receipt.getIssuedAt(), receipt.getPayload());
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
        List<BillLine> lines = billLineRepository.findByBillId(bill.getId());
        BigDecimal subtotalTime = sumLive(lines, BillLineKind.TIME, false);
        BigDecimal subtotalItems = sumLive(lines, BillLineKind.PRODUCT, false);
        BigDecimal totalAmount = subtotalTime.add(subtotalItems);
        BigDecimal totalCost = sumLive(lines, null, true);

        if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleException("There is nothing to charge on this bill.");
        }
        // Exact, in both directions. Cash overpayment is expressed as tendered, never here.
        if (paymentRequestDTO.getAmount().compareTo(totalAmount) != 0) {
            throw new BusinessRuleException("Amount " + paymentRequestDTO.getAmount().toPlainString()
                    + " does not match the bill total " + totalAmount.toPlainString() + ".");
        }

        UUID actorId = branchContext.getCurrentUserId();
        Payment payment = new Payment();
        payment.setBranchId(bill.getBranchId());
        payment.setBillId(bill.getId());
        payment.setMethod(paymentRequestDTO.getMethod());
        payment.setAmount(totalAmount);
        payment.setIdempotencyKey(paymentRequestDTO.getIdempotencyKey());
        payment.setTakenBy(actorId);
        applyMethodRules(payment, paymentRequestDTO, totalAmount, actorId);

        // Allocated under a row lock, inside this transaction: two simultaneous checkouts
        // must not hand two customers the same receipt number.
        Branch branch = branchRepository.findByIdForUpdate(bill.getBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Branch", bill.getBranchId()));
        long receiptNo = branch.getNextReceiptNo();
        branch.setNextReceiptNo(receiptNo + 1);
        branchRepository.saveAndFlush(branch);

        Payment savedPayment = paymentRepository.saveAndFlush(payment);

        bill.setSubtotalTime(subtotalTime);
        bill.setSubtotalItems(subtotalItems);
        bill.setTotalAmount(totalAmount);
        bill.setTotalCost(totalCost);
        bill.setReceiptNo(receiptNo);
        bill.setStatus(BillStatus.CLOSED);
        bill.setClosedAt(OffsetDateTime.now());
        bill.setClosedBy(actorId);
        // Updating the row bumps @Version, which is what a later stale checkout collides with.
        billRepository.saveAndFlush(bill);

        // Closes the thread that the unpaid card opened: who collected, when, and by what
        // method, alongside the names of who owed it. Written by the server and marked SYSTEM,
        // so it cannot be produced by typing the same sentence into the note box.
        sessionNoteService.recordSettlement(savedPayment);

        Receipt receipt = new Receipt();
        receipt.setBranchId(bill.getBranchId());
        receipt.setBillId(bill.getId());
        receipt.setReceiptNo(receiptNo);
        receipt.setPayload(receiptPayload(bill, lines, savedPayment, receiptNo, subtotalTime, subtotalItems, totalAmount));
        receiptRepository.saveAndFlush(receipt);

        return toResponseDto(savedPayment, receiptNo, false);
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
        if (bill.getStatus() != BillStatus.OPEN) {
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

    private Map<String, Object> receiptPayload(Bill bill, List<BillLine> lines, Payment payment,
                                               long receiptNo, BigDecimal subtotalTime,
                                               BigDecimal subtotalItems, BigDecimal totalAmount) {
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
        payload.put("receiptNo", receiptNo);
        payload.put("billId", bill.getId().toString());
        payload.put("lines", linePayloads);
        payload.put("subtotalTime", subtotalTime);
        payload.put("subtotalItems", subtotalItems);
        payload.put("totalAmount", totalAmount);
        payload.put("method", payment.getMethod().name());
        payload.put("tendered", payment.getTendered());
        payload.put("changeGiven", payment.getChangeGiven());
        payload.put("referenceNo", payment.getReferenceNo());
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

    private Long receiptNoFor(UUID billId) {
        return receiptRepository.findByBillId(billId).map(Receipt::getReceiptNo).orElse(null);
    }

    private Bill requireBill(UUID id) {
        return billRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bill", id));
    }
}
