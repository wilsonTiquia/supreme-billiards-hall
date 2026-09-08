package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.expense.ExpenseRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.expense.ExpenseResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.expense.VoidExpenseRequestDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ExpenseService {
    // The day's expenses, voided ones included and flagged, newest first.
    List<ExpenseResponseDTO> getExpenses(LocalDate businessDate);

    ExpenseResponseDTO recordExpense(ExpenseRequestDTO expenseRequestDTO);

    // Retained and excluded from totals — never deleted, like a voided bill line.
    ExpenseResponseDTO voidExpense(UUID id, VoidExpenseRequestDTO voidExpenseRequestDTO);
}
