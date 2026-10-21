package com.competition.portal;

import com.competition.portal.auth.RateLimiter;
import com.competition.portal.auth.SessionStore;
import com.competition.portal.service.AuthService;
import com.competition.portal.service.ClubService;
import com.competition.portal.service.MeetService;
import com.competition.portal.service.MeetStore;
import com.competition.portal.service.OrganiserService;
import com.competition.portal.service.PortalStore;

import java.time.Clock;

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
    }

    /** Whether the public address is HTTPS, which decides the session cookie's form. */
    public boolean secure() {
        return config.baseUrl().startsWith("https://");
    }
}
