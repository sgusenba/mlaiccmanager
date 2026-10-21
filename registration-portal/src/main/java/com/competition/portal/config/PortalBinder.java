package com.competition.portal.config;

import com.competition.portal.PortalConfig;
import com.competition.portal.PortalServices;
import com.competition.portal.auth.SessionStore;
import com.competition.portal.service.AuditLog;
import com.competition.portal.service.AuthService;
import com.competition.portal.service.ExportService;
import com.competition.portal.service.StarterService;
import com.competition.portal.service.ClubService;
import com.competition.portal.service.MeetService;
import com.competition.portal.service.MeetStore;
import com.competition.portal.service.OrganiserService;
import com.competition.portal.service.PortalStore;
import org.glassfish.hk2.utilities.binding.AbstractBinder;

/** Hands the shared services to the resources. */
public class PortalBinder extends AbstractBinder {
    private final PortalServices services;

    public PortalBinder(PortalServices services) {
        this.services = services;
    }

    @Override
    protected void configure() {
        bind(services).to(PortalServices.class);
        bind(services.config).to(PortalConfig.class);
        bind(services.portalStore).to(PortalStore.class);
        bind(services.meetStore).to(MeetStore.class);
        bind(services.sessions).to(SessionStore.class);
        bind(services.meetService).to(MeetService.class);
        bind(services.clubService).to(ClubService.class);
        bind(services.organiserService).to(OrganiserService.class);
        bind(services.authService).to(AuthService.class);
        bind(services.auditLog).to(AuditLog.class);
        bind(services.starterService).to(StarterService.class);
        bind(services.exportService).to(ExportService.class);
    }
}
