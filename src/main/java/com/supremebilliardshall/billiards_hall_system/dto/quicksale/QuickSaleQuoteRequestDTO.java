package com.supremebilliardshall.billiards_hall_system.dto.quicksale;

import com.supremebilliardshall.billiards_hall_system.dto.bill.AddBillLineRequestDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

// The same lines the sale will be made from, priced but not sold.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuickSaleQuoteRequestDTO {

    @NotEmpty(message = "A quick sale must have at least one line")
    @Valid
    private List<AddBillLineRequestDTO> lines;
}
