package com.supremebilliardshall.billiards_hall_system.security;

import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;

import java.util.UUID;

// Ends a user's server-side sessions after their password changes, so a reset prompted by a
// suspected compromise does not leave the intruder logged in. Works off the SessionRegistry,
// which login populates. Principals are matched by user id, so multiple logins by the same
// user (each a distinct principal object) are all found.
@Component
public class SessionInvalidator {

    private final SessionRegistry sessionRegistry;

    public SessionInvalidator(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    // The user changed their own password: end every OTHER session, keeping the one they are
    // using so they are not logged out of the act of changing it.
    public void invalidateOtherSessions(UUID userId, String keepSessionId) {
        expire(userId, keepSessionId);
    }

    // An admin reset this user's password: end all of the user's sessions.
    public void invalidateAllSessions(UUID userId) {
        expire(userId, null);
    }

    private void expire(UUID userId, String keepSessionId) {
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (principal instanceof AppUserDetails details && userId.equals(details.getUserId())) {
                for (SessionInformation session : sessionRegistry.getAllSessions(principal, false)) {
                    if (keepSessionId == null || !keepSessionId.equals(session.getSessionId())) {
                        // The ConcurrentSessionFilter turns this into an actual logout on the
                        // session's next request.
                        session.expireNow();
                    }
                }
            }
        }
    }
}
