package com.competition.resource;

import com.competition.i18n.Messages;
import com.competition.service.ConflictException;
import com.competition.service.RankingConfigService;
import com.competition.service.RankingService;
import com.competition.service.RecordNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/** The meet's rankings (rankings.json): their configuration, export/import, and their results. */
@Path("/rankings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RankingResource {
    private static final Logger logger = LoggerFactory.getLogger(RankingResource.class);

    @Inject
    private RankingConfigService rankingConfigService;

    @Inject
    private RankingService rankingService;

    @FunctionalInterface
    private interface Action {
        Object run() throws Exception;
    }

    public RankingResource() {
        // Default constructor for Jersey
    }

    @GET
    public Response getConfig() {
        return handle("get rankings", rankingConfigService::getConfig);
    }

    @POST
    public Response createRanking(Map<String, Object> request) {
        return handle("create ranking", () -> rankingConfigService.createRanking(request));
    }

    @GET
    @Path("/{id: \\d+}")
    public Response getRanking(@PathParam("id") int id) {
        return handle("get ranking", () -> rankingConfigService.getRanking(id));
    }

    @PUT
    @Path("/{id: \\d+}")
    public Response updateRanking(@PathParam("id") int id, Map<String, Object> request) {
        return handle("update ranking", () -> rankingConfigService.updateRanking(id, request));
    }

    @DELETE
    @Path("/{id: \\d+}")
    public Response deleteRanking(@PathParam("id") int id, @QueryParam("version") Integer version) {
        return handle("delete ranking", () -> {
            rankingConfigService.deleteRanking(id, version);
            return null;
        });
    }

    @PUT
    @Path("/order")
    public Response reorder(Map<String, Object> request) {
        return handle("reorder rankings", () -> {
            if (request == null || !(request.get("ids") instanceof List<?> ids)) {
                throw new IllegalArgumentException(Messages.get("request.required", "ids"));
            }
            return rankingConfigService.reorder(ids.stream().map(i -> ((Number) i).intValue()).toList());
        });
    }

    @PUT
    @Path("/settings")
    public Response setSettings(Map<String, Object> request) {
        return handle("save ranking settings", () -> {
            if (request == null || !(request.get("max_disciplines_per_ranking") instanceof Number max)) {
                throw new IllegalArgumentException(Messages.get("request.required", "max_disciplines_per_ranking"));
            }
            return rankingConfigService.setMaxDisciplines(max.intValue());
        });
    }

    /** The meet's rankings by the id of each discipline they cover. */
    @GET
    @Path("/by-discipline")
    public Response getByDiscipline() {
        return handle("get rankings by discipline", rankingConfigService::getRankingsByDiscipline);
    }

    @GET
    @Path("/export")
    public Response exportRankings() {
        return handle("export rankings", rankingConfigService::exportRankings);
    }

    @POST
    @Path("/import")
    public Response importRankings(JsonNode file) {
        return handle("import rankings", () -> rankingConfigService.importRankings(file));
    }

    @POST
    @Path("/defaults")
    public Response loadDefaults() {
        return handle("load the default rankings", rankingConfigService::loadDefaults);
    }

    /** Every ranking's result, in the meet's order, leaving out those with nothing to rank yet. */
    @GET
    @Path("/results")
    public Response getAllResults() {
        return handle("get ranking results", rankingService::getAllRankingResults);
    }

    @GET
    @Path("/{id: \\d+}/result")
    public Response getResult(@PathParam("id") int id) {
        return handle("get ranking result", () -> {
            Map<String, Object> result = rankingService.getRankingResult(id);
            if (result == null) {
                throw new RecordNotFoundException(Messages.get("ranking.notFound", id));
            }
            return result;
        });
    }

    private Response handle(String what, Action action) {
        try {
            Object result = action.run();
            return result != null ? Response.ok(result).build() : Response.noContent().build();
        } catch (ConflictException | RecordNotFoundException e) {
            throw e; // mapped to 409 / 404
        } catch (IllegalArgumentException | JsonProcessingException e) {
            logger.warn("Invalid request ({}): {}", what, e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorBody.of(e.getMessage()))
                .build();
        } catch (Exception e) {
            logger.error("Failed to {}", what, e);
            return Response.serverError()
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorBody.of(Messages.get("error.failed", what)))
                .build();
        }
    }
}
