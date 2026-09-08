package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.expense.ExpenseCategoryRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.expense.ExpenseCategoryResponseDTO;

import java.util.List;
import java.util.UUID;

public interface ExpenseCategoryService {
    List<ExpenseCategoryResponseDTO> getAllExpenseCategories();

    ExpenseCategoryResponseDTO createExpenseCategory(ExpenseCategoryRequestDTO expenseCategoryRequestDTO);

    // Update Expense Category
    ExpenseCategoryResponseDTO updateExpenseCategory(UUID id, ExpenseCategoryRequestDTO expenseCategoryRequestDTO);

    // Archive Expense Category — never a hard delete, recorded expenses reference it
    void deleteExpenseCategory(UUID id);
}
