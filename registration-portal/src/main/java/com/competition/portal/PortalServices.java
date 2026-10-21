package com.competition.portal;

import com.competition.portal.auth.RateLimiter;
import com.competition.portal.auth.SessionStore;
import com.competition.portal.service.AuditLog;
import com.competition.portal.service.AuthService;
import com.competition.portal.service.RetentionJob;
import com.competition.portal.service.StarterService;
import org.slf4j.LoggerFactory;
import com.competition.portal.service.ClubService;
import com.competition.portal.service.MeetService;
import com.competition.portal.service.MeetStore;
import com.competition.portal.service.OrganiserService;
import com.competition.portal.service.PortalStore;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** The portal's services, created once and shared by the filters and the API resources. */
public class PortalServices {
    public final PortalConfig config;
    public final Clock clock;
    public final PortalStore portalStore;
    public final MeetStore meetStore;
    public final SessionStore sessions;
    public final RateLimiter rateLimiter;
    public final MeetService meetService;
    public final ClubService clubService;
    public final OrganiserService organiserService;
    public final AuthService authService;
    public final AuditLog auditLog;
    public final StarterService starterService;
    public final RetentionJob retentionJob;

    public PortalServices(PortalConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
        this.portalStore = new PortalStore(config.dataDir());
        this.meetStore = new MeetStore(config.dataDir(), portalStore);
        this.sessions = new SessionStore(clock);
        this.rateLimiter = new RateLimiter(clock);
        this.meetService = new MeetService(meetStore);
        this.clubService = new ClubService(meetStore, sessions, clock, config.baseUrl());
        this.organiserService = new OrganiserService(portalStore, sessions, clock);
        this.authService = new AuthService(portalStore, meetStore, sessions, rateLimiter, clock);
        this.auditLog = new AuditLog(meetStore, clock);
        this.starterService = new StarterService(meetStore, auditLog, clock);
        this.retentionJob = new RetentionJob(meetStore, auditLog, clock);
    }

    /**
     * Starts the background work of the running portal: deleting registrations
     * past their meet's delete_after day (a minute after start, then every six
     * hours) and forgetting expired sessions. Not started in tests.
     */
    public void startBackgroundJobs() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "portal-jobs");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                retentionJob.run();
                sessions.purgeExpired();
            } catch (Exception e) {
                LoggerFactory.getLogger(PortalServices.class).error("Background job failed", e);
            }
        }, 1, 6 * 60, TimeUnit.MINUTES);
    }

    /** Whether the public address is HTTPS, which decides the session cookie's form. */
    public boolean secure() {
        return config.baseUrl().startsWith("https://");
    }
}
