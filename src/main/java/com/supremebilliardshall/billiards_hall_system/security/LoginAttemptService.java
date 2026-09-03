package com.supremebilliardshall.billiards_hall_system.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

// In-process login throttling. The app is internet-reachable and there are only a couple of
// accounts, so unlimited attempts against a known username is a one-step compromise. Kept
// deliberately simple: a map in memory, no new infrastructure. A restart clears it, which is
// acceptable — an attacker gains nothing from a restart they cannot cause.
//
// Both the username and the source address are throttled, so neither hammering one account nor
// spraying many usernames from one address gets unlimited tries.
@Component
public class LoginAttemptService {

    private static final int MAX_FAILURES = 5;
    private static final Duration LOCKOUT = Duration.ofMinutes(15);
    // A hard ceiling on tracked entries. A public login endpoint sprayed with unique usernames
    // would otherwise grow this map without bound — the rate limiter must not itself be the
    // memory-exhaustion vector. When the ceiling is passed, entries that are not currently
    // locked (the sub-threshold noise) are dropped; locked entries are kept until they expire.
    private static final int MAX_ENTRIES = 10_000;

    private final int maxEntries;
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    public LoginAttemptService() {
        this(MAX_ENTRIES);
    }

    // Package-private, for tests that need a small ceiling to exercise eviction.
    LoginAttemptService(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    private static final class Attempt {
        int failures;
        Instant lockedUntil;
    }

    public boolean isBlocked(String username, String sourceAddress) {
        return isKeyBlocked(userKey(username)) || isKeyBlocked(addressKey(sourceAddress));
    }

    public void recordFailure(String username, String sourceAddress) {
        recordFailure(userKey(username));
        recordFailure(addressKey(sourceAddress));
    }

    public void recordSuccess(String username, String sourceAddress) {
        attempts.remove(userKey(username));
        attempts.remove(addressKey(sourceAddress));
    }

    private boolean isKeyBlocked(String key) {
        Attempt attempt = attempts.get(key);
        if (attempt == null || attempt.lockedUntil == null) {
            return false;
        }
        if (Instant.now().isAfter(attempt.lockedUntil)) {
            // The lockout has elapsed; forget it so the next attempt starts clean.
            attempts.remove(key);
            return false;
        }
        return true;
    }

    private void recordFailure(String key) {
        attempts.compute(key, (k, existing) -> {
            Attempt attempt = existing != null ? existing : new Attempt();
            // A lockout that has already elapsed is a fresh start, not a continuation.
            if (attempt.lockedUntil != null && Instant.now().isAfter(attempt.lockedUntil)) {
                attempt.failures = 0;
                attempt.lockedUntil = null;
            }
            attempt.failures++;
            if (attempt.failures >= MAX_FAILURES) {
                attempt.lockedUntil = Instant.now().plus(LOCKOUT);
            }
            return attempt;
        });
        if (attempts.size() > maxEntries) {
            evictReleasable();
        }
    }

    // Drops entries that are safe to forget: those below the lockout threshold and those whose
    // lockout has already elapsed. Locked entries — the ones that carry live protection — are
    // never dropped here; they clear themselves when their lockout expires.
    private void evictReleasable() {
        Instant now = Instant.now();
        attempts.values().removeIf(a -> a.lockedUntil == null || now.isAfter(a.lockedUntil));
    }

    // For tests: how many entries are currently tracked.
    int trackedCount() {
        return attempts.size();
    }

    private String userKey(String username) {
        return "u:" + (username == null ? "" : username.trim().toLowerCase());
    }

    private String addressKey(String sourceAddress) {
        return "ip:" + (sourceAddress == null ? "" : sourceAddress);
    }
}
