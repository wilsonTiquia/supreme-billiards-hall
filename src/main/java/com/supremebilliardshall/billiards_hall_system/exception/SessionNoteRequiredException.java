package com.supremebilliardshall.billiards_hall_system.exception;

// A bill cannot be left unpaid with nobody's name against it, and this session's thread is
// empty. Coded rather than left as a plain message because the client has a specific repair:
// the note field on the leave-unpaid modal becomes required, focused and marked, instead of a
// red sentence under a form the operator then has to re-read to work out what to change.
public class SessionNoteRequiredException extends RuntimeException {

    public static final String CODE = "SESSION_NOTE_REQUIRED";

    public SessionNoteRequiredException() {
        super("Nobody's name is on this bill. Write who owes it before leaving it unpaid — "
                + "an unpaid bill with no name is money nobody can collect.");
    }
}
