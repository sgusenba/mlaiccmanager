package com.competition.portal.resource;

import com.competition.portal.auth.Session;
import com.competition.portal.service.StarterService;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/**
 * A logged-in club's own registrations. Which meet and club comes from the
 * session (AuthFilter), never from the address, so a club can reach nothing
 * but its own rows.
 */
@Path("/club")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ClubResource {

    @Inject
    private StarterService starters;

    @Context
    private HttpServletRequest request;

    private Session session() {
        return (Session) request.getAttribute(Session.ATTRIBUTE);
    }

    /** The meet: deadline, whether changes are still possible, the club's details and the rankings to choose from. */
    @GET
    @Path("/meet")
    public Map<String, Object> meet() {
        Session s = session();
        return starters.clubMeet(s.meetId, s.clubId);
    }

    @GET
    @Path("/starters")
    public Map<String, Object> list() {
        Session s = session();
        return starters.list(s.meetId, s.clubId);
    }

    /** Saves the sheet: {"upsert": [...], "delete": [{"id", "version"}], "consent": true on the first save}. */
    @PUT
    @Path("/starters")
    public Map<String, Object> save(Map<String, Object> body) throws Exception {
        Session s = session();
        return starters.save(s.meetId, s.clubId, body, s.actor(), false);
    }

    /** The club's contact person: {"name", "email", "version"}. */
    @PUT
    @Path("/contact")
    public Map<String, Object> contact(Map<String, Object> body) throws Exception {
        Session s = session();
        return starters.saveContact(s.meetId, s.clubId, body, s.actor(), false);
    }
}
