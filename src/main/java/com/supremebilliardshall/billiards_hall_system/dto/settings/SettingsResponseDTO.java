package com.supremebilliardshall.billiards_hall_system.dto.settings;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SettingsResponseDTO {

    // Zero means the hall keeps no float, and the drawer is expected to hold takings alone.
    private BigDecimal standardCashFloat;

    // Whether the checkout transition plays. Off is a perfectly good answer on a busy floor.
    private boolean checkoutAnimation;
}
