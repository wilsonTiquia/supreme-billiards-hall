package com.supremebilliardshall.billiards_hall_system.dto.expense;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseCategoryRequestDTO {

    @NotBlank(message = "Expense category name is required")
    @Size(max = 100, message = "Expense category name must be at most 100 characters")
    private String name;

    private Integer sortOrder;
}
