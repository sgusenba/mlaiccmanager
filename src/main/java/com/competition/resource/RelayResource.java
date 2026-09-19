package com.competition.resource;

import com.competition.service.ConflictException;
import com.competition.service.RecordNotFoundException;
import com.competition.service.RelayService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** Relay management (rmgmt page); all data is stored in relays.json. */
@Path("/rmgmt")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RelayResource {
    private static final Logger logger = LoggerFactory.getLogger(RelayResource.class);

    @Inject
    private RelayService relayService;

    @FunctionalInterface
    private interface Action {
        Object run() throws Exception;
    }

    public RelayResource() {
        // Default constructor for Jersey
    }

    @GET
    public Response getAll() {
        return handle("get relay data", relayService::getAll);
    }

    @PUT
    @Path("/config")
    public Response updateConfig(Map<String, Object> request) {
        return handle("update relay config", () -> relayService.updateConfig(request));
    }

    @POST
    @Path("/days")
    public Response createDay(Map<String, Object> request) {
        return handle("create day", () -> relayService.createDay(request));
    }

    @PUT
    @Path("/days/{id}")
    public Response updateDay(@PathParam("id") String id, Map<String, Object> request) {
        return handle("update day", () -> relayService.updateDay(id, request));
    }

    @DELETE
    @Path("/days/{id}")
    public Response deleteDay(@PathParam("id") String id) {
        return handle("delete day", () -> {
            relayService.deleteDay(id);
            return null;
        });
    }

    @PUT
    @Path("/days/{dayId}/ranges/{rangeId}/lock")
    public Response setLock(@PathParam("dayId") String dayId, @PathParam("rangeId") String rangeId, Map<String, Object> request) {
        return handle("update lock", () -> relayService.setLock(dayId, rangeId, request));
    }

    @POST
    @Path("/days/{id}/relays")
    public Response addRelays(@PathParam("id") String id, Map<String, Object> request) {
        return handle("add relays", () -> relayService.addRelays(id, request));
    }

    @GET
    @Path("/relays/{id}")
    public Response getRelay(@PathParam("id") String id) {
        return handle("get relay", () -> relayService.getRelay(id));
    }

    @DELETE
    @Path("/relays/{id}")
    public Response deleteRelay(@PathParam("id") String id) {
        return handle("delete relay", () -> {
            relayService.deleteRelay(id);
            return null;
        });
    }

    @GET
    @Path("/relays/{id}/available-starts")
    public Response getAvailableStarts(@PathParam("id") String id, @QueryParam("range_id") String rangeId) {
        return handle("get available starts", () -> {
            if (rangeId == null || rangeId.isBlank()) {
                throw new IllegalArgumentException("range_id is required");
            }
            return relayService.getAvailableStarts(id, rangeId);
        });
    }

    @POST
    @Path("/assignments")
    public Response assign(Map<String, Object> request) {
        return handle("assign lane", () -> relayService.assign(request));
    }

    @DELETE
    @Path("/assignments/{id}")
    public Response deleteAssignment(@PathParam("id") String id) {
        return handle("delete assignment", () -> {
            relayService.deleteAssignment(id);
            return null;
        });
    }

    @GET
    @Path("/competitors/{id}/schedule")
    public Response getCompetitorSchedule(@PathParam("id") int id) {
        return handle("get competitor schedule", () -> relayService.getCompetitorSchedule(id));
    }

    @GET
    @Path("/overview")
    public Response getOverview() {
        return handle("get relay overview", relayService::getOverview);
    }

    private Response handle(String what, Action action) {
        try {
            Object result = action.run();
            return result != null ? Response.ok(result).build() : Response.noContent().build();
        } catch (ConflictException | RecordNotFoundException e) {
            throw e; // mapped to 409 / 404
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid request ({}): {}", what, e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", e.getMessage()))
                .build();
        } catch (Exception e) {
            logger.error("Failed to {}", what, e);
            return Response.serverError()
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "Failed to " + what))
                .build();
        }
    }
}
