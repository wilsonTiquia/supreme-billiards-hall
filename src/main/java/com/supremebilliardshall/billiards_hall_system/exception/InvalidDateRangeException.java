package com.supremebilliardshall.billiards_hall_system.exception;

// A report asked for over a range that cannot be answered: `to` before `from`, or longer than
// the year the SQL is sized for. The caller's input, so a 400 rather than a 409.
public class InvalidDateRangeException extends RuntimeException {
    public InvalidDateRangeException(String message) {
        super(message);
    }
}
