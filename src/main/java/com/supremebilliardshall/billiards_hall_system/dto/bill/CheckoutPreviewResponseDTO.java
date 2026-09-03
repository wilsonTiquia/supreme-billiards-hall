package com.supremebilliardshall.billiards_hall_system.dto.bill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

// A preview, and nothing else: reading this writes nothing. The bill inside carries the same
// role-aware shape as GET /bills/{id}, so an employee still sees no cost.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutPreviewResponseDTO {

    private BillResponseDTO bill;
    private boolean canCheckout;
    // What is stopping checkout, if anything: an open session, or a bill already settled.
    private List<String> blockers;
}
