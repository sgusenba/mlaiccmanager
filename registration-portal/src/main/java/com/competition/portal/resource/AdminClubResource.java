package com.competition.portal.resource;

import com.competition.portal.auth.Session;
import com.competition.portal.service.ClubService;
import com.competition.portal.service.StarterService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Context;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * The clubs of a meet and their access codes. Creating a club and replacing
 * its code answer the code and invite link; that is the only time they are
 * shown.
 */
@Path("/admin/meets/{meetId: \\d+}/clubs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminClubResource {

    @Inject
    private ClubService clubService;

    @Inject
    private StarterService starters;

    @Context
    private HttpServletRequest request;

    private String actor() {
        return ((Session) request.getAttribute(Session.ATTRIBUTE)).actor();
    }

    @GET
    public List<Map<String, Object>> list(@PathParam("meetId") int meetId) {
        return clubService.list(meetId);
    }

    /** Body: {"name", "default_country"}. */
    @POST
    public ClubService.WithCode create(@PathParam("meetId") int meetId, Map<String, Object> body) throws Exception {
        return clubService.create(meetId, body);
    }

    /** Body: {"name", "default_country", "version"}. */
    @PUT
    @Path("/{clubId: \\d+}")
    public Map<String, Object> update(@PathParam("meetId") int meetId, @PathParam("clubId") int clubId,
                                      Map<String, Object> body) throws Exception {
        return clubService.update(meetId, clubId, body);
    }

    /** Refused (409) while the club has starters, unless ?with_starters=true. */
    @DELETE
    @Path("/{clubId: \\d+}")
    public Response delete(@PathParam("meetId") int meetId, @PathParam("clubId") int clubId,
                           @QueryParam("with_starters") boolean withStarters) throws Exception {
        clubService.delete(meetId, clubId, withStarters);
        return Response.noContent().build();
    }

    /** A new code; the old one stops working and the club is logged out. */
    @POST
    @Path("/{clubId: \\d+}/code")
    public ClubService.WithCode rotate(@PathParam("meetId") int meetId, @PathParam("clubId") int clubId) throws Exception {
        return clubService.rotate(meetId, clubId);
    }

    /** The club's sheet as the organiser edits it (the same shape as GET /api/club/meet, always editable). */
    @GET
    @Path("/{clubId: \\d+}/sheet")
    public Map<String, Object> sheet(@PathParam("meetId") int meetId, @PathParam("clubId") int clubId) {
        return starters.organiserSheet(meetId, clubId);
    }

    @GET
    @Path("/{clubId: \\d+}/starters")
    public Map<String, Object> listStarters(@PathParam("meetId") int meetId, @PathParam("clubId") int clubId) {
        return starters.list(meetId, clubId);
    }

    /** Changes the club's starters, with the same body as the club's own save; the deadline does not apply. */
    @PUT
    @Path("/{clubId: \\d+}/starters")
    public Map<String, Object> saveStarters(@PathParam("meetId") int meetId, @PathParam("clubId") int clubId,
                                            Map<String, Object> body) throws Exception {
        return starters.save(meetId, clubId, body, actor(), true);
    }

    /** The club's contact person: {"name", "email", "version"}. */
    @PUT
    @Path("/{clubId: \\d+}/contact")
    public Map<String, Object> contact(@PathParam("meetId") int meetId, @PathParam("clubId") int clubId,
                                       Map<String, Object> body) throws Exception {
        return starters.saveContact(meetId, clubId, body, actor(), true);
    }

    /** Body: {"revoked": true|false}; blocking logs the club out. */
    @POST
    @Path("/{clubId: \\d+}/revoke")
    public Map<String, Object> revoke(@PathParam("meetId") int meetId, @PathParam("clubId") int clubId,
                                      Map<String, Object> body) throws Exception {
        boolean revoked = body == null || !Boolean.FALSE.equals(body.get("revoked"));
        return clubService.revoke(meetId, clubId, revoked);
    }
}
