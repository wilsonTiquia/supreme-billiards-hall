package com.supremebilliardshall.billiards_hall_system.security;

import com.supremebilliardshall.billiards_hall_system.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// A plain unit test against a real SessionRegistryImpl — the mechanism, without the servlet
// stack. The ConcurrentSessionFilter turns expireNow() into an actual logout in production.
class SessionInvalidatorTest {

    @Test
    void changingOwnPasswordEndsOtherSessionsButKeepsTheCurrentOne() {
        SessionRegistry registry = new SessionRegistryImpl();
        UUID userId = UUID.randomUUID();
        AppUserDetails principal = principal(userId);

        registry.registerNewSession("current-session", principal);
        registry.registerNewSession("other-session", principal);

        new SessionInvalidator(registry).invalidateOtherSessions(userId, "current-session");

        assertThat(registry.getSessionInformation("current-session").isExpired()).isFalse();
        assertThat(registry.getSessionInformation("other-session").isExpired()).isTrue();
    }

    @Test
    void anAdminResetEndsAllOfTheTargetsSessions() {
        SessionRegistry registry = new SessionRegistryImpl();
        UUID userId = UUID.randomUUID();
        // Two distinct principal objects for the same user id — as two separate logins produce.
        registry.registerNewSession("session-a", principal(userId));
        registry.registerNewSession("session-b", principal(userId));

        // A different user's session must be left alone.
        UUID otherUser = UUID.randomUUID();
        registry.registerNewSession("bystander", principal(otherUser));

        new SessionInvalidator(registry).invalidateAllSessions(userId);

        assertThat(registry.getSessionInformation("session-a").isExpired()).isTrue();
        assertThat(registry.getSessionInformation("session-b").isExpired()).isTrue();
        assertThat(registry.getSessionInformation("bystander").isExpired()).isFalse();
    }

    private AppUserDetails principal(UUID userId) {
        return new AppUserDetails(userId, null, "user-" + userId, "unused",
                "User", UserRole.EMPLOYEE, true);
    }
}
