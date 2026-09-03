package com.supremebilliardshall.billiards_hall_system.dto.quicksale;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

// A quote, and nothing else: reading this writes nothing, moves no stock and takes no money.
// It exists so the counter screen never has to multiply a price by a quantity itself — the
// amount it then sends back must equal this total exactly, and both come from here.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuickSaleQuoteResponseDTO {

    private List<QuickSaleQuoteLineDTO> lines;
    private BigDecimal total;
}
