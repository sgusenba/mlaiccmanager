package com.competition.portal.auth;

import java.time.Instant;

/** Who is logged in with a session cookie: an organiser, or a club for one meet. */
public class Session {
    public static final String ORGANISER = "organiser";
    public static final String CLUB = "club";
    /** The request attribute AuthFilter puts the session under. */
    public static final String ATTRIBUTE = "portal.session";

    public final String role;
    public final int organiserId;
    public final int meetId;
    public final int clubId;
    final Instant created;
    volatile Instant lastSeen;

    Session(String role, int organiserId, int meetId, int clubId, Instant now) {
        this.role = role;
        this.organiserId = organiserId;
        this.meetId = meetId;
        this.clubId = clubId;
        this.created = now;
        this.lastSeen = now;
    }

    public boolean isOrganiser() {
        return ORGANISER.equals(role);
    }

    public boolean isClub() {
        return CLUB.equals(role);
    }

    /** "organiser:3" or "club:7", for the audit log. */
    public String actor() {
        return isOrganiser() ? "organiser:" + organiserId : "club:" + clubId;
    }
}
