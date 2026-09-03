package com.supremebilliardshall.billiards_hall_system.security;

import com.supremebilliardshall.billiards_hall_system.entity.UserRole;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Getter
public class AppUserDetails implements UserDetails {

    private final UUID userId;
    // The user's own branch. NULL for a global admin, whose active branch lives in the
    // session and is resolved by BranchContext, never guessed here.
    private final UUID branchId;
    private final String username;
    private final String password;
    private final String fullName;
    private final UserRole role;
    private final boolean enabled;

    public AppUserDetails(UUID userId, UUID branchId, String username, String password,
                          String fullName, UserRole role, boolean enabled) {
        this.userId = userId;
        this.branchId = branchId;
        this.username = username;
        this.password = password;
        this.fullName = fullName;
        this.role = role;
        this.enabled = enabled;
    }

    // The principal for scheduled work: bound to a branch, with no human behind it. Both
    // table_session.closed_by and audit_log.actor_id are nullable for this reason.
    public static AppUserDetails system(UUID branchId) {
        return new AppUserDetails(null, branchId, "system", "", "System", UserRole.ADMIN, true);
    }

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
