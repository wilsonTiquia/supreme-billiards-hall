package com.supremebilliardshall.billiards_hall_system.dto.user;

import com.supremebilliardshall.billiards_hall_system.entity.UserRole;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

// The staff list for Admin > Staff. No password hash and nothing sensitive: just who exists,
// their role, whether they are active, and whether they still owe a password change.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserResponseDTO {
    private UUID id;
    private String username;
    private String fullName;
    private UserRole role;
    private boolean active;
    private boolean mustChangePassword;
}
