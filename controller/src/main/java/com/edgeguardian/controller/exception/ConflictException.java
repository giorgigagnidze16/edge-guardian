package com.edgeguardian.controller.exception;

/**
 * Thrown by services when a request conflicts with the current state. Mapped to HTTP 409.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
