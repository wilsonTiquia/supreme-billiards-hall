package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.businessday.BusinessDayResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.businessday.CashCountRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.businessday.CashCountUpdateRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.businessday.CashCountResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.businessday.UncountedDayDTO;
import com.supremebilliardshall.billiards_hall_system.dto.pooltable.TableSessionSummaryDTO;
import com.supremebilliardshall.billiards_hall_system.dto.session.SessionResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.Branch;
import com.supremebilliardshall.billiards_hall_system.entity.AppUser;
import com.supremebilliardshall.billiards_hall_system.entity.CashCount;
import com.supremebilliardshall.billiards_hall_system.exception.BusinessRuleException;
import com.supremebilliardshall.billiards_hall_system.repository.BranchRepository;
import com.supremebilliardshall.billiards_hall_system.repository.BranchSettingRepository;
import com.supremebilliardshall.billiards_hall_system.repository.AppUserRepository;
import com.supremebilliardshall.billiards_hall_system.repository.CashCountRepository;
import com.supremebilliardshall.billiards_hall_system.repository.ExpenseRepository;
import com.supremebilliardshall.billiards_hall_system.repository.PaymentRepository;
import com.supremebilliardshall.billiards_hall_system.security.BranchContext;
import com.supremebilliardshall.billiards_hall_system.service.AuditService;
import com.supremebilliardshall.billiards_hall_system.service.BusinessDayService;
import com.supremebilliardshall.billiards_hall_system.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BusinessDayServiceImpl implements BusinessDayService {

    private static final Logger log = LoggerFactory.getLogger(BusinessDayServiceImpl.class);

    // Asia/Manila has been a fixed +08:00 with no DST since 1978, the same assumption
    // business_date_of() makes in the schema.
    private static final ZoneOffset MANILA = ZoneOffset.ofHours(8);
    private static final int BUSINESS_DAY_END_HOUR = 5;

    // The change float the drawer is supposed to start every night with. Absent or unset means
    // zero, which is the honest default: a hall that keeps no float configures nothing and the
    // arithmetic is exactly what it was before the float existed.
    static final String STANDARD_CASH_FLOAT_KEY = "standard_cash_float";

    private final CashCountRepository cashCountRepository;
    private final AppUserRepository appUserRepository;
    private final PaymentRepository paymentRepository;
    private final ExpenseRepository expenseRepository;
    private final BranchRepository branchRepository;
    private final BranchSettingRepository branchSettingRepository;
    private final SessionService sessionService;
    private final AuditService auditService;
    private final BranchContext branchContext;

    public BusinessDayServiceImpl(CashCountRepository cashCountRepository,
                                  AppUserRepository appUserRepository,
                                  PaymentRepository paymentRepository,
                                  ExpenseRepository expenseRepository,
                                  BranchRepository branchRepository,
                                  BranchSettingRepository branchSettingRepository,
                                  SessionService sessionService,
                                  AuditService auditService,
                                  BranchContext branchContext) {
        this.cashCountRepository = cashCountRepository;
        this.appUserRepository = appUserRepository;
        this.paymentRepository = paymentRepository;
        this.expenseRepository = expenseRepository;
        this.branchRepository = branchRepository;
        this.branchSettingRepository = branchSettingRepository;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.branchContext = branchContext;
    }


    @Override
    @Transactional(readOnly = true)
    public BusinessDayResponseDTO getCurrentBusinessDay() {
        return getOpenSessions(branchRepository.currentBusinessDate());
    }

    @Override
    @Transactional(readOnly = true)
    public BusinessDayResponseDTO getOpenSessions(LocalDate businessDate) {
        List<TableSessionSummaryDTO> open = List.copyOf(
                sessionService.getLiveSessionSummariesByTable().values());
        return new BusinessDayResponseDTO(businessDate, open.isEmpty(), open,
                OffsetDateTime.now(), standardCashFloat());
    }

    @Override
    @Transactional(readOnly = true)
    public CashCountResponseDTO getCashCount(LocalDate businessDate) {
        return cashCountRepository.findByBusinessDate(businessDate)
                .map(this::toResponseDto)
                .orElse(null);
    }

    @Override
    @Transactional
    public CashCountResponseDTO recordCashCount(LocalDate businessDate, CashCountRequestDTO cashCountRequestDTO) {
        if (cashCountRepository.findByBusinessDate(businessDate).isPresent()) {
            throw new BusinessRuleException("The drawer has already been counted for " + businessDate + ".");
        }

        // Frozen at the moment of counting, and always the server's figure: a variance the
        // client could influence would not be a control at all.
        BigDecimal cashSales = paymentRepository.sumCashForBusinessDate(businessDate);

        // What was paid out of the till tonight, frozen the same way and for the same reason.
        // Server-computed: an expense figure the client could propose would let anyone explain
        // away a shortfall by claiming they had bought something.
        BigDecimal cashExpenses = expenseRepository.sumPaidFromDrawer(businessDate);

        // The float is the one figure the client may propose, and only because the drawer is a
        // physical object the server cannot see. Whether that proposal counts as an override is
        // still decided here, against the standard — the client does not get to say "this was
        // normal" about a night that was not.
        BigDecimal standardFloat = standardCashFloat();
        BigDecimal openingFloat = cashCountRequestDTO.getOpeningFloat() == null
                ? standardFloat
                : cashCountRequestDTO.getOpeningFloat();
        boolean overridden = openingFloat.compareTo(standardFloat) != 0;

        CashCount cashCount = new CashCount();
        cashCount.setBranchId(branchContext.getCurrentBranchId());
        cashCount.setBusinessDate(businessDate);
        cashCount.setCashSales(cashSales);
        cashCount.setCashExpenses(cashExpenses);
        cashCount.setOpeningFloat(openingFloat);
        cashCount.setFloatOverridden(overridden);
        cashCount.setCountedCash(cashCountRequestDTO.getCountedCash());
        cashCount.setCountedBy(branchContext.getCurrentUserId());
        cashCount.setNote(cashCountRequestDTO.getNote());
        CashCount saved = cashCountRepository.saveAndFlush(cashCount);

        // Only the unusual night is logged. Recording every count would bury the one that
        // matters under three hundred that say "the float was the float".
        if (overridden) {
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("standardFloat", standardFloat);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("openingFloat", saved.getOpeningFloat());
            after.put("cashExpenses", saved.getCashExpenses());
            after.put("expectedCash", expectedCash(saved));
            after.put("variance", saved.getVariance());
            auditService.record("CASH_FLOAT_OVERRIDDEN", "cash_count", saved.getId(),
                    before, after, cashCountRequestDTO.getNote());
        }

        return toResponseDto(saved);
    }

    /*
     * The branch standard, or zero.
     *
     * Zero is not a fallback for a missing configuration so much as the correct answer for a
     * hall that keeps no float — which is why an unset key is not an error here.
     */
    private BigDecimal standardCashFloat() {
        return branchSettingRepository.findValueByKey(STANDARD_CASH_FLOAT_KEY)
                .map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
    }

    @Override
    @Transactional
    public CashCountResponseDTO updateCashCount(LocalDate businessDate,
                                                CashCountUpdateRequestDTO cashCountUpdateRequestDTO) {
        CashCount cashCount = cashCountRepository.findByBusinessDate(businessDate)
                .orElseThrow(() -> new BusinessRuleException(
                        "The drawer has not been counted for " + businessDate + " yet."));

        // Once the night is signed off the figure stands. Reopening it would turn the count
        // into something that can be edited after the fact, which is the opposite of a control.
        if (cashCount.getClosedAt() != null) {
            throw new BusinessRuleException(businessDate
                    + " is closed; the drawer count can no longer be corrected.");
        }

        BigDecimal previousCounted = cashCount.getCountedCash();
        BigDecimal previousFloat = cashCount.getOpeningFloat();
        BigDecimal correctedFloat = cashCountUpdateRequestDTO.getOpeningFloat() == null
                ? previousFloat
                : cashCountUpdateRequestDTO.getOpeningFloat();

        // Unchanged on both figures is still nothing to correct. Checked across both so that
        // fixing only the float is a legitimate correction rather than a 409.
        if (previousCounted.compareTo(cashCountUpdateRequestDTO.getCountedCash()) == 0
                && previousFloat.compareTo(correctedFloat) == 0) {
            throw new BusinessRuleException("That is already the counted figure.");
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("countedCash", previousCounted);
        before.put("openingFloat", previousFloat);
        before.put("cashExpenses", cashCount.getCashExpenses());
        before.put("variance", cashCount.getVariance());

        cashCount.setCountedCash(cashCountUpdateRequestDTO.getCountedCash());
        cashCount.setOpeningFloat(correctedFloat);
        cashCount.setFloatOverridden(correctedFloat.compareTo(standardCashFloat()) != 0);
        if (cashCountUpdateRequestDTO.getNote() != null) {
            cashCount.setNote(cashCountUpdateRequestDTO.getNote());
        }
        // Flushed so the generated variance column comes back recomputed; the entity is
        // annotated to read it back on update as well as insert.
        CashCount saved = cashCountRepository.saveAndFlush(cashCount);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("countedCash", saved.getCountedCash());
        after.put("openingFloat", saved.getOpeningFloat());
        after.put("cashExpenses", saved.getCashExpenses());
        after.put("variance", saved.getVariance());

        // The original figure is never destroyed: it lives here, which is this project's rule
        // for every correction.
        auditService.record("CASH_COUNT_CORRECTED", "cash_count", saved.getId(),
                before, after, cashCountUpdateRequestDTO.getNote());

        return toResponseDto(saved);
    }

    /*
     * Counting a night again after it was closed and then traded on.
     *
     * Not admin-only, deliberately. The shift that closed up at 03:00 and then served a
     * straggler has to be able to finish the night themselves; making them wake the owner to
     * reconcile a beer would mean the drawer simply never gets recounted.
     *
     * The original count is not destroyed — it goes to the audit log with both figures — and
     * the day reopens so the ordinary close runs again and records who signed it off the
     * second time.
     */
    @Override
    @Transactional
    public CashCountResponseDTO recountAfterClose(LocalDate businessDate,
                                                  CashCountRequestDTO cashCountRequestDTO) {
        CashCount cashCount = cashCountRepository.findByBusinessDate(businessDate)
                .orElseThrow(() -> new BusinessRuleException(
                        "The drawer has not been counted for " + businessDate + " yet."));

        if (cashCount.getClosedAt() == null) {
            throw new BusinessRuleException(businessDate + " is not closed. Correct the count "
                    + "instead of recounting it.");
        }

        PaymentRepository.AfterCloseProjection after = paymentRepository.findActivityAfter(
                cashCount.getBranchId(), businessDate, cashCount.getClosedAt());
        // A payout after the close moves the drawer exactly as a sale does, so it is equally a
        // reason to count again. Without this the night that was closed and then paid the water
        // man would have no way back.
        ExpenseRepository.AfterCloseProjection afterExpenses = expenseRepository.findCashExpensesAfter(
                cashCount.getBranchId(), businessDate, cashCount.getClosedAt());
        if (after.getSales() == 0 && afterExpenses.getExpenses() == 0) {
            throw new BusinessRuleException("Nothing has been sold or paid out on " + businessDate
                    + " since it was closed, so the count still stands.");
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("cashSales", cashCount.getCashSales());
        before.put("openingFloat", cashCount.getOpeningFloat());
        before.put("cashExpenses", cashCount.getCashExpenses());
        before.put("countedCash", cashCount.getCountedCash());
        before.put("variance", cashCount.getVariance());
        before.put("closedAt", cashCount.getClosedAt().toString());
        before.put("closedBy", usernameOf(cashCount.getClosedBy()));
        before.put("salesAfterClose", after.getSales());
        before.put("amountAfterClose", after.getAmount());
        before.put("expensesAfterClose", afterExpenses.getExpenses());
        before.put("cashExpensesAfterClose", afterExpenses.getAmount());

        // Recomputed from the payments as they stand now, exactly as the first count was.
        // Only the takings are re-read. The float went into the drawer once, at open, and a
        // straggler served at 03:30 does not change what was put in at 10:00.
        cashCount.setCashSales(paymentRepository.sumCashForBusinessDate(businessDate));
        cashCount.setCashExpenses(expenseRepository.sumPaidFromDrawer(businessDate));
        cashCount.setCountedCash(cashCountRequestDTO.getCountedCash());
        cashCount.setCountedBy(branchContext.getCurrentUserId());
        if (cashCountRequestDTO.getNote() != null) {
            cashCount.setNote(cashCountRequestDTO.getNote());
        }
        // Reopened: the day has to be closed again, by whoever does it, on the new figures.
        cashCount.setClosedAt(null);
        cashCount.setClosedBy(null);
        CashCount saved = cashCountRepository.saveAndFlush(cashCount);

        Map<String, Object> afterState = new LinkedHashMap<>();
        afterState.put("cashSales", saved.getCashSales());
        afterState.put("cashExpenses", saved.getCashExpenses());
        afterState.put("countedCash", saved.getCountedCash());
        afterState.put("variance", saved.getVariance());
        afterState.put("closedAt", null);

        auditService.record("CASH_COUNT_SUPERSEDED", "cash_count", saved.getId(),
                before, afterState, cashCountRequestDTO.getNote());

        return toResponseDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UncountedDayDTO> getUncountedDays() {
        return cashCountRepository.findUncountedDaysBefore(branchContext.getCurrentBranchId())
                .stream()
                .map(row -> new UncountedDayDTO(row.getBusinessDate(), row.getBills()))
                .toList();
    }

    @Override
    @Transactional
    public BusinessDayResponseDTO closeBusinessDay(LocalDate businessDate) {
        BusinessDayResponseDTO day = getOpenSessions(businessDate);
        if (!day.isCanClose()) {
            // Names the tables, not the customer types: "still open on [Regular, Regular]"
            // tells the closer nothing about where to walk.
            throw new BusinessRuleException("Cannot close " + businessDate + ": "
                    + day.getOpenSessions().size() + " session(s) still open on "
                    + String.join(", ", day.getOpenSessions().stream()
                        .map(TableSessionSummaryDTO::getPoolTableName)
                        .toList()) + ". Close them first.");
        }
        // The drawer count is the one control carried over from the paper process, so a day
        // does not close without it. That is also why the count row carries the close: it is
        // the only row guaranteed to exist for a closed day.
        CashCount cashCount = cashCountRepository.findByBusinessDate(businessDate)
                .orElseThrow(() -> new BusinessRuleException(
                        "Cannot close " + businessDate + ": the drawer has not been counted."));

        if (cashCount.getClosedAt() != null) {
            throw new BusinessRuleException(businessDate + " was already closed at "
                    + cashCount.getClosedAt() + " by " + usernameOf(cashCount.getClosedBy()) + ".");
        }

        cashCount.setClosedAt(OffsetDateTime.now());
        cashCount.setClosedBy(branchContext.getCurrentUserId());
        cashCountRepository.saveAndFlush(cashCount);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("businessDate", businessDate.toString());
        after.put("openingFloat", cashCount.getOpeningFloat());
        after.put("cashSales", cashCount.getCashSales());
        after.put("cashExpenses", cashCount.getCashExpenses());
        after.put("expectedCash", expectedCash(cashCount));
        after.put("countedCash", cashCount.getCountedCash());
        after.put("variance", cashCount.getVariance());
        auditService.record("BUSINESS_DAY_CLOSED", "cash_count", cashCount.getId(),
                null, after, "End of day close");

        return day;
    }

    // The safety net. Sessions left open at the end of the night are closed capped at the
    // business-day boundary rather than at whatever time someone notices the next morning.
    @Scheduled(cron = "0 0 5 * * *", zone = "Asia/Manila")
    public void autoCloseAtEndOfBusinessDay() {
        OffsetDateTime cutoff = OffsetDateTime.now(MANILA)
                .withHour(BUSINESS_DAY_END_HOUR).withMinute(0).withSecond(0).withNano(0);

        for (Branch branch : branchRepository.findByIsActiveTrue()) {
            List<SessionResponseDTO> closed = branchContext.runAsSystem(branch.getId(),
                    () -> sessionService.autoCloseOpenSessions(cutoff));
            if (!closed.isEmpty()) {
                log.warn("Auto-closed {} session(s) in branch {} at business-day end {}; each is flagged needs_review",
                        closed.size(), branch.getCode(), cutoff);
            }
        }
    }

    private CashCountResponseDTO toResponseDto(CashCount cashCount) {
        CashCountResponseDTO dto = new CashCountResponseDTO(
                cashCount.getId(),
                cashCount.getBusinessDate(),
                cashCount.getOpeningFloat(),
                cashCount.getCashSales(),
                cashCount.getCashExpenses(),
                expectedCash(cashCount),
                cashCount.isFloatOverridden(),
                cashCount.getCountedCash(),
                cashCount.getVariance(),
                cashCount.getCountedAt(),
                cashCount.getNote(),
                cashCount.getClosedAt(),
                cashCount.getClosedBy() == null ? null : usernameOf(cashCount.getClosedBy()),
                0, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO);

        // Only meaningful once the day is signed off: before that, everything is "after the
        // last thing that happened" and nothing is superseded.
        if (cashCount.getClosedAt() != null) {
            PaymentRepository.AfterCloseProjection after = paymentRepository.findActivityAfter(
                    cashCount.getBranchId(), cashCount.getBusinessDate(), cashCount.getClosedAt());
            dto.setSalesAfterClose(after.getSales());
            dto.setAmountAfterClose(after.getAmount());
            dto.setCashAfterClose(after.getCash());

            ExpenseRepository.AfterCloseProjection afterExpenses = expenseRepository.findCashExpensesAfter(
                    cashCount.getBranchId(), cashCount.getBusinessDate(), cashCount.getClosedAt());
            dto.setExpensesAfterClose(afterExpenses.getExpenses());
            dto.setCashExpensesAfterClose(afterExpenses.getAmount());
        }
        return dto;
    }

    /*
     * What the drawer should have held: the float that went in, plus the takings, less what was
     * paid out of it.
     *
     * Computed here rather than read off the row because nothing stores it — the database stores
     * the three components and the variance, which is the right split: a stored expected_cash
     * would be a fourth number that can disagree with the other three. Every caller that shows
     * or logs the figure comes through here, so they cannot drift.
     */
    private BigDecimal expectedCash(CashCount cashCount) {
        return cashCount.getCashSales()
                .add(cashCount.getOpeningFloat())
                .subtract(cashCount.getCashExpenses());
    }

    private String usernameOf(UUID userId) {
        if (userId == null) return null;
        return appUserRepository.findById(userId).map(AppUser::getUsername).orElse("someone");
    }
}
