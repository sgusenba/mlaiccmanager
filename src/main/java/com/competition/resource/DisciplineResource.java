package com.competition.resource;

import com.competition.service.ConflictException;
import com.competition.service.DisciplineService;
import com.competition.service.RecordNotFoundException;
import com.competition.service.TeamService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * The installation's discipline catalog (/available-disciplines), its settings
 * and export/import, and the meet's program (/active-disciplines: the
 * disciplines its rankings cover).
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DisciplineResource {
    private static final Logger logger = LoggerFactory.getLogger(DisciplineResource.class);

    @Inject
    private DisciplineService disciplineService;

    @Inject
    private TeamService teamService;

    @FunctionalInterface
    private interface Action {
        Object run() throws Exception;
    }

    public DisciplineResource() {
        // Default constructor for Jersey
    }

    /** The meet's program: the ids of the disciplines its rankings cover. */
    @GET
    @Path("/active-disciplines")
    public Response getProgram() {
        return handle("get the program", disciplineService::getProgramIds);
    }

    @GET
    @Path("/available-disciplines")
    public Response getAvailableDisciplines() {
        return handle("get disciplines", disciplineService::getAvailableDisciplines);
    }

    @POST
    @Path("/available-disciplines")
    public Response createCatalogDiscipline(Map<String, Object> requestData) {
        try {
            return Response.status(Response.Status.CREATED)
                .entity(disciplineService.createCatalogDiscipline(requestData)).build();
        } catch (ConflictException e) {
            throw e; // mapped to 409
        } catch (IllegalArgumentException | ClassCastException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            logger.error("Error creating discipline", e);
            return Response.serverError().entity(Map.of("error", "Failed to create discipline")).build();
        }
    }

    @PUT
    @Path("/available-disciplines/{id}")
    public Response updateCatalogDiscipline(@PathParam("id") int id, Map<String, Object> requestData) {
        // A new team composition or level must not leave a stored team or member's start out
        return handle("update discipline", () -> teamService.changeComposition(id, requestData,
            () -> disciplineService.updateCatalogDiscipline(id, requestData)));
    }

    @DELETE
    @Path("/available-disciplines/{id}")
    public Response deleteCatalogDiscipline(@PathParam("id") int id) {
        // Teams, then data.json and the catalog: nothing may refer to the discipline once it is gone
        return handle("delete discipline", () -> teamService.withoutTeams(id, () -> {
            disciplineService.deleteCatalogDiscipline(id);
            return null;
        }));
    }

    @PUT
    @Path("/available-disciplines/shooting-distances")
    public Response updateShootingDistances(Map<String, Object> requestData) {
        return handle("update shooting distances", () -> {
            @SuppressWarnings("unchecked")
            Map<String, String> mapping = requestData != null
                ? (Map<String, String>) requestData.get("shooting_distances") : null;
            if (mapping == null) {
                throw new IllegalArgumentException("shooting_distances is required");
            }
            return disciplineService.updateShootingDistances(mapping);
        });
    }

    @GET
    @Path("/disciplines/settings")
    public Response getSettings() {
        return handle("get discipline settings", disciplineService::getSettings);
    }

    @PUT
    @Path("/disciplines/settings")
    public Response setSettings(Map<String, Object> requestData) {
        return handle("save discipline settings", () -> {
            if (requestData == null || !(requestData.get("max_disciplines") instanceof Number max)) {
                throw new IllegalArgumentException("max_disciplines is required");
            }
            return disciplineService.setMaxDisciplines(max.intValue());
        });
    }

    @GET
    @Path("/disciplines/export")
    public Response exportCatalog() {
        return handle("export disciplines", disciplineService::exportCatalog);
    }

    @POST
    @Path("/disciplines/import")
    public Response importCatalog(@QueryParam("mode") String mode, JsonNode file) {
        return handle("import disciplines", () -> disciplineService.importCatalog(file, mode));
    }

    private Response handle(String what, Action action) {
        try {
            Object result = action.run();
            return result != null ? Response.ok(result).build() : Response.noContent().build();
        } catch (ConflictException | RecordNotFoundException e) {
            throw e; // mapped to 409 / 404
        } catch (IllegalArgumentException | ClassCastException | JsonProcessingException e) {
            logger.warn("Invalid request ({}): {}", what, e.getMessage());
            return badRequest(e.getMessage());
        } catch (Exception e) {
            logger.error("Failed to {}", what, e);
            return Response.serverError()
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "Failed to " + what))
                .build();
        }
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
            .type(MediaType.APPLICATION_JSON)
            .entity(ErrorBody.of(message))
            .build();
    }
}
