package com.supremebilliardshall.billiards_hall_system.exception;

// Too many failed logins for a username or a source address. Translated to 429 centrally.
public class TooManyLoginAttemptsException extends RuntimeException {
    public TooManyLoginAttemptsException(String message) {
        super(message);
    }
}
