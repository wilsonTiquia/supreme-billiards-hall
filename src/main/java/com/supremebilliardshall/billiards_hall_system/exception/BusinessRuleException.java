package com.supremebilliardshall.billiards_hall_system.exception;

// A request that is well formed but conflicts with the current state of the domain: pausing a
// session that is already paused, closing one that is already closed, or setting a friend rate
// on a customer type that does not allow one.
public class BusinessRuleException extends RuntimeException{
    public BusinessRuleException(String message) {
        super(message);
    }
}
