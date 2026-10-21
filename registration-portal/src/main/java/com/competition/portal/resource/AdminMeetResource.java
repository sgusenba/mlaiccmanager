package com.competition.portal.resource;

import com.competition.portal.model.Meet;
import com.competition.portal.service.MeetService;
import com.competition.portal.service.SnapshotService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The organiser's meets: list, create, change (settings and status), delete,
 * and upload the rankings and catalog exported from the Manager.
 * Errors are answered by the mappers in ErrorMappers (400, 404, 409).
 */
@Path("/admin/meets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminMeetResource {

    @Inject
    private MeetService meetService;

    @GET
    public List<Map<String, Object>> list() throws Exception {
        return meetService.list().stream().map(MeetService::view).collect(Collectors.toList());
    }

    @POST
    public Map<String, Object> create(Map<String, Object> request) throws Exception {
        return MeetService.view(meetService.create(request));
    }

    @GET
    @Path("/{id: \\d+}")
    public Map<String, Object> get(@PathParam("id") int id) {
        return MeetService.view(meetService.get(id));
    }

    @PUT
    @Path("/{id: \\d+}")
    public Map<String, Object> update(@PathParam("id") int id, Map<String, Object> request) throws Exception {
        return MeetService.view(meetService.update(id, request));
    }

    @DELETE
    @Path("/{id: \\d+}")
    public Response delete(@PathParam("id") int id) throws Exception {
        meetService.delete(id);
        return Response.noContent().build();
    }

    /** Body: {"rankings": <the Manager's rankings export>, "catalog": <its discipline catalog export>}. */
    @PUT
    @Path("/{id: \\d+}/snapshot")
    public MeetService.UploadResult uploadSnapshot(@PathParam("id") int id, JsonNode body) throws Exception {
        return meetService.uploadSnapshot(id, body != null ? body.get("rankings") : null, body != null ? body.get("catalog") : null);
    }

    /** What clubs can register for: the individual rankings with their entries. */
    @GET
    @Path("/{id: \\d+}/offer")
    public Object offer(@PathParam("id") int id) {
        Meet meet = meetService.get(id);
        return SnapshotService.offer(meet.snapshot);
    }
}
