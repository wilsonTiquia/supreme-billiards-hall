package com.supremebilliardshall.billiards_hall_system.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// A plain unit test — the service is a POJO, no Spring context needed.
class LoginAttemptServiceTest {

    @Test
    void subThresholdEntriesDoNotAccumulateWithoutBound() {
        // A small ceiling so eviction is easy to trigger.
        LoginAttemptService service = new LoginAttemptService(4);

        // Many distinct usernames, each a single failure from a distinct address — the spray
        // the reviewer flagged, all sub-threshold so none ever locks.
        for (int i = 0; i < 500; i++) {
            service.recordFailure("user-" + UUID.randomUUID(), "10.0." + (i % 250) + "." + i);
        }

        // The map stayed bounded instead of holding 1000 entries.
        assertThat(service.trackedCount()).isLessThanOrEqualTo(6);
    }

    @Test
    void aGenuineLockoutIsNotEvictedByThePressureFromOtherAttempts() {
        LoginAttemptService service = new LoginAttemptService(4);

        // Lock one account out.
        for (int i = 0; i < 5; i++) {
            service.recordFailure("victim", "203.0.113.9");
        }
        assertThat(service.isBlocked("victim", "203.0.113.9")).isTrue();

        // Now flood with unrelated sub-threshold attempts, forcing repeated eviction.
        for (int i = 0; i < 500; i++) {
            service.recordFailure("noise-" + UUID.randomUUID(), "198.51.100." + (i % 250));
        }

        // The real lockout survived the pressure.
        assertThat(service.isBlocked("victim", "203.0.113.9")).isTrue();
    }

    /*
     * The hall has one Mac, so every login in the system's life arrives from one address.
     * Sharing the five-failure threshold across the address scope meant the cashier's typos
     * locked out the owner -- whose login is the only way to reach clearAll(), so the
     * documented recovery was unreachable from the only machine there is.
     */
    @Test
    void oneStaffMemberFumblingTheirPasswordDoesNotLockOutTheRestOfTheTill() {
        LoginAttemptService service = new LoginAttemptService();
        String till = "127.0.0.1";

        for (int i = 0; i < 5; i++) {
            service.recordFailure("counter", till);
        }

        // Whoever actually fumbled is still locked out; that half is the point of the control.
        assertThat(service.isBlocked("counter", till)).isTrue();
        // The owner, who has not failed once, can still get in and clear it.
        assertThat(service.isBlocked("owner", till)).isFalse();
    }

    /*
     * The address cap still exists and still bites -- it is spraying-shaped (30) rather than
     * typo-shaped (5).
     *
     * What the exemption costs is stated plainly here: a username with a clean record gets one
     * attempt before the address cap can reach it. That is an acceptable price in this hall,
     * where two accounts exist and each is independently capped at five, so there is no
     * username list to work through; it is the trade to revisit if this ever runs more than one
     * till.
     */
    @Test
    void aSprayFromOneAddressIsStillCapped() {
        LoginAttemptService service = new LoginAttemptService();
        String attacker = "203.0.113.40";

        for (int i = 0; i < 30; i++) {
            service.recordFailure("victim-" + i, attacker);
        }

        // A fresh username from that address is exempt only while it is itself clean; once it
        // fails, the address cap catches it rather than letting the spray run indefinitely.
        service.recordFailure("victim-next", attacker);
        assertThat(service.isBlocked("victim-next", attacker)).isTrue();
    }
}
