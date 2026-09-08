package com.supremebilliardshall.billiards_hall_system.dto.user;

import com.supremebilliardshall.billiards_hall_system.entity.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/*
 * An ADMIN adding someone who can sign in.
 *
 * The password rules are ResetPasswordRequestDTO's, word for word, rather than a second
 * standard invented here: both are an admin typing a temporary that the person replaces on
 * first sign-in, and two different minimum lengths for the same act would be a bug waiting
 * to be reported as one.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateUserRequestDTO {

    /*
     * Restricted to what someone can type at a till without ambiguity. Spaces are out because
     * the login form trims, and a username that differs from its neighbour only by a trailing
     * space is a support call nobody can diagnose over the phone.
     */
    @NotBlank(message = "A username is required")
    @Size(max = 50, message = "Username must be at most 50 characters")
    @Pattern(regexp = "[A-Za-z0-9._-]+",
            message = "Username can use letters, numbers, dots, dashes and underscores only")
    private String username;

    @NotBlank(message = "A full name is required")
    @Size(max = 100, message = "Full name must be at most 100 characters")
    private String fullName;

    @NotNull(message = "A role is required")
    private UserRole role;

    // Temporary by construction: the server sets must_change_password, so this value stops
    // working the moment the person signs in with it.
    @NotBlank(message = "A temporary password is required")
    @Size(min = 8, message = "The temporary password must be at least 8 characters")
    private String temporaryPassword;
}
