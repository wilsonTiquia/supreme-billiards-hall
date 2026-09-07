package com.supremebilliardshall.billiards_hall_system.dto.expense;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoidExpenseRequestDTO {

    // Required. A void with no stated reason is not an audit trail, and the database rejects
    // it as well.
    @NotBlank(message = "Reason is required to void an expense")
    private String reason;
}
