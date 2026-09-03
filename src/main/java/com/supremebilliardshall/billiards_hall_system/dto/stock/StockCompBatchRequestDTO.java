package com.supremebilliardshall.billiards_hall_system.dto.stock;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

// Several products given away in one act — a round for a table, a tray for the staff.
//
// One note covers the batch: it is one decision by one person, and asking for four identical
// reasons would get four blank-looking ones. It is written onto every movement so a single
// ledger row still explains itself.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockCompBatchRequestDTO {

    @NotEmpty(message = "A give-away must have at least one line")
    @Valid
    private List<StockCompLineRequestDTO> lines;

    @NotBlank(message = "A reason is required for a give-away")
    private String note;
}
