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
// The account lockout is keyed by username AND source address, never by username alone: the
// usernames are public in git history, so a bare per-username lock would let anyone lock the
// till out of its own account from anywhere. The source address is throttled separately, so a
// spray from one address is still capped — but at its own, much higher threshold, and it never
// reaches an account that has not failed itself. Sharing one threshold across both scopes meant
// five typos by the cashier locked the owner out of the hall's only machine, which also made
// the ADMIN clearAll() below unreachable at exactly the moment it was needed.
@Component
public class LoginAttemptService {

    private static final int MAX_FAILURES = 5;

    /*
     * The address-only cap, deliberately far above the per-account one.
     *
     * Five is a TYPO-shaped number. Sharing it with the address scope meant one cashier
     * fumbling their own password five times locked every account out of that machine for
     * fifteen minutes -- including the admin, who had never failed once and whose login is the
     * only way to reach clearAll(). The hall is one Mac on one address, so "throttle the
     * address" and "shut the hall" were the same sentence.
     *
     * Thirty is spraying-shaped: nobody reaches it by mistyping, and a script working through a
     * username list still hits it quickly.
     *
     * Distinct usernames attempted would discriminate better than raw failure count, and if
     * this hall ever runs more than one till that becomes the right answer and this should be
     * revisited. It is not chosen here on the merits: with every login in the system's life
     * arriving from one address, the address scope is nearly inert, and a bounded per-address
     * set is complexity in a security path to sharpen a control this topology cannot exercise.
     * The map below already had to be capped once so it could not exhaust memory; a second
     * unbounded structure per address walks straight back into that.
     */
    private static final int MAX_ADDRESS_FAILURES = 30;
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

    /*
     * A user with no failures of their own is never blocked by somebody else's.
     *
     * The account lock still applies in full to whoever actually fumbled. What no longer
     * happens is the address lock reaching past them to an account that has not failed once:
     * that is what took the owner out of the till when the counter mistyped, and it left the
     * documented recovery -- an admin calling clearAll() -- unreachable from the only machine
     * the hall owns.
     */
    public boolean isBlocked(String username, String sourceAddress) {
        if (isKeyBlocked(userAddressKey(username, sourceAddress))) {
            return true;
        }
        return hasFailures(userAddressKey(username, sourceAddress))
                && isKeyBlocked(addressKey(sourceAddress));
    }

    public void recordFailure(String username, String sourceAddress) {
        recordFailure(userAddressKey(username, sourceAddress), MAX_FAILURES);
        recordFailure(addressKey(sourceAddress), MAX_ADDRESS_FAILURES);
    }

    public void recordSuccess(String username, String sourceAddress) {
        attempts.remove(userAddressKey(username, sourceAddress));
        attempts.remove(addressKey(sourceAddress));
    }

    // Generic single-scope throttling, reused by the change-own-password path so a hijacked
    // session cannot brute-force the current password. The caller builds a namespaced key.
    public boolean isBlockedKey(String key) {
        return isKeyBlocked(key);
    }

    public void recordFailureKey(String key) {
        recordFailure(key, MAX_FAILURES);
    }

    public void recordSuccessKey(String key) {
        attempts.remove(key);
    }

    // The ADMIN unlock path: staff who lock themselves out at the till should not have to wait
    // fifteen minutes or restart the app.
    public void clearAll() {
        attempts.clear();
    }

    /*
     * Has this account itself failed here recently? Read-only on purpose -- isKeyBlocked prunes
     * an elapsed lockout as a side effect, and the exemption check must not be the thing that
     * decides whether an entry still exists. An elapsed window counts as no failures, which is
     * the same fresh start recordFailure gives.
     */
    private boolean hasFailures(String key) {
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return false;
        }
        if (attempt.lockedUntil != null && Instant.now().isAfter(attempt.lockedUntil)) {
            return false;
        }
        return attempt.failures > 0;
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

    private void recordFailure(String key, int maxFailures) {
        attempts.compute(key, (k, existing) -> {
            Attempt attempt = existing != null ? existing : new Attempt();
            // A lockout that has already elapsed is a fresh start, not a continuation.
            if (attempt.lockedUntil != null && Instant.now().isAfter(attempt.lockedUntil)) {
                attempt.failures = 0;
                attempt.lockedUntil = null;
            }
            attempt.failures++;
            if (attempt.failures >= maxFailures) {
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

    private String userAddressKey(String username, String sourceAddress) {
        return "ua:" + (username == null ? "" : username.trim().toLowerCase())
                + "|" + (sourceAddress == null ? "" : sourceAddress);
    }

    private String addressKey(String sourceAddress) {
        return "ip:" + (sourceAddress == null ? "" : sourceAddress);
    }
}
