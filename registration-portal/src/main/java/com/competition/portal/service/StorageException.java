package com.competition.portal.service;

/**
 * A data file that exists but cannot be read (damaged, wrong format). It is a
 * hard error: the file is left as it is, so nothing is ever saved over data
 * that could not be loaded. Mapped to HTTP 500.
 */
public class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
