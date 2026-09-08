package com.supremebilliardshall.billiards_hall_system.service.impl;

import java.security.SecureRandom;

/**
 * The code a cashier types at 1am, in a dim room, off a customer's phone screen.
 *
 * <p>Generation and matching are both here, deliberately. If the two ever disagreed about what
 * a code looks like -- one uppercasing, the other not; one keeping the prefix, the other
 * stripping it -- every code the hall printed would stop working, and it would fail as "no such
 * voucher" rather than as anything pointing at the cause.
 */
final class VoucherCodes {

    /*
     * Crockford Base32 minus 0 and 1: no 0/O, no 1/I/L, and no U either, which Crockford drops
     * and which is worth dropping again for the U/V pair on a low-resolution screenshot.
     *
     * Thirty characters. The alphabet is what a cashier can read back correctly; the LENGTH is
     * what keeps two codes apart, so shrinking the alphabet is paid for below rather than by
     * hoping the ambiguous characters never come up.
     */
    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";

    /*
     * Six characters, which is 30^6 = 729,000,000 codes.
     *
     * A batch of fifty has a 1.7-in-a-million chance of containing a repeat. Over ten thousand
     * codes -- more than this hall will print in years -- the birthday approximation
     * 1 - exp(-n(n-1)/2N) gives 6.6%, about one in fifteen, so expect roughly one repeat ever.
     * That is a RETRY and not a duplicate: voucher_code_key rejects it and generation draws
     * again, so the arithmetic here decides how often that happens, not whether it is handled.
     *
     * Five characters would have been 24.3 million and about a 40% chance of a repeat within
     * the first five thousand codes -- still survivable, but it also puts guessing in reach:
     * with 200 codes outstanding a blind guess would land once in 121,500 rather than once in
     * 3,645,000. Redemption is staff-authenticated and audited, so guessing is a staff attack
     * rather than a public one, but a sixth character costs one keystroke and closes it.
     */
    private static final int LENGTH = 6;

    // Two letters that mean nothing to the system and everything to the person holding the
    // phone: it makes the code recognisable AS a code in a screenshot full of other text. The
    // prefix is stored and matched like the rest of it, and typing it is optional.
    private static final String PREFIX = "SB";

    private static final SecureRandom RANDOM = new SecureRandom();

    private VoucherCodes() {
    }

    /** One code in its stored form: prefixed, uppercase, no separators. */
    static String generate() {
        StringBuilder code = new StringBuilder(PREFIX);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /**
     * What the cashier typed, reduced to the one form that is ever stored.
     *
     * <p>Uppercased, with every space and dash removed wherever they fall, and the prefix added
     * back if it was left off -- so "sb 7k4-m2q", "SB-7K4M2Q" and "7k4m2q" all find the same
     * row. Nothing else is corrected: an O typed for a 0 is not silently repaired, because the
     * alphabet has neither and a code that needs repairing is a code that was misread.
     *
     * <p>Returns null for input that cannot be a code at all, which the service turns into the
     * not-found refusal rather than running a query that cannot match.
     */
    static String normalise(String typed) {
        if (typed == null) {
            return null;
        }
        String stripped = typed.replaceAll("[\\s-]", "").toUpperCase();
        if (stripped.isEmpty()) {
            return null;
        }
        return stripped.startsWith(PREFIX) ? stripped : PREFIX + stripped;
    }

    /** How the code is shown back: SB-7K4-M2Q. Never stored in this form. */
    static String display(String code) {
        if (code == null || code.length() != PREFIX.length() + LENGTH) {
            return code;
        }
        int half = PREFIX.length() + LENGTH / 2;
        return code.substring(0, PREFIX.length()) + "-"
                + code.substring(PREFIX.length(), half) + "-"
                + code.substring(half);
    }
}
