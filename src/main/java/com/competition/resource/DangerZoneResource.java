package com.competition.resource;

import com.competition.service.DangerZoneService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** Clears one kind of data at a time; backs the Danger Zone page. */
@Path("/danger-zone")
@Produces(MediaType.APPLICATION_JSON)
public class DangerZoneResource {
    private static final Logger logger = LoggerFactory.getLogger(DangerZoneResource.class);

    @Inject
    private DangerZoneService dangerZoneService;

    public DangerZoneResource() {
        // Default constructor for Jersey
    }

    @GET
    public Response summary() {
        try {
            return Response.ok(dangerZoneService.summary()).build();
        } catch (Exception e) {
            logger.error("Failed to count the data", e);
            return Response.serverError().entity(Map.of("error", "Failed to count the data")).build();
        }
    }

    @POST
    @Path("/clear/{target}")
    public Response clear(@PathParam("target") String target) {
        try {
            return Response.ok(dangerZoneService.clear(target)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            logger.error("Failed to clear {}", target, e);
            return Response.serverError().entity(Map.of("error", "Failed to clear " + target)).build();
        }
    }
}
