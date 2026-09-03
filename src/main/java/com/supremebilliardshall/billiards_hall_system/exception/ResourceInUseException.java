package com.supremebilliardshall.billiards_hall_system.exception;

public class ResourceInUseException extends RuntimeException{
    public ResourceInUseException(String message) {
        super(message);
    }
}
