package com.supremebilliardshall.billiards_hall_system.dto.bill;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoidBillLineRequestDTO {

    // Required. A void with no stated reason is not an audit trail, and the database rejects
    // it as well.
    @NotBlank(message = "Reason is required to void a line")
    private String reason;
}
