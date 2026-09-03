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
}
