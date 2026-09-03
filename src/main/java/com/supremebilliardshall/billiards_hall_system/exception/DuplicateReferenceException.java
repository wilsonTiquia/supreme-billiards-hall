package com.supremebilliardshall.billiards_hall_system.exception;

// A payment reference already used in this branch. Deliberately not a hard failure: the same
// GCash reference can legitimately repeat, so the client is given a code it can branch on and
// offer "record anyway", which comes back with duplicateOverride set.
public class DuplicateReferenceException extends RuntimeException{

    public static final String CODE = "DUPLICATE_PAYMENT_REFERENCE";

    public DuplicateReferenceException(String referenceNo) {
        super("Reference '" + referenceNo + "' has already been recorded in this branch. "
                + "Re-submit with duplicateOverride to record it anyway.");
    }
}
