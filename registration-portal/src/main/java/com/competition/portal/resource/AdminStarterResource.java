package com.competition.portal.resource;

import com.competition.portal.auth.Session;
import com.competition.portal.i18n.Messages;
import com.competition.portal.model.Meet;
import com.competition.portal.service.MeetService;
import com.competition.portal.service.StarterService;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/**
 * All starters of a meet for the organiser, and deleting all registrations
 * after the meet. Editing a club's starters is in AdminClubResource, the
 * audit log in AdminMeetResource.
 */
@Path("/admin/meets/{meetId: \\d+}/starters")
@Produces(MediaType.APPLICATION_JSON)
public class AdminStarterResource {

    @Inject
    private StarterService starters;

    @Inject
    private MeetService meetService;

    @Context
    private HttpServletRequest request;

    /** {starters, warnings}; the warnings include names registered by two clubs. */
    @GET
    public Map<String, Object> list(@PathParam("meetId") int meetId) {
        return starters.listAll(meetId);
    }

    /**
     * Deletes all registrations of the meet (starters, contact persons, audit
     * log); the meet and its clubs stay. ?confirm= must be the meet's key, so
     * it cannot happen by accident.
     */
    @DELETE
    public Map<String, Object> purge(@PathParam("meetId") int meetId, @QueryParam("confirm") String confirm) throws Exception {
        Meet meet = meetService.get(meetId);
        if (confirm == null || !confirm.equals(meet.key)) {
            throw new IllegalArgumentException(Messages.get("purge.confirm", meet.key));
        }
        String actor = ((Session) request.getAttribute(Session.ATTRIBUTE)).actor();
        return Map.of("deleted", starters.purge(meetId, actor));
    }
}
