package com.competition.resource;

import com.competition.service.ConflictException;
import com.competition.service.RecordNotFoundException;
import com.competition.service.TeamService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** Teams of the team disciplines and their ranking; all teams are stored in teams.json. */
@Path("/teams")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TeamResource {
    private static final Logger logger = LoggerFactory.getLogger(TeamResource.class);

    @Inject
    private TeamService teamService;

    @FunctionalInterface
    private interface Action {
        Object run() throws Exception;
    }

    public TeamResource() {
        // Default constructor for Jersey
    }

    @GET
    public Response getTeams(@QueryParam("discipline_id") Integer disciplineId) {
        return handle("get teams", () -> teamService.getTeams(disciplineId));
    }

    @GET
    @Path("/disciplines")
    public Response getTeamDisciplines() {
        return handle("get team disciplines", teamService::getTeamDisciplines);
    }

    @GET
    @Path("/candidates")
    public Response getCandidates(@QueryParam("discipline_id") Integer disciplineId) {
        return handle("get team candidates", () -> {
            if (disciplineId == null) {
                throw new IllegalArgumentException("discipline_id is required");
            }
            return teamService.getCandidates(disciplineId);
        });
    }

    @GET
    @Path("/ranking/{disciplineId}")
    public Response getRanking(@PathParam("disciplineId") int disciplineId) {
        return handle("get team ranking", () -> {
            Map<String, Object> ranking = teamService.getRanking(disciplineId);
            if (ranking == null) {
                throw new RecordNotFoundException("Team discipline " + disciplineId + " not found");
            }
            return ranking;
        });
    }

    @GET
    @Path("/{id}")
    public Response getTeam(@PathParam("id") int id) {
        return handle("get team", () -> teamService.getTeam(id));
    }

    @POST
    public Response createTeam(Map<String, Object> request) {
        return handle("create team", () -> teamService.createTeam(request));
    }

    @PUT
    @Path("/{id}")
    public Response updateTeam(@PathParam("id") int id, Map<String, Object> request) {
        return handle("update team", () -> teamService.updateTeam(id, request));
    }

    @DELETE
    @Path("/{id}")
    public Response deleteTeam(@PathParam("id") int id, @QueryParam("version") Integer version) {
        return handle("delete team", () -> {
            teamService.deleteTeam(id, version);
            return null;
        });
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
