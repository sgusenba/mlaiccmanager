package com.competition.portal.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down guessing, in memory:
 * <ul>
 * <li>club codes: at most 10 wrong codes per address in 15 minutes;</li>
 * <li>organisers: after 5 wrong logins for a user name or from an address,
 *     that name or address waits 1 minute, doubling with each further
 *     failure up to 1 hour; a successful login clears both;</li>
 * <li>everyone: more than 50 failed logins in a minute and every login
 *     takes a second longer.</li>
 * </ul>
 */
public class RateLimiter {
    static final int CLUB_FAILURES = 10;
    static final Duration CLUB_WINDOW = Duration.ofMinutes(15);
    static final int ORGANISER_FREE_FAILURES = 5;
    static final Duration ORGANISER_FIRST_WAIT = Duration.ofMinutes(1);
    static final Duration ORGANISER_MAX_WAIT = Duration.ofHours(1);
    static final int GLOBAL_FAILURES_PER_MINUTE = 50;

    private final Clock clock;
    private final Map<String, Deque<Instant>> clubFailures = new ConcurrentHashMap<>();
    private final Map<String, Backoff> organiserFailures = new ConcurrentHashMap<>();
    private final Deque<Instant> recentFailures = new ArrayDeque<>();

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    private static class Backoff {
        int failures;
        Instant blockedUntil = Instant.MIN;
    }

    /** How long the address must wait before trying another club code; zero if it may try now. */
    public Duration clubWait(String ip) {
        Deque<Instant> failures = clubFailures.get(ip);
        if (failures == null) {
            return Duration.ZERO;
        }
        synchronized (failures) {
            Instant now = clock.instant();
            while (!failures.isEmpty() && failures.peekFirst().plus(CLUB_WINDOW).isBefore(now)) {
                failures.pollFirst();
            }
            return failures.size() >= CLUB_FAILURES
                ? Duration.between(now, failures.peekFirst().plus(CLUB_WINDOW)) : Duration.ZERO;
        }
    }

    public void clubFailed(String ip) {
        Deque<Instant> failures = clubFailures.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (failures) {
            failures.addLast(clock.instant());
        }
        failed();
    }

    /** How long the user name or address must wait before the next login attempt; zero if it may try now. */
    public Duration organiserWait(String username, String ip) {
        Instant now = clock.instant();
        Duration wait = Duration.ZERO;
        for (String key : new String[] {"user:" + norm(username), "ip:" + ip}) {
            Backoff b = organiserFailures.get(key);
            if (b != null) {
                synchronized (b) {
                    if (b.blockedUntil.isAfter(now)) {
                        Duration d = Duration.between(now, b.blockedUntil);
                        if (d.compareTo(wait) > 0) {
                            wait = d;
                        }
                    }
                }
            }
        }
        return wait;
    }

    public void organiserFailed(String username, String ip) {
        Instant now = clock.instant();
        for (String key : new String[] {"user:" + norm(username), "ip:" + ip}) {
            Backoff b = organiserFailures.computeIfAbsent(key, k -> new Backoff());
            synchronized (b) {
                b.failures++;
                int over = b.failures - ORGANISER_FREE_FAILURES;
                if (over > 0) {
                    long seconds = ORGANISER_FIRST_WAIT.getSeconds() << Math.min(over - 1, 12);
                    b.blockedUntil = now.plusSeconds(Math.min(seconds, ORGANISER_MAX_WAIT.getSeconds()));
                }
            }
        }
        failed();
    }

    public void organiserSucceeded(String username, String ip) {
        organiserFailures.remove("user:" + norm(username));
        organiserFailures.remove("ip:" + ip);
    }

    /** Whether so many logins failed in the last minute that every login is slowed down. */
    public boolean underAttack() {
        synchronized (recentFailures) {
            prune();
            return recentFailures.size() > GLOBAL_FAILURES_PER_MINUTE;
        }
    }

    private void failed() {
        synchronized (recentFailures) {
            recentFailures.addLast(clock.instant());
            prune();
        }
    }

    private void prune() {
        Instant limit = clock.instant().minus(Duration.ofMinutes(1));
        while (!recentFailures.isEmpty() && recentFailures.peekFirst().isBefore(limit)) {
            recentFailures.pollFirst();
        }
    }

    private static String norm(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }
}
