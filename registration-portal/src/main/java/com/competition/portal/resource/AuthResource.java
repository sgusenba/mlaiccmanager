package com.competition.portal.resource;

import com.competition.portal.PortalServices;
import com.competition.portal.auth.Session;
import com.competition.portal.auth.SessionCookie;
import com.competition.portal.i18n.Messages;
import com.competition.portal.model.Club;
import com.competition.portal.model.Meet;
import com.competition.portal.model.Organiser;
import com.competition.portal.service.RecordNotFoundException;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Log-in and log-out, and who is logged in. A successful log-in answers 204
 * with the session cookie; failures are answered by ErrorMappers.Auth.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    @Inject
    private PortalServices services;

    @Context
    private HttpServletRequest request;

    /** Body: {"username", "password", "totp"}; totp only once the authenticator app is on (the answer says so). */
    @POST
    @Path("/auth/organiser")
    public Response organiser(Map<String, Object> body) throws Exception {
        String token = services.authService.organiserLogin(text(body, "username"), text(body, "password"),
            text(body, "totp"), request.getRemoteAddr());
        return withCookie(token);
    }

    /** Body: {"code"}, the club's access code as typed or from the invite link. */
    @POST
    @Path("/auth/club")
    public Response club(Map<String, Object> body) throws Exception {
        return withCookie(services.authService.clubLogin(text(body, "code"), request.getRemoteAddr()));
    }

    @POST
    @Path("/auth/logout")
    public Response logout() {
        services.sessions.end(SessionCookie.token(request, services.secure()));
        return Response.noContent().header("Set-Cookie", SessionCookie.clear(services.secure())).build();
    }

    /** Who is logged in: {"role": "organiser", "username"} or {"role": "club", "meet": {...}, "club": {...}}; 401 if nobody. */
    @GET
    @Path("/session")
    public Response session() {
        Session session = (Session) request.getAttribute(Session.ATTRIBUTE);
        Map<String, Object> view = new LinkedHashMap<>();
        try {
            if (session != null && session.isOrganiser()) {
                Organiser o = services.organiserService.get(session.organiserId);
                view.put("role", Session.ORGANISER);
                view.put("id", o.id);
                view.put("username", o.username);
                view.put("totp_enabled", o.totpConfirmed);
                return Response.ok(view).build();
            }
            if (session != null && session.isClub()) {
                Meet meet = services.meetStore.get(session.meetId);
                Club club = meet.clubs.stream().filter(c -> c.id == session.clubId && !c.revoked).findFirst().orElse(null);
                if (club != null) {
                    view.put("role", Session.CLUB);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", meet.id);
                    m.put("name", meet.name);
                    m.put("status", meet.status);
                    m.put("deadline", meet.deadline);
                    m.put("time_zone", meet.timeZone);
                    view.put("meet", m);
                    view.put("club", Map.of("id", club.id, "name", club.name));
                    return Response.ok(view).build();
                }
            }
        } catch (RecordNotFoundException e) {
            // account, meet or club deleted meanwhile: logged out
        }
        return Response.status(401).entity(Map.of("error", Messages.get("auth.notLoggedIn"))).build();
    }

    private Response withCookie(String token) {
        return Response.noContent().header("Set-Cookie", SessionCookie.set(token, services.secure())).build();
    }

    static String text(Map<String, Object> body, String field) {
        Object value = body != null ? body.get(field) : null;
        return value instanceof String s ? s : null;
    }
}
