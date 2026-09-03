package com.supremebilliardshall.billiards_hall_system.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// An ADMIN setting another user's password: the counter's password after launch, or a
// reset for someone locked out. No current password — the admin is the authority here.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResetPasswordRequestDTO {

    @NotBlank(message = "New password is required")
    @Size(min = 8, message = "New password must be at least 8 characters")
    private String newPassword;
}
