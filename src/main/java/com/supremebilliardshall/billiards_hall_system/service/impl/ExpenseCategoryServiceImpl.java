package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.expense.ExpenseCategoryRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.expense.ExpenseCategoryResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.ExpenseCategory;
import com.supremebilliardshall.billiards_hall_system.exception.DuplicateResourceException;
import com.supremebilliardshall.billiards_hall_system.exception.ResourceNotFoundException;
import com.supremebilliardshall.billiards_hall_system.mapper.ExpenseCategoryMapper;
import com.supremebilliardshall.billiards_hall_system.repository.ExpenseCategoryRepository;
import com.supremebilliardshall.billiards_hall_system.security.BranchContext;
import com.supremebilliardshall.billiards_hall_system.service.AuditService;
import com.supremebilliardshall.billiards_hall_system.service.ExpenseCategoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class ExpenseCategoryServiceImpl implements ExpenseCategoryService {

    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final ExpenseCategoryMapper expenseCategoryMapper;
    private final BranchContext branchContext;
    private final AuditService auditService;

    public ExpenseCategoryServiceImpl(ExpenseCategoryRepository expenseCategoryRepository,
                                      ExpenseCategoryMapper expenseCategoryMapper,
                                      BranchContext branchContext,
                                      AuditService auditService) {
        this.expenseCategoryRepository = expenseCategoryRepository;
        this.expenseCategoryMapper = expenseCategoryMapper;
        this.branchContext = branchContext;
        this.auditService = auditService;
    }


    @Override
    @Transactional(readOnly = true)
    public List<ExpenseCategoryResponseDTO> getAllExpenseCategories() {
        return expenseCategoryRepository.findAllActive()
                .stream()
                .map(expenseCategoryMapper::toResponseDto)
                .toList();
    }

    @Override
    @Transactional
    public ExpenseCategoryResponseDTO createExpenseCategory(ExpenseCategoryRequestDTO expenseCategoryRequestDTO) {
        boolean exists = expenseCategoryRepository.existsByName(expenseCategoryRequestDTO.getName());
        if (exists) {
            throw new DuplicateResourceException("Expense category with name '" + expenseCategoryRequestDTO.getName() + "' already exists.");
        }

        ExpenseCategory expenseCategory = expenseCategoryMapper.toEntity(expenseCategoryRequestDTO);
        expenseCategory.setBranchId(branchContext.getCurrentBranchId());
        if (expenseCategory.getSortOrder() == null) {
            expenseCategory.setSortOrder(0);
        }

        ExpenseCategory saved = expenseCategoryRepository.save(expenseCategory);
        auditService.record("EXPENSE_CATEGORY_CREATED", "expense_category", saved.getId(),
                null, auditSnapshot(saved), null);
        return expenseCategoryMapper.toResponseDto(saved);
    }

    @Override
    @Transactional
    public ExpenseCategoryResponseDTO updateExpenseCategory(UUID id, ExpenseCategoryRequestDTO expenseCategoryRequestDTO) {
        ExpenseCategory existing = expenseCategoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense category", id));

        boolean existingName = expenseCategoryRepository.
                existsByNameAndIdNot(expenseCategoryRequestDTO.getName(), id);

        if (existingName) {
            throw new DuplicateResourceException("Expense category with name '" + expenseCategoryRequestDTO.getName() + "' already exists.");
        }

        Map<String, Object> before = auditSnapshot(existing);
        Integer sortOrder = existing.getSortOrder();
        expenseCategoryMapper.updateEntityFromDto(expenseCategoryRequestDTO, existing);
        if (existing.getSortOrder() == null) {
            existing.setSortOrder(sortOrder);
        }

        ExpenseCategory updated = expenseCategoryRepository.save(existing);
        auditService.record("EXPENSE_CATEGORY_UPDATED", "expense_category", updated.getId(),
                before, auditSnapshot(updated), null);
        return expenseCategoryMapper.toResponseDto(updated);
    }

    @Override
    @Transactional
    public void deleteExpenseCategory(UUID id) {

        ExpenseCategory expenseCategory = expenseCategoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense category", id));
        Map<String, Object> before = auditSnapshot(expenseCategory);
        expenseCategory.setArchivedAt(OffsetDateTime.now());
        ExpenseCategory archived = expenseCategoryRepository.save(expenseCategory);
        auditService.record("EXPENSE_CATEGORY_ARCHIVED", "expense_category", archived.getId(),
                before, auditSnapshot(archived), null);
    }

    // The same shape every other audited row uses: what a reader needs to see what changed.
    private Map<String, Object> auditSnapshot(ExpenseCategory expenseCategory) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", expenseCategory.getName());
        snapshot.put("sortOrder", expenseCategory.getSortOrder());
        snapshot.put("archivedAt", expenseCategory.getArchivedAt());
        return snapshot;
    }
}
