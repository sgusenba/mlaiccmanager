package com.competition.portal.auth;

import com.competition.portal.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2027-04-01T10:00:00Z"));
    private final SessionStore sessions = new SessionStore(clock);

    @Test
    void organiserSessionsEndAfterThirtyIdleMinutes() {
        String token = sessions.createOrganiser(1);
        assertEquals(1, sessions.find(token).organiserId);
        clock.advance(Duration.ofMinutes(29));
        assertNotNull(sessions.find(token), "activity keeps it");
        clock.advance(Duration.ofMinutes(31));
        assertNull(sessions.find(token));
    }

    @Test
    void organiserSessionsEndAfterTwelveHoursAnyway() {
        String token = sessions.createOrganiser(1);
        for (int i = 0; i < 24; i++) {
            clock.advance(Duration.ofMinutes(29));
            assertNotNull(sessions.find(token), "active for 11.6 h");
        }
        clock.advance(Duration.ofMinutes(29));
        assertNull(sessions.find(token), "still active, but past 12 h since the login");
    }

    @Test
    void clubSessionsLastLonger() {
        String token = sessions.createClub(3, 7);
        clock.advance(Duration.ofHours(3));
        Session s = sessions.find(token);
        assertTrue(s.isClub());
        assertEquals(3, s.meetId);
        assertEquals(7, s.clubId);
        clock.advance(Duration.ofHours(5));
        assertNull(sessions.find(token));
    }

    @Test
    void sessionsCanBeEnded() {
        String a = sessions.createClub(3, 7);
        String b = sessions.createClub(3, 8);
        String c = sessions.createOrganiser(1);
        sessions.endWhere(s -> s.isClub() && s.clubId == 7);
        assertNull(sessions.find(a));
        assertNotNull(sessions.find(b));
        sessions.end(c);
        assertNull(sessions.find(c));
        assertNull(sessions.find("made-up"));
        assertNull(sessions.find(null));
    }
}
