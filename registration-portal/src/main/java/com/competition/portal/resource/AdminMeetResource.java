package com.competition.portal.resource;

import com.competition.portal.auth.SessionStore;
import com.competition.portal.model.Meet;
import com.competition.portal.service.AuditLog;
import com.competition.portal.service.ExportService;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;
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

    @Inject
    private SessionStore sessions;

    @Inject
    private AuditLog auditLog;

    @Inject
    private ExportService exportService;

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
        sessions.endWhere(s -> s.isClub() && s.meetId == id);
        return Response.noContent().build();
    }

    /** Body: {"rankings": <the Manager's rankings export>, "catalog": <its discipline catalog export>}. */
    @PUT
    @Path("/{id: \\d+}/snapshot")
    public MeetService.UploadResult uploadSnapshot(@PathParam("id") int id, JsonNode body) throws Exception {
        return meetService.uploadSnapshot(id, body != null ? body.get("rankings") : null, body != null ? body.get("catalog") : null);
    }

    /** The file for the Manager's Import Registrations page: all clubs' starters. */
    @GET
    @Path("/{id: \\d+}/export")
    public Response export(@PathParam("id") int id) {
        return Response.ok(exportService.meet(id))
            .header("Content-Disposition", "attachment; filename=\"" + exportService.fileName(id, null) + "\"").build();
    }

    /** The meet's audit log, newest first. */
    @GET
    @Path("/{id: \\d+}/audit")
    public List<Map<String, Object>> audit(@PathParam("id") int id,
                                           @QueryParam("offset") @DefaultValue("0") int offset,
                                           @QueryParam("limit") @DefaultValue("100") int limit) throws Exception {
        meetService.get(id);
        return auditLog.read(id, Math.max(0, offset), Math.max(1, Math.min(limit, 1000)));
    }

    /** What clubs can register for: the individual rankings with their entries. */
    @GET
    @Path("/{id: \\d+}/offer")
    public Object offer(@PathParam("id") int id) {
        Meet meet = meetService.get(id);
        return SnapshotService.offer(meet.snapshot);
    }
}
