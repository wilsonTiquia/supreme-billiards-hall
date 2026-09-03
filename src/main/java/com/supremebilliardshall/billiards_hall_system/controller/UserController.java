package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.auth.ResetPasswordRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.user.UserResponseDTO;
import com.supremebilliardshall.billiards_hall_system.security.LoginAttemptService;
import com.supremebilliardshall.billiards_hall_system.security.SessionInvalidator;
import com.supremebilliardshall.billiards_hall_system.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// User administration is the owner's job. The one action here is resetting a password —
// setting the counter's after launch, or unlocking a user who has forgotten theirs.
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

    // The staff of the admin's current branch, for the Admin > Staff screen.
    @GetMapping
    public ResponseEntity<APIResponse<List<UserResponseDTO>>> getUsers() {
        List<UserResponseDTO> users = authService.listBranchUsers();
        return ResponseEntity.
                ok(APIResponse.success(
                        users,
                        "Users fetched successfully"));
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
