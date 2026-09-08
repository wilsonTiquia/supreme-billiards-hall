package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.auth.ChangePasswordRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.auth.CurrentUserResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.auth.ResetPasswordRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.user.CreateUserRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.user.UpdateUserRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.user.UserResponseDTO;

import java.util.List;
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

    // The staff of the caller's current branch, for the Admin > Staff screen.
    List<UserResponseDTO> listBranchUsers();

    // Adding, editing and retiring the people who can sign in. ADMIN only, enforced at the
    // controller; the invariants that keep an admin reachable live in the implementation.
    UserResponseDTO createUser(CreateUserRequestDTO request);

    UserResponseDTO updateUser(UUID userId, UpdateUserRequestDTO request);

    UserResponseDTO archiveUser(UUID userId);
}
