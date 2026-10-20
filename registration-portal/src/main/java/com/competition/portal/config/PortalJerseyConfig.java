package com.competition.portal.config;

import com.competition.portal.PortalConfig;
import com.competition.portal.resource.AdminMeetResource;
import com.competition.portal.resource.HealthResource;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;

/** The portal's REST API under /api. */
public class PortalJerseyConfig extends ResourceConfig {

    public PortalJerseyConfig(PortalConfig config) {
        register(HealthResource.class);
        register(AdminMeetResource.class);

        // No generated API description (application.wadl) on the internet
        property(ServerProperties.WADL_FEATURE_DISABLE, true);

        register(ErrorMappers.Conflict.class);
        register(ErrorMappers.NotFound.class);
        register(ErrorMappers.BadRequest.class);
        register(ErrorMappers.Unexpected.class);

        register(JacksonFeature.class);
        register(ObjectMapperResolver.class);

        register(new PortalBinder(config));
    }
}
