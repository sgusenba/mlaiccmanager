package com.competition.portal.resource;

import com.competition.portal.service.PortalStore;
import com.competition.portal.service.StorageException;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * For the uptime monitor: 200 {"ok": true} while the portal runs and can read
 * its data, 503 {"ok": false} when it cannot. Reveals nothing else.
 */
@Path("/health")
@Produces(MediaType.APPLICATION_JSON)
public class HealthResource {
    private static final Logger logger = LoggerFactory.getLogger(HealthResource.class);

    @Inject
    private PortalStore portalStore;

    @GET
    public Response health() {
        try {
            portalStore.get();
            return Response.ok(Map.of("ok", true)).build();
        } catch (StorageException e) {
            logger.error("Health check failed", e);
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(Map.of("ok", false)).build();
        }
    }
}
