package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.PagedResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.bill.*;
import com.supremebilliardshall.billiards_hall_system.entity.*;
import com.supremebilliardshall.billiards_hall_system.exception.BusinessRuleException;
import com.supremebilliardshall.billiards_hall_system.exception.ResourceNotFoundException;
import com.supremebilliardshall.billiards_hall_system.repository.BillLineRepository;
import com.supremebilliardshall.billiards_hall_system.repository.BillRepository;
import com.supremebilliardshall.billiards_hall_system.repository.CustomerTypeRepository;
import com.supremebilliardshall.billiards_hall_system.repository.PoolTableRepository;
import com.supremebilliardshall.billiards_hall_system.repository.TableSessionRepository;
import com.supremebilliardshall.billiards_hall_system.security.BranchContext;
import com.supremebilliardshall.billiards_hall_system.service.AuditService;
import com.supremebilliardshall.billiards_hall_system.service.BillService;
import com.supremebilliardshall.billiards_hall_system.service.SessionNoteService;
import com.supremebilliardshall.billiards_hall_system.service.SessionService;
import com.supremebilliardshall.billiards_hall_system.service.StockService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class BillServiceImpl implements BillService {

    // A night's receipts should be readable on a screen, not downloaded. Same ceiling the
    // audit log uses.
    private static final int MAX_PAGE_SIZE = 200;

    private final BillRepository billRepository;
    private final BillLineRepository billLineRepository;
    private final CustomerTypeRepository customerTypeRepository;
    private final TableSessionRepository tableSessionRepository;
    private final PoolTableRepository poolTableRepository;
    private final StockService stockService;
    private final SessionService sessionService;
    private final SessionNoteService sessionNoteService;
    private final AuditService auditService;
    private final BranchContext branchContext;

    public BillServiceImpl(BillRepository billRepository,
                           BillLineRepository billLineRepository,
                           CustomerTypeRepository customerTypeRepository,
                           TableSessionRepository tableSessionRepository,
                           PoolTableRepository poolTableRepository,
                           StockService stockService,
                           SessionService sessionService,
                           SessionNoteService sessionNoteService,
                           AuditService auditService,
                           BranchContext branchContext) {
        this.billRepository = billRepository;
        this.billLineRepository = billLineRepository;
        this.customerTypeRepository = customerTypeRepository;
        this.tableSessionRepository = tableSessionRepository;
        this.poolTableRepository = poolTableRepository;
        this.stockService = stockService;
        this.sessionService = sessionService;
        this.sessionNoteService = sessionNoteService;
        this.auditService = auditService;
        this.branchContext = branchContext;
    }


    @Override
    @Transactional(readOnly = true)
    public BillResponseDTO getBill(UUID id) {
        return toResponseDto(requireBill(id));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponseDTO<BillSummaryResponseDTO> getSettledBills(LocalDate businessDate, int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        Page<BillRepository.BillSummaryProjection> results = billRepository.findSettledByBusinessDate(
                branchContext.getCurrentBranchId(), businessDate,
                PageRequest.of(Math.max(page, 0), pageSize));

        List<BillSummaryResponseDTO> content = results.getContent().stream()
                .map(row -> new BillSummaryResponseDTO(
                        row.getId(), row.getReceiptNo(),
                        row.getClosedAt().atOffset(ZoneOffset.UTC), row.getTotalAmount(),
                        PaymentMethod.valueOf(row.getMethod()), row.getTakenByUsername(),
                        row.getQuickSale()))
                .toList();

        return new PagedResponseDTO<>(content, results.getNumber(), results.getSize(),
                results.getTotalElements(), results.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UnsettledBillResponseDTO> getUnsettledBills() {
        // Every unsettled bill, newest first — not just tonight's. Each row carries its own
        // businessDate so an old one is visibly old on screen.
        return billRepository.findUnsettled().stream()
                .map(this::toUnsettledResponseDto)
                // A bill totalling zero cannot be settled at all: payment validation requires
                // at least 0.01, so it would sit in the floor strip for ever. It is also not a
                // lost sale — there is nothing to collect. Listing it would only teach staff
                // to ignore the strip, which is the one thing it cannot afford.
                .filter(unsettled -> unsettled.getTotalAmount().signum() > 0)
                .toList();
    }

    @Override
    @Transactional
    public AddBillLineResponseDTO addLine(UUID billId, AddBillLineRequestDTO addBillLineRequestDTO) {
        Bill bill = requireBill(billId);
        requireOpen(bill);

        // Locked before anything is read from it: the snapshot and the running balance must
        // both come from the same serialised view of this product.
        Product product = stockService.lockProduct(addBillLineRequestDTO.getProductId());
        if (product.getArchivedAt() != null) {
            throw new BusinessRuleException("Product '" + product.getName() + "' is archived.");
        }

        BillLine line = new BillLine();
        line.setBranchId(bill.getBranchId());
        line.setBillId(bill.getId());
        line.setLineKind(BillLineKind.PRODUCT);
        line.setSeq(billLineRepository.findMaxSeq(bill.getId()) + 1);
        line.setProductId(product.getId());
        // The three snapshots. Re-pricing or renaming this product tomorrow must not reach
        // back into tonight's bill, and never into last month's reported profit.
        line.setDescription(product.getName());
        line.setUnitPrice(product.getSellingPrice());
        line.setUnitCost(product.getAvgCost());
        line.setQuantity(addBillLineRequestDTO.getQuantity());
        line.setCreatedBy(branchContext.getCurrentUserId());
        // Flushed so the movement can reference it, and so line_total comes back computed.
        BillLine savedLine = billLineRepository.saveAndFlush(line);

        StockMovement movement = stockService.applyMovement(product, StockReason.SALE,
                addBillLineRequestDTO.getQuantity().negate(), null, savedLine.getId(), null, null);

        boolean belowZero = movement.getQtyAfter().compareTo(BigDecimal.ZERO) < 0;
        return new AddBillLineResponseDTO(
                toResponseDto(savedLine),
                belowZero,
                movement.getQtyAfter(),
                belowZero ? "Stock for '" + product.getName() + "' is now " + movement.getQtyAfter().toPlainString()
                        + ". The sale was recorded; correct the count when you can." : null);
    }

    @Override
    @Transactional
    public BillLineResponseDTO voidLine(UUID billId, UUID lineId, VoidBillLineRequestDTO voidBillLineRequestDTO) {
        Bill bill = requireBill(billId);
        requireOpen(bill);

        BillLine line = billLineRepository.findById(lineId)
                .orElseThrow(() -> new ResourceNotFoundException("Bill line", lineId));
        if (!line.getBillId().equals(bill.getId())) {
            throw new ResourceNotFoundException("Bill line", lineId);
        }
        if (line.getVoidedAt() != null) {
            throw new BusinessRuleException("That line is already voided.");
        }
        if (line.getLineKind() != BillLineKind.PRODUCT) {
            throw new BusinessRuleException("A time line is voided by voiding its session, not on its own.");
        }

        Map<String, Object> before = auditSnapshot(line);

        // Retained, never deleted: the original record of what was rung up stays on the bill
        // and is excluded from every total.
        line.setVoidedAt(OffsetDateTime.now());
        line.setVoidedBy(branchContext.getCurrentUserId());
        line.setVoidReason(voidBillLineRequestDTO.getReason());
        BillLine voidedLine = billLineRepository.saveAndFlush(line);

        Product product = stockService.lockProduct(line.getProductId());
        stockService.applyMovement(product, StockReason.SALE_VOID, line.getQuantity(),
                null, line.getId(), null, voidBillLineRequestDTO.getReason());

        auditService.record("BILL_LINE_VOIDED", "bill_line", line.getId(),
                before, auditSnapshot(voidedLine), voidBillLineRequestDTO.getReason());

        return toResponseDto(voidedLine);
    }


    // Looked up per bill rather than joined. The list is what staff forgot to settle tonight,
    // so it is a handful of rows at most, and the per-bill reads stay far easier to follow
    // than the multi-table aggregate that would replace them.
    private UnsettledBillResponseDTO toUnsettledResponseDto(Bill bill) {
        List<BillLine> lines = billLineRepository.findByBillId(bill.getId());
        BigDecimal totalAmount = sumLive(lines, BillLineKind.TIME)
                .add(sumLive(lines, BillLineKind.PRODUCT));

        List<TableSession> sessions = tableSessionRepository.findByBillId(bill.getId());

        List<String> tableNames = sessions.stream()
                .map(TableSession::getPoolTableId)
                .distinct()
                .map(poolTableId -> poolTableRepository.findById(poolTableId)
                        .map(PoolTable::getName)
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList();

        OffsetDateTime sessionEndedAt = sessions.stream()
                .map(TableSession::getClosedAt)
                .filter(Objects::nonNull)
                .max(OffsetDateTime::compareTo)
                .orElse(null);

        return new UnsettledBillResponseDTO(
                bill.getId(),
                bill.getOpenedAt(),
                sessionEndedAt,
                bill.getBusinessDate(),
                customerTypeName(bill.getCustomerTypeId()),
                tableNames,
                totalAmount,
                sessionNoteService.getLatestNoteForBill(bill.getId()));
    }

    private BillResponseDTO toResponseDto(Bill bill) {
        List<BillLine> lines = billLineRepository.findByBillId(bill.getId());

        BigDecimal subtotalTime = sumLive(lines, BillLineKind.TIME);
        BigDecimal subtotalItems = sumLive(lines, BillLineKind.PRODUCT);
        BigDecimal totalAmount = subtotalTime.add(subtotalItems);

        BillResponseDTO responseDto = branchContext.isAdmin()
                ? new BillAdminResponseDTO()
                : new BillResponseDTO();

        responseDto.setId(bill.getId());
        responseDto.setStatus(bill.getStatus());
        responseDto.setCustomerTypeId(bill.getCustomerTypeId());
        responseDto.setCustomerTypeName(customerTypeName(bill.getCustomerTypeId()));
        responseDto.setOpenedAt(bill.getOpenedAt());
        responseDto.setClosedAt(bill.getClosedAt());
        responseDto.setBusinessDate(bill.getBusinessDate());
        responseDto.setVersion(bill.getVersion());
        responseDto.setSubtotalTime(subtotalTime);
        responseDto.setSubtotalItems(subtotalItems);
        responseDto.setTotalAmount(totalAmount);
        responseDto.setLines(lines.stream().map(this::toResponseDto).toList());
        responseDto.setSessions(sessionService.getSummariesForBill(bill.getId()));

        if (responseDto instanceof BillAdminResponseDTO adminResponseDto) {
            BigDecimal totalCost = lines.stream()
                    .filter(line -> line.getVoidedAt() == null)
                    .map(line -> line.getLineCost() == null ? BigDecimal.ZERO : line.getLineCost())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            adminResponseDto.setTotalCost(totalCost);
            adminResponseDto.setGrossProfit(totalAmount.subtract(totalCost));
        }
        return responseDto;
    }

    // Employees must never receive a cost field, so the type differs rather than the value.
    private BillLineResponseDTO toResponseDto(BillLine line) {
        BillLineResponseDTO responseDto = branchContext.isAdmin()
                ? new BillLineAdminResponseDTO()
                : new BillLineResponseDTO();

        responseDto.setId(line.getId());
        responseDto.setLineKind(line.getLineKind());
        responseDto.setSeq(line.getSeq());
        responseDto.setProductId(line.getProductId());
        responseDto.setSessionId(line.getSessionId());
        responseDto.setDescription(line.getDescription());
        responseDto.setUnitPrice(line.getUnitPrice());
        responseDto.setQuantity(line.getQuantity());
        responseDto.setBilledMinutes(line.getBilledMinutes());
        responseDto.setLineTotal(line.getLineTotal());
        responseDto.setVoidedAt(line.getVoidedAt());
        responseDto.setVoidReason(line.getVoidReason());

        if (responseDto instanceof BillLineAdminResponseDTO adminResponseDto) {
            adminResponseDto.setUnitCost(line.getUnitCost());
            adminResponseDto.setLineCost(line.getLineCost());
        }
        return responseDto;
    }

    private BigDecimal sumLive(List<BillLine> lines, BillLineKind kind) {
        return lines.stream()
                .filter(line -> line.getVoidedAt() == null && line.getLineKind() == kind)
                .map(line -> line.getLineTotal() == null ? BigDecimal.ZERO : line.getLineTotal())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Bill requireBill(UUID id) {
        return billRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bill", id));
    }

    private void requireOpen(Bill bill) {
        if (bill.getStatus() != BillStatus.OPEN) {
            throw new BusinessRuleException("Bill is already " + bill.getStatus() + ".");
        }
    }

    private String customerTypeName(UUID customerTypeId) {
        if (customerTypeId == null) {
            return null;
        }
        return customerTypeRepository.findById(customerTypeId)
                .map(CustomerType::getName)
                .orElse(null);
    }

    private Map<String, Object> auditSnapshot(BillLine line) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("description", line.getDescription());
        snapshot.put("quantity", line.getQuantity());
        snapshot.put("unitPrice", line.getUnitPrice());
        snapshot.put("voidedAt", line.getVoidedAt());
        snapshot.put("voidReason", line.getVoidReason());
        return snapshot;
    }
}
