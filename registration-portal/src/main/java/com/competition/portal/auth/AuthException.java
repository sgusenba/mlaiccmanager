package com.competition.portal.auth;

import java.time.Duration;

/**
 * A refused log-in or a request without the right session: 401 (not logged
 * in, wrong credentials), 403 (logged in but not allowed) or 429 (too many
 * attempts, with how long to wait). Mapped to a JSON error by ErrorMappers.
 */
public class AuthException extends RuntimeException {
    private final int status;
    private final Duration retryAfter;
    private final boolean totpRequired;

    public AuthException(int status, String message) {
        this(status, message, null, false);
    }

    public AuthException(int status, String message, Duration retryAfter, boolean totpRequired) {
        super(message);
        this.status = status;
        this.retryAfter = retryAfter;
        this.totpRequired = totpRequired;
    }

    public int getStatus() {
        return status;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    public boolean isTotpRequired() {
        return totpRequired;
    }
}
