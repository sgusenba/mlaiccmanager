package com.competition.resource;

import com.competition.model.Discipline;
import com.competition.service.DisciplineService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DisciplineResource {
    private static final Logger logger = LoggerFactory.getLogger(DisciplineResource.class);
    
    @Inject
    private DisciplineService disciplineService;

    public DisciplineResource() {
        // Default constructor for Jersey
    }

    @GET
    @Path("/active-disciplines")
    public Response getActiveDisciplines() {
        try {
            List<Integer> activeDisciplines = disciplineService.getActiveDisciplines();
            return Response.ok(activeDisciplines).build();
        } catch (Exception e) {
            logger.error("Error getting active disciplines", e);
            return Response.serverError().entity("{\"error\": \"Failed to get active disciplines\"}").build();
        }
    }

    @POST
    @Path("/active-disciplines")
    public Response setActiveDisciplines(Map<String, Object> requestData) {
        try {
            List<Integer> disciplineIds = new ArrayList<>();
            Object rawIds = requestData != null ? requestData.get("discipline_ids") : null;
            if (rawIds instanceof List) {
                for (Object idObj : (List<?>) rawIds) {
                    if (idObj instanceof Number) {
                        disciplineIds.add(((Number) idObj).intValue());
                    }
                }
            }

            List<Integer> activeDisciplines = disciplineService.setActiveDisciplines(disciplineIds);
            return Response.ok(activeDisciplines).build();
        } catch (Exception e) {
            logger.error("Error setting active disciplines", e);
            return Response.serverError().entity("{\"error\": \"Failed to set active disciplines\"}").build();
        }
    }

    @GET
    @Path("/available-disciplines")
    public Response getAvailableDisciplines() {
        try {
            List<Discipline> availableDisciplines = disciplineService.getAvailableDisciplines();
            return Response.ok(availableDisciplines).build();
        } catch (Exception e) {
            logger.error("Error getting available disciplines", e);
            return Response.serverError().entity("{\"error\": \"Failed to get available disciplines\"}").build();
        }
    }

    @GET
    @Path("/disciplines")
    public Response getCustomDisciplines() {
        try {
            List<Map<String, Object>> customDisciplines = disciplineService.getCustomDisciplines();
            return Response.ok(customDisciplines).build();
        } catch (Exception e) {
            logger.error("Error getting custom disciplines", e);
            return Response.serverError().entity("{\"error\": \"Failed to get custom disciplines\"}").build();
        }
    }

    @POST
    @Path("/disciplines")
    public Response createDiscipline(Map<String, Object> requestData) {
        try {
            Map<String, Object> created = disciplineService.createDiscipline(requestData);
            return Response.status(Response.Status.CREATED).entity(created).build();
        } catch (Exception e) {
            logger.error("Error creating discipline", e);
            return Response.serverError().entity("{\"error\": \"Failed to create discipline\"}").build();
        }
    }

    @PUT
    @Path("/disciplines/{id}")
    public Response updateDiscipline(@PathParam("id") int id, Map<String, Object> requestData) {
        try {
            Map<String, Object> updated = disciplineService.updateDiscipline(id, requestData);
            return Response.ok(updated).build();
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid discipline data: {}", e.getMessage());
            return Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        } catch (Exception e) {
            logger.error("Error updating discipline", e);
            return Response.serverError().entity("{\"error\": \"Failed to update discipline\"}").build();
        }
    }

    @DELETE
    @Path("/disciplines/{id}")
    public Response deleteDiscipline(@PathParam("id") int id) {
        try {
            disciplineService.deleteDiscipline(id);
            return Response.noContent().build();
        } catch (Exception e) {
            logger.error("Error deleting discipline", e);
            return Response.serverError().entity("{\"error\": \"Failed to delete discipline\"}").build();
        }
    }
}