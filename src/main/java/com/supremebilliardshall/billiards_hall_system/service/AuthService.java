package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.auth.CurrentUserResponseDTO;

import java.util.UUID;

public interface AuthService {
    // Stamps last_login_at and returns the caller's identity, role and branch.
    CurrentUserResponseDTO recordLogin(UUID userId);

    CurrentUserResponseDTO getCurrentUser();

    // Sets the acting branch for a global admin, for the rest of the session.
    CurrentUserResponseDTO selectBranch(UUID branchId);
}
