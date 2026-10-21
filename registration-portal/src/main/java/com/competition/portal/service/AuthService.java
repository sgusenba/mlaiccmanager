package com.competition.portal.service;

import com.competition.portal.auth.AccessCodes;
import com.competition.portal.auth.AuthException;
import com.competition.portal.auth.PasswordHasher;
import com.competition.portal.auth.RateLimiter;
import com.competition.portal.auth.SessionStore;
import com.competition.portal.auth.Totp;
import com.competition.portal.i18n.Messages;
import com.competition.portal.model.Club;
import com.competition.portal.model.Meet;
import com.competition.portal.model.Organiser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;

/**
 * Log-ins: organisers with user name and password (plus a code from their
 * authenticator app once they turned it on), clubs with their access code.
 * Every failure gives the same message, whatever was wrong, and is logged
 * with the address but never with what was typed. RateLimiter slows down
 * guessing.
 */
public class AuthService {
    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final PortalStore portalStore;
    private final MeetStore meetStore;
    private final SessionStore sessions;
    private final RateLimiter limiter;
    private final Clock clock;

    public AuthService(PortalStore portalStore, MeetStore meetStore, SessionStore sessions, RateLimiter limiter, Clock clock) {
        this.portalStore = portalStore;
        this.meetStore = meetStore;
        this.sessions = sessions;
        this.limiter = limiter;
        this.clock = clock;
    }

    /** Logs an organiser in; returns the session token. */
    public String organiserLogin(String username, String password, String totp, String ip) throws Exception {
        slowDownIfUnderAttack();
        Duration wait = limiter.organiserWait(username, ip);
        if (!wait.isZero()) {
            throw tooMany(wait);
        }
        Organiser organiser = OrganiserService.find(portalStore.get(), username);
        boolean passwordOk;
        if (organiser == null) {
            PasswordHasher.spendTime(password);
            passwordOk = false;
        } else {
            passwordOk = PasswordHasher.verify(password, organiser.passwordHash);
        }
        if (!passwordOk) {
            limiter.organiserFailed(username, ip);
            logger.warn("Failed organiser login from {}", ip);
            throw new AuthException(401, Messages.get("auth.failed"));
        }
        if (organiser.totpConfirmed) {
            if (totp == null || totp.isBlank()) {
                // Not a failure: the page asks for the code and sends everything again
                throw new AuthException(401, Messages.get("auth.totpRequired"), null, true);
            }
            long step = Totp.verify(organiser.totpSecret, totp, clock.instant().getEpochSecond(), organiser.totpLastStep);
            if (step < 0) {
                limiter.organiserFailed(username, ip);
                logger.warn("Failed organiser login (authenticator code) from {}", ip);
                throw new AuthException(401, Messages.get("auth.totpWrong"), null, true);
            }
            int id = organiser.id;
            portalStore.update(data -> {
                Organiser stored = OrganiserService.byId(data, id);
                if (stored != null) {
                    stored.totpLastStep = Math.max(stored.totpLastStep, step);
                }
                return null;
            });
        }
        limiter.organiserSucceeded(username, ip);
        logger.info("Organiser {} logged in from {}", organiser.id, ip);
        return sessions.createOrganiser(organiser.id);
    }

    /** Logs a club in with its access code; returns the session token. */
    public String clubLogin(String code, String ip) throws Exception {
        slowDownIfUnderAttack();
        Duration wait = limiter.clubWait(ip);
        if (!wait.isZero()) {
            throw tooMany(wait);
        }
        String hash = AccessCodes.hash(code);
        Meet meet = null;
        Club club = null;
        if (hash != null) {
            for (Meet m : meetStore.list()) {
                for (Club c : m.clubs) {
                    if (hash.equals(c.codeHash) && !c.revoked) {
                        meet = m;
                        club = c;
                    }
                }
            }
        }
        if (club == null) {
            limiter.clubFailed(ip);
            logger.warn("Failed club login from {}", ip);
            throw new AuthException(401, Messages.get("auth.codeInvalid"));
        }
        if (Meet.DRAFT.equals(meet.status)) {
            throw new AuthException(403, Messages.get("auth.notOpenYet", meet.name));
        }
        int clubId = club.id;
        String now = clock.instant().toString();
        meetStore.update(meet.id, m -> {
            m.clubs.stream().filter(c -> c.id == clubId).forEach(c -> c.lastLoginAt = now);
            return null;
        });
        logger.info("Club {} of meet {} logged in from {}", clubId, meet.id, ip);
        return sessions.createClub(meet.id, clubId);
    }

    private void slowDownIfUnderAttack() throws InterruptedException {
        if (limiter.underAttack()) {
            Thread.sleep(1000);
        }
    }

    private static AuthException tooMany(Duration wait) {
        long minutes = Math.max(1, (wait.getSeconds() + 59) / 60);
        return new AuthException(429, Messages.get("auth.tooMany", minutes), wait, false);
    }
}
