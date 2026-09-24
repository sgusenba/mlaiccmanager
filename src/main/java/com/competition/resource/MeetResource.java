package com.competition.resource;

import com.competition.service.ConflictException;
import com.competition.service.MeetService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** The meet's name, venue, host and dates; stored in meet.json. */
@Path("/meet")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MeetResource {
    private static final Logger logger = LoggerFactory.getLogger(MeetResource.class);

    @Inject
    private MeetService meetService;

    public MeetResource() {
        // Default constructor for Jersey
    }

    @GET
    public Response get() {
        try {
            return Response.ok(meetService.get()).build();
        } catch (Exception e) {
            logger.error("Failed to get meet details", e);
            return Response.serverError()
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "Failed to get meet details"))
                .build();
        }
    }

    @PUT
    public Response update(Map<String, Object> request) {
        try {
            return Response.ok(meetService.update(request)).build();
        } catch (ConflictException e) {
            throw e; // mapped to 409
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid meet details: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", e.getMessage()))
                .build();
        } catch (Exception e) {
            logger.error("Failed to update meet details", e);
            return Response.serverError()
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "Failed to update meet details"))
                .build();
        }
    }
}
