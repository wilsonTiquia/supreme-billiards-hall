package com.supremebilliardshall.billiards_hall_system.exception;

import java.io.IOException;

// Raised while reading a request body that has passed the configured ceiling. An IOException
// rather than a RuntimeException because it is thrown from inside the servlet input stream,
// where that is the only thing the contract allows; the handler unwraps it back into a 413.
public class RequestBodyTooLargeException extends IOException {

    public RequestBodyTooLargeException(long maxBytes) {
        super("The request body is larger than the " + maxBytes + " byte limit.");
    }
}
