package com.supremebilliardshall.billiards_hall_system.exception;

// The client settled a bill it had not re-read. This is what stops two counter tabs taking
// payment for the same table twice.
public class StaleBillVersionException extends RuntimeException{

    public static final String CODE = "STALE_BILL_VERSION";

    public StaleBillVersionException(Integer submitted, Integer current) {
        super("This bill has changed since you loaded it (you sent version " + submitted
                + ", it is now " + current + "). Reload it and check the total before charging.");
    }
}
