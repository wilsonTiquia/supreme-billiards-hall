package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.auth.ResetPasswordRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.user.CreateUserRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.user.UpdateUserRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.user.UserResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.UserRole;
import com.supremebilliardshall.billiards_hall_system.security.LoginAttemptService;
import com.supremebilliardshall.billiards_hall_system.security.SessionInvalidator;
import com.supremebilliardshall.billiards_hall_system.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// User administration is the owner's job: who can sign in, what they may do, and the two
// recoveries — a new temporary password, and clearing a lockout.
//
// Every route is ADMIN, which is also why the create route matters. Until it existed, every
// user this system had came from the V2 seed, so there was exactly one admin and the
// documented unlock path (clearLockouts, below) could be shut behind the one account that
// could reach it. A second admin is what makes that recovery real.
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final AuthService authService;
    private final LoginAttemptService loginAttemptService;
    private final SessionInvalidator sessionInvalidator;

    public UserController(AuthService authService,
                          LoginAttemptService loginAttemptService,
                          SessionInvalidator sessionInvalidator) {
        this.authService = authService;
        this.loginAttemptService = loginAttemptService;
        this.sessionInvalidator = sessionInvalidator;
    }

    // The Admin > Staff screen: this branch's people plus the global admins, who are staff of
    // every branch rather than of none. See AppUserRepository.findBranchStaffAndGlobalAdmins.
    @GetMapping
    public ResponseEntity<APIResponse<List<UserResponseDTO>>> getUsers() {
        List<UserResponseDTO> users = authService.listBranchUsers();
        return ResponseEntity.
                ok(APIResponse.success(
                        users,
                        "Users fetched successfully"));
    }

    @PostMapping
    public ResponseEntity<APIResponse<UserResponseDTO>> createUser(
            @Valid @RequestBody CreateUserRequestDTO createUserRequestDTO) {
        UserResponseDTO user = authService.createUser(createUserRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        user,
                        "Staff member added successfully"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<APIResponse<UserResponseDTO>> updateUser(@PathVariable UUID id,
                                                                   @Valid @RequestBody UpdateUserRequestDTO updateUserRequestDTO) {
        UserResponseDTO user = authService.updateUser(id, updateUserRequestDTO);
        // A demoted or deactivated user keeps the session they are holding, and that session
        // was built with the authorities they had when they signed in. Ending it is what makes
        // the change take effect now rather than whenever they next log out — the same reason
        // a password reset ends sessions below.
        if (user.getRole() != UserRole.ADMIN || !user.isActive()) {
            sessionInvalidator.invalidateAllSessions(id);
        }
        return ResponseEntity.
                ok(APIResponse.success(
                        user,
                        "Staff member updated successfully"));
    }

    // Archives; never deletes. Audit rows, bill lines and payments point at this person for
    // ever, and the partial unique index frees the username for reuse the moment it is set.
    @DeleteMapping("/{id}")
    public ResponseEntity<APIResponse<UserResponseDTO>> archiveUser(@PathVariable UUID id) {
        UserResponseDTO user = authService.archiveUser(id);
        // Archived means cannot sign in, so a live session must not outlive the decision.
        sessionInvalidator.invalidateAllSessions(id);
        return ResponseEntity.
                ok(APIResponse.success(
                        user,
                        "Staff member archived successfully"));
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<APIResponse<Void>> resetPassword(@PathVariable UUID id,
                                                           @Valid @RequestBody ResetPasswordRequestDTO resetPasswordRequestDTO) {
        authService.resetPassword(id, resetPasswordRequestDTO);
        // A reset is often prompted by a suspected compromise, so end all of that user's
        // sessions — the intruder must not stay logged in on the strength of the old password.
        sessionInvalidator.invalidateAllSessions(id);
        return ResponseEntity.
                ok(APIResponse.success(
                        null,
                        "Password reset successfully"));
    }

    // Clears every login lockout. The unlock path for staff who lock themselves out at the
    // till — an admin can free them without waiting out the timer or restarting the app.
    @DeleteMapping("/lockouts")
    public ResponseEntity<APIResponse<Void>> clearLockouts() {
        loginAttemptService.clearAll();
        return ResponseEntity.
                ok(APIResponse.success(
                        null,
                        "Login lockouts cleared"));
    }
}
