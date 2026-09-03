package com.supremebilliardshall.billiards_hall_system.dto.stock;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockDeliveryRequestDTO {

    private String supplierName;

    private String reference;

    private String note;

    @NotEmpty(message = "A delivery must have at least one line")
    @Valid
    private List<StockDeliveryLineRequestDTO> lines;
}
