package com.supremebilliardshall.billiards_hall_system.dto.expense;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseRequestDTO {

    @NotNull(message = "An expense category is required")
    private UUID expenseCategoryId;

    // Matches expense_amount_chk. A zero expense is a typo, and a negative one is a correction
    // that should be recorded by voiding the original.
    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be more than zero")
    @Digits(integer = 10, fraction = 2, message = "Amount must be a peso figure")
    private BigDecimal amount;

    @Size(max = 280, message = "Note must be at most 280 characters")
    private String note;

    // No default here on purpose: the client says which it was, and the checkbox on the form
    // is the one that carries the default. A silent false would quietly stop the drawer
    // arithmetic ever noticing a cash payout.
    @NotNull(message = "Say whether this was paid from the cash drawer")
    private Boolean paidFromDrawer;
}
