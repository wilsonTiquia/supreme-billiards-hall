package com.supremebilliardshall.billiards_hall_system.dto.auth;

import com.supremebilliardshall.billiards_hall_system.entity.UserRole;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CurrentUserResponseDTO {
    private UUID id;
    private String username;
    private String fullName;
    private UserRole role;
    private UUID branchId;
    private String branchName;

    /*
     * Whether the checkout transition should play, carried on the session rather than fetched
     * separately.
     *
     * The counter needs to know and is not an ADMIN, so it cannot read /settings. It already
     * asks who it is on load; one boolean rides along at no extra cost, and flipping the
     * setting reaches every till on its next sign-in or refresh with no restart and no deploy.
     */
    private boolean checkoutAnimation;
}
