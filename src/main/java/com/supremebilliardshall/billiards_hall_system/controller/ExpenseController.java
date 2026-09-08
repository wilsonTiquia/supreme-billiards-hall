package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.expense.ExpenseRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.expense.ExpenseResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.expense.VoidExpenseRequestDTO;
import com.supremebilliardshall.billiards_hall_system.service.ExpenseService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/*
 * Open to EMPLOYEE as well as ADMIN, all three routes, and that is deliberate.
 *
 * An operating expense is not product cost and not profit — it carries no unit_cost, no margin
 * and no figure any of those can be derived from — so the rule that employees never see cost
 * does not reach it. What the counter does need is to read back the PHP 850 they just typed for
 * the water man, because the alternative to seeing it is recording it twice. One response type
 * serves both roles, the way UnsettledBillResponseDTO already does.
 */
@RestController
@RequestMapping("/api/v1/expenses")
public class ExpenseController {

    private final ExpenseService expenseService;

    public ExpenseController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    // No date means the night currently running, decided by the database.
    @GetMapping
    public ResponseEntity<APIResponse<List<ExpenseResponseDTO>>> getExpenses(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        List<ExpenseResponseDTO> expensesDto = expenseService.getExpenses(businessDate);
        return ResponseEntity.
                ok(APIResponse.success(
                        expensesDto,
                        "Expenses fetched successfully"));
    }

    @PostMapping
    public ResponseEntity<APIResponse<ExpenseResponseDTO>> recordExpense(@Valid @RequestBody ExpenseRequestDTO expenseRequestDTO) {
        ExpenseResponseDTO savedExpense = expenseService.recordExpense(expenseRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        savedExpense,
                        "Expense recorded successfully"));
    }

    @PostMapping("/{id}/void")
    public ResponseEntity<APIResponse<ExpenseResponseDTO>> voidExpense(@PathVariable UUID id,
                                                                       @Valid @RequestBody VoidExpenseRequestDTO voidExpenseRequestDTO) {
        ExpenseResponseDTO voidedExpense = expenseService.voidExpense(id, voidExpenseRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        voidedExpense,
                        "Expense voided successfully"));
    }

}
