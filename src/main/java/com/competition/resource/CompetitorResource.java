package com.competition.resource;

import com.competition.model.Competitor;
import com.competition.service.CompetitorService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Path("/competitors")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CompetitorResource {
    private static final Logger logger = LoggerFactory.getLogger(CompetitorResource.class);
    
    @Inject
    private CompetitorService competitorService;

    public CompetitorResource() {
        // Default constructor for Jersey
    }

    @GET
    public Response getAllCompetitors() {
        try {
            List<Competitor> competitors = competitorService.getAllCompetitors();
            return Response.ok(competitors).build();
        } catch (Exception e) {
            logger.error("Error getting competitors", e);
            return Response.serverError().entity("{\"error\": \"Failed to get competitors\"}").build();
        }
    }

    @POST
    public Response createOrUpdateCompetitor(Competitor competitor) {
        try {
            if (competitor.getId() > 0) {
                // Update existing competitor
                Competitor updated = competitorService.updateCompetitor(competitor.getId(), competitor);
                return Response.ok(updated).build();
            } else {
                // Create new competitor
                Competitor created = competitorService.createCompetitor(competitor);
                return Response.ok(created).build();
            }
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid competitor data: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        } catch (Exception e) {
            logger.error("Error creating/updating competitor", e);
            return Response.serverError().entity("{\"error\": \"Failed to create/update competitor\"}").build();
        }
    }

    @PUT
    @Path("/{id}")
    public Response updateCompetitor(@PathParam("id") int id, Competitor competitor) {
        try {
            Competitor updated = competitorService.updateCompetitor(id, competitor);
            return Response.ok(updated).build();
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid competitor data: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        } catch (Exception e) {
            logger.error("Error updating competitor", e);
            return Response.serverError().entity("{\"error\": \"Failed to update competitor\"}").build();
        }
    }

    @DELETE
    @Path("/{id}")
    public Response deleteCompetitor(@PathParam("id") int id) {
        try {
            competitorService.deleteCompetitor(id);
            return Response.noContent().build();
        } catch (Exception e) {
            logger.error("Error deleting competitor", e);
            return Response.serverError().entity("{\"error\": \"Failed to delete competitor\"}").build();
        }
    }
}