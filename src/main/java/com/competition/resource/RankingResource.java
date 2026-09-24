package com.competition.resource;

import com.competition.service.RankingService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Path("/ranking")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RankingResource {
    private static final Logger logger = LoggerFactory.getLogger(RankingResource.class);

    @Inject
    private RankingService rankingService;

    public RankingResource() {
        // Default constructor for Jersey
    }

    @GET
    public Response getAllRankings() {
        try {
            Map<Integer, Object> rankings = rankingService.getAllRankings();
            return Response.ok(rankings).build();
        } catch (Exception e) {
            logger.error("Error getting all rankings", e);
            return Response.serverError().entity("{\"error\": \"Failed to get rankings\"}").build();
        }
    }

    @GET
    @Path("/best")
    public Response getAllBestResultRankings() {
        try {
            return Response.ok(rankingService.getAllBestResultRankings()).build();
        } catch (Exception e) {
            logger.error("Error getting best-result rankings", e);
            return Response.serverError().entity("{\"error\": \"Failed to get rankings\"}").build();
        }
    }

    @GET
    @Path("/best/{disciplineId}")
    public Response getBestResultRanking(@PathParam("disciplineId") int disciplineId) {
        try {
            Map<String, Object> ranking = rankingService.getBestResultRanking(disciplineId);
            if (ranking == null) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"Discipline not found\"}").build();
            }
            return Response.ok(ranking).build();
        } catch (Exception e) {
            logger.error("Error getting best-result ranking", e);
            return Response.serverError().entity("{\"error\": \"Failed to get ranking\"}").build();
        }
    }

    @GET
    @Path("/{disciplineId}")
    public Response getRanking(@PathParam("disciplineId") int disciplineId) {
        try {
            Map<String, Object> ranking = rankingService.getRanking(disciplineId);
            if (ranking == null) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"Discipline not found\"}").build();
            }
            return Response.ok(ranking).build();
        } catch (Exception e) {
            logger.error("Error getting ranking", e);
            return Response.serverError().entity("{\"error\": \"Failed to get ranking\"}").build();
        }
    }
}
