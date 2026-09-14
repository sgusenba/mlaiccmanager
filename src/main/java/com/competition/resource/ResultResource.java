package com.competition.resource;

import com.competition.model.Result;
import com.competition.service.ResultService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@Path("/results")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ResultResource {
    private static final Logger logger = LoggerFactory.getLogger(ResultResource.class);
    
    @Inject
    private ResultService resultService;

    public ResultResource() {
        // Default constructor for Jersey
    }

    @GET
    public Response getResults(@QueryParam("discipline_id") Integer disciplineId) {
        try {
            List<Map<String, Object>> results = resultService.getResults(disciplineId);
            return Response.ok(results).build();
        } catch (Exception e) {
            logger.error("Error getting results", e);
            return Response.serverError().entity("{\"error\": \"Failed to get results\"}").build();
        }
    }

    @POST
    public Response createOrUpdateResult(Result result) {
        try {
            if (result.getId() > 0) {
                // Update existing result
                Result updated = resultService.updateResult(result.getId(), result);
                return Response.ok(updated).build();
            } else {
                // Create new result
                Result created = resultService.createResult(result);
                return Response.ok(created).build();
            }
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid result data: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        } catch (Exception e) {
            logger.error("Error creating/updating result", e);
            return Response.serverError().entity("{\"error\": \"Failed to create/update result\"}").build();
        }
    }

    @PUT
    @Path("/{id}")
    public Response updateResult(@PathParam("id") int id, Result result) {
        try {
            Result updated = resultService.updateResult(id, result);
            return Response.ok(updated).build();
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid result data: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        } catch (Exception e) {
            logger.error("Error updating result", e);
            return Response.serverError().entity("{\"error\": \"Failed to update result\"}").build();
        }
    }

    @DELETE
    @Path("/{id}")
    public Response deleteResult(@PathParam("id") int id) {
        try {
            resultService.deleteResult(id);
            return Response.noContent().build();
        } catch (Exception e) {
            logger.error("Error deleting result", e);
            return Response.serverError().entity("{\"error\": \"Failed to delete result\"}").build();
        }
    }
}