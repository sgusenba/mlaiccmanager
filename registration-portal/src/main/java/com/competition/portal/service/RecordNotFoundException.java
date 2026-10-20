package com.competition.portal.service;

/** A meet, club or starter that does not exist (any more). Mapped to HTTP 404. */
public class RecordNotFoundException extends RuntimeException {
    public RecordNotFoundException(String message) {
        super(message);
    }
}
