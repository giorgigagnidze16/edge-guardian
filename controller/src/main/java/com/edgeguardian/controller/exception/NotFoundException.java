package com.edgeguardian.controller.exception;

/**
 * Thrown by services when a requested resource does not exist. Mapped to HTTP 404.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
