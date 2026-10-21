package com.competition.portal.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The logged-in sessions, in memory only: a restart logs everyone out. The
 * cookie carries a random token (256 bits); only its SHA-256 is kept, so the
 * tokens cannot be read from memory or a heap dump. A session ends after a
 * while without requests (idle) and at the latest some time after the login
 * (absolute): organisers 30 minutes / 12 hours, clubs 4 hours / 7 days.
 */
public class SessionStore {
    static final Duration ORGANISER_IDLE = Duration.ofMinutes(30);
    static final Duration ORGANISER_MAX = Duration.ofHours(12);
    static final Duration CLUB_IDLE = Duration.ofHours(4);
    static final Duration CLUB_MAX = Duration.ofDays(7);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Clock clock;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public SessionStore(Clock clock) {
        this.clock = clock;
    }

    /** A new organiser session; returns the token for the cookie. */
    public String createOrganiser(int organiserId) {
        return put(new Session(Session.ORGANISER, organiserId, 0, 0, clock.instant()));
    }

    /** A new club session for one meet; returns the token for the cookie. */
    public String createClub(int meetId, int clubId) {
        return put(new Session(Session.CLUB, 0, meetId, clubId, clock.instant()));
    }

    /** The session of the token, or null if there is none or it has expired. Counts as activity. */
    public Session find(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        String key = key(token);
        Session session = sessions.get(key);
        if (session == null) {
            return null;
        }
        Instant now = clock.instant();
        boolean organiser = session.isOrganiser();
        Duration idle = organiser ? ORGANISER_IDLE : CLUB_IDLE;
        Duration max = organiser ? ORGANISER_MAX : CLUB_MAX;
        if (now.isAfter(session.lastSeen.plus(idle)) || now.isAfter(session.created.plus(max))) {
            sessions.remove(key);
            return null;
        }
        session.lastSeen = now;
        return session;
    }

    public void end(String token) {
        if (token != null) {
            sessions.remove(key(token));
        }
    }

    /** Ends every session that matches, e.g. a club's after its code was replaced. */
    public void endWhere(Predicate<Session> which) {
        sessions.values().removeIf(which);
    }

    /** Forgets expired sessions; called now and then so memory does not grow. */
    public void purgeExpired() {
        Instant now = clock.instant();
        sessions.values().removeIf(s -> {
            boolean organiser = s.isOrganiser();
            return now.isAfter(s.lastSeen.plus(organiser ? ORGANISER_IDLE : CLUB_IDLE))
                || now.isAfter(s.created.plus(organiser ? ORGANISER_MAX : CLUB_MAX));
        });
    }

    int size() {
        return sessions.size();
    }

    private String put(Session session) {
        if (sessions.size() > 10_000) {
            purgeExpired();
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(key(token), session);
        return token;
    }

    private static String key(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
