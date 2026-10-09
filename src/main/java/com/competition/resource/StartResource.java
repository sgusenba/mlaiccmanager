package com.competition.resource;

import com.competition.model.Start;
import com.competition.service.ConflictException;
import com.competition.service.DisciplineService;
import com.competition.service.StartService;
import com.competition.service.TeamService;
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

    @Inject
    private TeamService teamService;

    @Inject
    private DisciplineService disciplineService;

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
            Object caliberObj = requestData.get("caliber");
            String caliber = caliberObj != null ? caliberObj.toString() : null;
            Start created = startService.createStart(competitorId, disciplineId, caliber);
            return Response.ok(created).build();
        } catch (ConflictException e) {
            throw e; // mapped to 409
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid start data: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        } catch (Exception e) {
            logger.error("Error creating start", e);
            return Response.serverError().entity("{\"error\": \"Failed to create start\"}").build();
        }
    }

    /** Files the start under the other type of its event (original / reproduction), keeping its id. */
    @POST
    @Path("/{generatedId}/switch-type")
    public Response switchType(@PathParam("competitorId") int competitorId, @PathParam("generatedId") String generatedId) {
        try {
            int toId = disciplineService.partnerDisciplineOfStart(competitorId, generatedId);
            Start switched = teamService.changeStartDiscipline(generatedId, toId,
                () -> startService.switchType(competitorId, generatedId));
            return Response.ok(switched).build();
        } catch (ConflictException e) {
            throw e; // mapped to 409
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid start switch: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            logger.error("Error switching start", e);
            return Response.serverError().entity("{\"error\": \"Failed to switch the start\"}").build();
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