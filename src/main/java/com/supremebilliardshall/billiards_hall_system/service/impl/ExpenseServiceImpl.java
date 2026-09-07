package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.expense.ExpenseRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.expense.ExpenseResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.expense.VoidExpenseRequestDTO;
import com.supremebilliardshall.billiards_hall_system.entity.AppUser;
import com.supremebilliardshall.billiards_hall_system.entity.Expense;
import com.supremebilliardshall.billiards_hall_system.entity.ExpenseCategory;
import com.supremebilliardshall.billiards_hall_system.exception.BusinessRuleException;
import com.supremebilliardshall.billiards_hall_system.exception.ResourceNotFoundException;
import com.supremebilliardshall.billiards_hall_system.mapper.ExpenseMapper;
import com.supremebilliardshall.billiards_hall_system.repository.AppUserRepository;
import com.supremebilliardshall.billiards_hall_system.repository.BranchRepository;
import com.supremebilliardshall.billiards_hall_system.repository.CashCountRepository;
import com.supremebilliardshall.billiards_hall_system.repository.ExpenseCategoryRepository;
import com.supremebilliardshall.billiards_hall_system.repository.ExpenseRepository;
import com.supremebilliardshall.billiards_hall_system.security.BranchContext;
import com.supremebilliardshall.billiards_hall_system.service.AuditService;
import com.supremebilliardshall.billiards_hall_system.service.ExpenseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ExpenseServiceImpl implements ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final CashCountRepository cashCountRepository;
    private final AppUserRepository appUserRepository;
    private final BranchRepository branchRepository;
    private final ExpenseMapper expenseMapper;
    private final BranchContext branchContext;
    private final AuditService auditService;

    public ExpenseServiceImpl(ExpenseRepository expenseRepository,
                              ExpenseCategoryRepository expenseCategoryRepository,
                              CashCountRepository cashCountRepository,
                              AppUserRepository appUserRepository,
                              BranchRepository branchRepository,
                              ExpenseMapper expenseMapper,
                              BranchContext branchContext,
                              AuditService auditService) {
        this.expenseRepository = expenseRepository;
        this.expenseCategoryRepository = expenseCategoryRepository;
        this.cashCountRepository = cashCountRepository;
        this.appUserRepository = appUserRepository;
        this.branchRepository = branchRepository;
        this.expenseMapper = expenseMapper;
        this.branchContext = branchContext;
        this.auditService = auditService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpenseResponseDTO> getExpenses(LocalDate businessDate) {
        LocalDate date = businessDate != null ? businessDate : branchRepository.currentBusinessDate();
        return expenseRepository.findByBusinessDate(date)
                .stream()
                .map(this::toResponseDto)
                .toList();
    }

    @Override
    @Transactional
    public ExpenseResponseDTO recordExpense(ExpenseRequestDTO expenseRequestDTO) {
        ExpenseCategory category = expenseCategoryRepository.findById(expenseRequestDTO.getExpenseCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Expense category", expenseRequestDTO.getExpenseCategoryId()));
        if (category.getArchivedAt() != null) {
            throw new BusinessRuleException("'" + category.getName()
                    + "' has been archived and can no longer be used for a new expense.");
        }

        // The night this lands on, decided by the database rather than by the JVM. The row's own
        // business_date is generated from incurred_at, so the two agree by construction.
        LocalDate businessDate = branchRepository.currentBusinessDate();
        requireDayOpen(businessDate, "recorded");

        Expense expense = expenseMapper.toEntity(expenseRequestDTO);
        expense.setBranchId(branchContext.getCurrentBranchId());
        expense.setIncurredAt(OffsetDateTime.now());
        expense.setRecordedBy(branchContext.getCurrentUserId());
        // Flushed so the generated business_date comes back; the response says which night the
        // money was spent on and only the database can answer that.
        Expense saved = expenseRepository.saveAndFlush(expense);

        auditService.record("EXPENSE_RECORDED", "expense", saved.getId(),
                null, auditSnapshot(saved, category), saved.getNote());

        return toResponseDto(saved);
    }

    @Override
    @Transactional
    public ExpenseResponseDTO voidExpense(UUID id, VoidExpenseRequestDTO voidExpenseRequestDTO) {
        Expense expense = expenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense", id));

        if (expense.getVoidedAt() != null) {
            throw new BusinessRuleException("That expense is already voided.");
        }
        // The night the expense belongs to, not tonight: an expense from a closed night cannot
        // be taken back by waiting until the next one.
        requireDayOpen(expense.getBusinessDate(), "voided");

        Map<String, Object> before = auditSnapshot(expense, categoryOf(expense));

        // Retained, never deleted: the original record of what was paid out stays and is
        // excluded from every total, exactly as a voided bill line is.
        expense.setVoidedAt(OffsetDateTime.now());
        expense.setVoidedBy(branchContext.getCurrentUserId());
        expense.setVoidReason(voidExpenseRequestDTO.getReason());
        Expense voided = expenseRepository.saveAndFlush(expense);

        auditService.record("EXPENSE_VOIDED", "expense", voided.getId(),
                before, auditSnapshot(voided, categoryOf(voided)), voidExpenseRequestDTO.getReason());

        return toResponseDto(voided);
    }

    /*
     * A closed night's arithmetic is finished.
     *
     * cash_expenses is frozen onto the count at the moment of counting, the same way cash_sales
     * is, so money moving in or out of the drawer after the day is signed off makes the recorded
     * variance describe a total that no longer exists. The recount is the route for that — it
     * re-reads both figures and reopens the day — and naming it here is the difference between a
     * refusal someone can act on and one they work around by writing it on paper.
     */
    private void requireDayOpen(LocalDate businessDate, String verb) {
        cashCountRepository.findByBusinessDate(businessDate)
                .filter(cashCount -> cashCount.getClosedAt() != null)
                .ifPresent(cashCount -> {
                    throw new BusinessRuleException(businessDate
                            + " is closed; an expense can no longer be " + verb
                            + " on it. Count the drawer again on the end-of-day screen to reopen "
                            + "the night, then record it.");
                });
    }

    private ExpenseCategory categoryOf(Expense expense) {
        return expenseCategoryRepository.findById(expense.getExpenseCategoryId()).orElse(null);
    }

    private ExpenseResponseDTO toResponseDto(Expense expense) {
        ExpenseCategory category = categoryOf(expense);
        return new ExpenseResponseDTO(
                expense.getId(),
                expense.getExpenseCategoryId(),
                category == null ? null : category.getName(),
                expense.getAmount(),
                expense.getNote(),
                expense.isPaidFromDrawer(),
                expense.getIncurredAt(),
                expense.getBusinessDate(),
                usernameOf(expense.getRecordedBy()),
                expense.getVoidedAt(),
                expense.getVoidedBy() == null ? null : usernameOf(expense.getVoidedBy()),
                expense.getVoidReason());
    }

    // The category name goes in by name, not by id: the owner reads this log months later and
    // an id tells him nothing, while an archived category's name still says what was bought.
    private Map<String, Object> auditSnapshot(Expense expense, ExpenseCategory category) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("category", category == null ? null : category.getName());
        snapshot.put("amount", expense.getAmount());
        snapshot.put("paidFromDrawer", expense.isPaidFromDrawer());
        snapshot.put("businessDate", expense.getBusinessDate());
        snapshot.put("note", expense.getNote());
        snapshot.put("voidedAt", expense.getVoidedAt());
        return snapshot;
    }

    private String usernameOf(UUID userId) {
        if (userId == null) return null;
        return appUserRepository.findById(userId).map(AppUser::getUsername).orElse("someone");
    }
}
