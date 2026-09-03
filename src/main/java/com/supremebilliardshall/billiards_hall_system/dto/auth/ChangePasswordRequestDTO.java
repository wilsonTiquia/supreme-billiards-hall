package com.supremebilliardshall.billiards_hall_system.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Changing your own password. The current password is required so a walk-up at an
// unlocked till cannot silently take the account over.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChangePasswordRequestDTO {

    @NotBlank(message = "Current password is required")
    private String currentPassword;

    // Minimum length only, no complexity rules: length is the control that matters and
    // arbitrary composition rules push staff towards weaker, written-down passwords.
    @NotBlank(message = "New password is required")
    @Size(min = 8, message = "New password must be at least 8 characters")
    private String newPassword;
}
