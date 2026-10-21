package com.competition.portal.auth;

import com.competition.portal.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2027-04-01T10:00:00Z"));
    private final RateLimiter limiter = new RateLimiter(clock);

    @Test
    void tenWrongClubCodesPerAddressInFifteenMinutes() {
        for (int i = 0; i < 9; i++) {
            limiter.clubFailed("203.0.113.5");
        }
        assertEquals(Duration.ZERO, limiter.clubWait("203.0.113.5"));
        limiter.clubFailed("203.0.113.5");
        assertTrue(limiter.clubWait("203.0.113.5").toMinutes() >= 14);
        assertEquals(Duration.ZERO, limiter.clubWait("198.51.100.1"), "other addresses are not affected");
        clock.advance(Duration.ofMinutes(16));
        assertEquals(Duration.ZERO, limiter.clubWait("203.0.113.5"));
    }

    @Test
    void organiserFailuresBackOffExponentially() {
        for (int i = 0; i < 5; i++) {
            limiter.organiserFailed("stefan", "203.0.113.5");
        }
        assertEquals(Duration.ZERO, limiter.organiserWait("stefan", "198.51.100.1"), "five tries are free");
        limiter.organiserFailed("stefan", "203.0.113.5");
        assertEquals(Duration.ofMinutes(1), limiter.organiserWait("Stefan", "198.51.100.1"), "by user name, any case");
        assertEquals(Duration.ofMinutes(1), limiter.organiserWait("someone", "203.0.113.5"), "and by address");
        clock.advance(Duration.ofMinutes(1));
        limiter.organiserFailed("stefan", "203.0.113.5");
        assertEquals(Duration.ofMinutes(2), limiter.organiserWait("stefan", "x"));
        for (int i = 0; i < 20; i++) {
            limiter.organiserFailed("stefan", "203.0.113.5");
        }
        assertEquals(Duration.ofHours(1), limiter.organiserWait("stefan", "x"), "capped at an hour");

        limiter.organiserSucceeded("stefan", "203.0.113.5");
        assertEquals(Duration.ZERO, limiter.organiserWait("stefan", "203.0.113.5"));
    }

    @Test
    void manyFailuresSlowEveryoneDown() {
        for (int i = 0; i < 51; i++) {
            limiter.clubFailed("10.0.0." + i);
        }
        assertTrue(limiter.underAttack());
        clock.advance(Duration.ofMinutes(2));
        assertFalse(limiter.underAttack());
    }
}
