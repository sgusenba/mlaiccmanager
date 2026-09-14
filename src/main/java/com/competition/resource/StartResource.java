package com.competition.resource;

import com.competition.model.Start;
import com.competition.service.StartService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Path("/competitors/{competitorId}/starts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StartResource {
    private static final Logger logger = LoggerFactory.getLogger(StartResource.class);
    
    @Inject
    private StartService startService;

    public StartResource() {
        // Default constructor for Jersey
    }

    @POST
    public Response createStart(@PathParam("competitorId") int competitorId, Map<String, Object> requestData) {
        try {
            Object disciplineIdObj = requestData.get("discipline_id");
            if (disciplineIdObj == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"discipline_id is required\"}").build();
            }
            
            int disciplineId = ((Number) disciplineIdObj).intValue();
            Start created = startService.createStart(competitorId, disciplineId);
            return Response.ok(created).build();
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid start data: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        } catch (Exception e) {
            logger.error("Error creating start", e);
            return Response.serverError().entity("{\"error\": \"Failed to create start\"}").build();
        }
    }

    @DELETE
    @Path("/{generatedId}")
    public Response deleteStart(@PathParam("competitorId") int competitorId, @PathParam("generatedId") String generatedId) {
        try {
            startService.deleteStart(competitorId, generatedId);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid start deletion: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        } catch (Exception e) {
            logger.error("Error deleting start", e);
            return Response.serverError().entity("{\"error\": \"Failed to delete start\"}").build();
        }
    }
}