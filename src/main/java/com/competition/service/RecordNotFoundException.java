package com.competition.service;

/** Thrown when the record to update or delete does not exist (anymore). Mapped to HTTP 404. */
public class RecordNotFoundException extends RuntimeException {
    public RecordNotFoundException(String message) {
        super(message);
    }
}
