package com.competition.portal.config;

import com.competition.portal.PortalServices;
import com.competition.portal.resource.AdminClubResource;
import com.competition.portal.resource.AdminMeetResource;
import com.competition.portal.resource.AdminStarterResource;
import com.competition.portal.resource.ClubResource;
import com.competition.portal.resource.CountriesResource;
import com.competition.portal.resource.AdminUserResource;
import com.competition.portal.resource.AuthResource;
import com.competition.portal.resource.HealthResource;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;

/** The portal's REST API under /api. Who may call what is decided before, in AuthFilter. */
public class PortalJerseyConfig extends ResourceConfig {

    public PortalJerseyConfig(PortalServices services) {
        register(HealthResource.class);
        register(AuthResource.class);
        register(AdminMeetResource.class);
        register(AdminClubResource.class);
        register(AdminUserResource.class);
        register(AdminStarterResource.class);
        register(ClubResource.class);
        register(CountriesResource.class);

        // No generated API description (application.wadl) on the internet
        property(ServerProperties.WADL_FEATURE_DISABLE, true);

        register(ErrorMappers.Conflict.class);
        register(ErrorMappers.NotFound.class);
        register(ErrorMappers.BadRequest.class);
        register(ErrorMappers.Auth.class);
        register(ErrorMappers.Validation.class);
        register(ErrorMappers.Closed.class);
        register(ErrorMappers.Unexpected.class);

        register(JacksonFeature.class);
        register(ObjectMapperResolver.class);

        register(new PortalBinder(services));
    }
}
