package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.expense.ExpenseCategoryRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.expense.ExpenseCategoryResponseDTO;
import com.supremebilliardshall.billiards_hall_system.service.ExpenseCategoryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/expense-categories")
public class ExpenseCategoryController {

    private final ExpenseCategoryService expenseCategoryService;

    public ExpenseCategoryController(ExpenseCategoryService expenseCategoryService) {
        this.expenseCategoryService = expenseCategoryService;
    }

    // Readable by anyone authenticated: the counter needs the dropdown to record an expense.
    // Only writing the vocabulary is the owner's.
    @GetMapping
    public ResponseEntity<APIResponse<List<ExpenseCategoryResponseDTO>>> getAll() {
        List<ExpenseCategoryResponseDTO> expenseCategoriesDto = expenseCategoryService.getAllExpenseCategories();
        return ResponseEntity.
                ok(APIResponse.success(
                        expenseCategoriesDto,
                        "All Expense Categories fetched successfully"));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<ExpenseCategoryResponseDTO>> createExpenseCategory(@Valid @RequestBody ExpenseCategoryRequestDTO expenseCategoryRequestDTO) {
        ExpenseCategoryResponseDTO savedExpenseCategory = expenseCategoryService.createExpenseCategory(expenseCategoryRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        savedExpenseCategory,
                        "Expense category created successfully"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<ExpenseCategoryResponseDTO>> updateExpenseCategory(@PathVariable UUID id, @Valid @RequestBody ExpenseCategoryRequestDTO expenseCategoryRequestDTO) {
        ExpenseCategoryResponseDTO updatedExpenseCategory = expenseCategoryService.updateExpenseCategory(id, expenseCategoryRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        updatedExpenseCategory,
                        "Expense category updated successfully"));
    }


    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<ExpenseCategoryResponseDTO>> deleteExpenseCategory(@PathVariable UUID id) {
        expenseCategoryService.deleteExpenseCategory(id);
        return ResponseEntity.ok(
                APIResponse.success(null, "Expense category archived successfully with id: " + id)
        );
    }

}
