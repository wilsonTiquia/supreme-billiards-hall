package com.supremebilliardshall.billiards_hall_system.exception;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String resource, Object identifier) {
        super(resource + " not found with identifier: " + identifier);
    }

    public ResourceNotFoundException(String resource, Object id, String name) {
        super(resource + " not found: id=" + id + ", name='" + name + "'");
    }
}