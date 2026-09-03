package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.auth.ChangePasswordRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.auth.CurrentUserResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.auth.ResetPasswordRequestDTO;

import java.util.UUID;

public interface AuthService {
    // Stamps last_login_at and returns the caller's identity, role and branch.
    CurrentUserResponseDTO recordLogin(UUID userId);

    CurrentUserResponseDTO getCurrentUser();

    // Sets the acting branch for a global admin, for the rest of the session.
    CurrentUserResponseDTO selectBranch(UUID branchId);

    // The caller changes their own password; the current one must be given and must match.
    void changeOwnPassword(ChangePasswordRequestDTO request);

    // An ADMIN sets another user's password outright — no current password needed.
    void resetPassword(UUID userId, ResetPasswordRequestDTO request);
}
