package com.supremebilliardshall.billiards_hall_system.dto.user;

import com.supremebilliardshall.billiards_hall_system.entity.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/*
 * Editing someone who already exists.
 *
 * No username: app_user_username_key is partial on archived_at IS NULL, so a rename would have
 * to reason about collisions with archived rows for no benefit anybody asked for. A username is
 * what a person types every night; it is not a field to fiddle with.
 *
 * No password either -- that is PUT /users/{id}/password, which invalidates the user's sessions
 * as a side effect because a reset usually means a suspected compromise. Folding it in here
 * would make an ordinary name correction end somebody's shift.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateUserRequestDTO {

    @NotBlank(message = "A full name is required")
    @Size(max = 100, message = "Full name must be at most 100 characters")
    private String fullName;

    @NotNull(message = "A role is required")
    private UserRole role;

    // Sent explicitly rather than defaulted: a silent false would deactivate somebody on a
    // request that only meant to fix a spelling.
    @NotNull(message = "Say whether this user is active")
    private Boolean isActive;
}
