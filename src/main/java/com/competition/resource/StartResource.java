package com.competition.resource;

import com.competition.i18n.Messages;
import com.competition.model.Start;
import com.competition.service.ConflictException;
import com.competition.service.CascadingDeletes;
import com.competition.service.RecordNotFoundException;
import com.competition.service.StartService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@Path("/competitors/{competitorId}/starts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StartResource {
    private static final Logger logger = LoggerFactory.getLogger(StartResource.class);
    
    @Inject
    private StartService startService;

    @Inject
    private CascadingDeletes cascadingDeletes;

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
        } catch (ConflictException | RecordNotFoundException e) {
            throw e; // mapped to 409 / 404
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid start data: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(ErrorBody.of(e.getMessage())).build();
        } catch (Exception e) {
            logger.error("Error creating start", e);
            return Response.serverError().entity(ErrorBody.of(Messages.get("error.failed", "create start"))).build();
        }
    }

    /**
     * Registers the competitor for an entry of a ranking: {"ranking_id", "entry", "caliber"}.
     * Uses the starts the competitor already has in its disciplines, adds the missing ones.
     */
    @POST
    @Path("/rankings")
    public Response registerForRanking(@PathParam("competitorId") int competitorId, Map<String, Object> requestData) {
        try {
            if (!(requestData.get("ranking_id") instanceof Number rankingId)) {
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorBody.of(Messages.get("request.required", "ranking_id"))).build();
            }
            int entry = requestData.get("entry") instanceof Number n ? n.intValue() : 0;
            Object caliberObj = requestData.get("caliber");
            List<Start> starts = startService.registerForRanking(competitorId, rankingId.intValue(), entry,
                caliberObj != null ? caliberObj.toString() : null);
            return Response.ok(starts).build();
        } catch (ConflictException | RecordNotFoundException e) {
            throw e; // mapped to 409 / 404
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid ranking registration: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(ErrorBody.of(e.getMessage())).build();
        } catch (Exception e) {
            logger.error("Error registering for ranking", e);
            return Response.serverError().entity(ErrorBody.of(Messages.get("error.failed", "register for ranking"))).build();
        }
    }

    /** Withdraws the competitor from a ranking; starts left in no ranking are deleted. */
    @DELETE
    @Path("/rankings/{rankingId}")
    public Response withdrawFromRanking(@PathParam("competitorId") int competitorId, @PathParam("rankingId") int rankingId) {
        try {
            cascadingDeletes.withdrawFromRanking(competitorId, rankingId);
            return Response.noContent().build();
        } catch (RecordNotFoundException e) {
            throw e; // mapped to 404
        } catch (Exception e) {
            logger.error("Error withdrawing from ranking", e);
            return Response.serverError().entity(ErrorBody.of(Messages.get("error.failed", "withdraw from ranking"))).build();
        }
    }

    @DELETE
    @Path("/{generatedId}")
    public Response deleteStart(@PathParam("competitorId") int competitorId, @PathParam("generatedId") String generatedId) {
        try {
            cascadingDeletes.deleteStart(competitorId, generatedId);
            return Response.noContent().build();
        } catch (RecordNotFoundException e) {
            throw e; // mapped to 404
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid start deletion: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(ErrorBody.of(e.getMessage())).build();
        } catch (Exception e) {
            logger.error("Error deleting start", e);
            return Response.serverError().entity(ErrorBody.of(Messages.get("error.failed", "delete start"))).build();
        }
    }
}