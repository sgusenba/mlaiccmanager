package com.competition.service;

/**
 * Thrown when a write is based on stale data, e.g. another user saved the same
 * record first. Mapped to HTTP 409; {@code current} is the record as it is stored
 * now (or null if it no longer exists) so the client can show the latest state.
 */
public class ConflictException extends RuntimeException {
    private final transient Object current;

    public ConflictException(String message, Object current) {
        super(message);
        this.current = current;
    }

    public Object getCurrent() {
        return current;
    }
}
