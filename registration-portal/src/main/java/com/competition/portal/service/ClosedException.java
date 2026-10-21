package com.competition.portal.service;

/** A club tried to change its registrations after the deadline or while the meet is not open. Mapped to 403 with "closed": true. */
public class ClosedException extends RuntimeException {
    public ClosedException(String message) {
        super(message);
    }
}
