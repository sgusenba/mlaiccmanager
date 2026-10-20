package com.competition.portal.config;

import com.competition.portal.PortalConfig;
import com.competition.portal.service.MeetStore;
import com.competition.portal.service.PortalStore;
import org.glassfish.hk2.utilities.binding.AbstractBinder;

/** Creates the portal's services once and hands them to the resources. */
public class PortalBinder extends AbstractBinder {
    private final PortalConfig config;

    public PortalBinder(PortalConfig config) {
        this.config = config;
    }

    @Override
    protected void configure() {
        PortalStore portalStore = new PortalStore(config.dataDir());
        MeetStore meetStore = new MeetStore(config.dataDir(), portalStore);

        bind(config).to(PortalConfig.class);
        bind(portalStore).to(PortalStore.class);
        bind(meetStore).to(MeetStore.class);
    }
}
