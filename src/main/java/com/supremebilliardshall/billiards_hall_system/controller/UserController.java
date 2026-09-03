package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.auth.ResetPasswordRequestDTO;
import com.supremebilliardshall.billiards_hall_system.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

// User administration is the owner's job. The one action here is resetting a password —
// setting the counter's after launch, or unlocking a user who has forgotten theirs.
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final AuthService authService;

    public UserController(AuthService authService) {
        this.authService = authService;
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<APIResponse<Void>> resetPassword(@PathVariable UUID id,
                                                           @Valid @RequestBody ResetPasswordRequestDTO resetPasswordRequestDTO) {
        authService.resetPassword(id, resetPasswordRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        null,
                        "Password reset successfully"));
    }
}
