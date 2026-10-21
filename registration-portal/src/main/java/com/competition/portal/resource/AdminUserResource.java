package com.competition.portal.resource;

import com.competition.portal.PortalServices;
import com.competition.portal.auth.Session;
import com.competition.portal.auth.SessionCookie;
import com.competition.portal.model.Settings;
import com.competition.portal.service.OrganiserService;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

import static com.competition.portal.resource.AuthResource.text;

/** The organiser accounts (e.g. a second organiser), the own password and the authenticator app. */
@Path("/admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminUserResource {

    @Inject
    private PortalServices services;

    @Context
    private HttpServletRequest request;

    private int me() {
        return ((Session) request.getAttribute(Session.ATTRIBUTE)).organiserId;
    }

    @GET
    @Path("/organisers")
    public List<Map<String, Object>> organisers() {
        return services.organiserService.list();
    }

    /** Body: {"username", "password"}: an account for a further organiser, who can change the password later. */
    @POST
    @Path("/organisers")
    public Map<String, Object> create(Map<String, Object> body) throws Exception {
        return OrganiserService.view(services.organiserService.create(text(body, "username"), text(body, "password")));
    }

    @DELETE
    @Path("/organisers/{id: \\d+}")
    public Response delete(@PathParam("id") int id) throws Exception {
        services.organiserService.delete(id);
        return Response.noContent().build();
    }

    /** Body: {"current", "password"}. Ends the account's other sessions; this one gets a new cookie. */
    @PUT
    @Path("/password")
    public Response password(Map<String, Object> body) throws Exception {
        String token = services.organiserService.changePassword(me(), text(body, "current"), text(body, "password"));
        return Response.noContent().header("Set-Cookie", SessionCookie.set(token, services.secure())).build();
    }

    /** The portal's settings: the data controller shown on the privacy page. */
    @GET
    @Path("/settings")
    public Settings settings() {
        return services.portalStore.get().settings;
    }

    /** Body: {"controller": {"name", "address", "email"}}. */
    @PUT
    @Path("/settings")
    public Settings updateSettings(Map<String, Object> body) throws Exception {
        return SettingsResource.update(services.portalStore, body);
    }

    /** Starts setting up the authenticator app: {"secret", "otpauth_uri"} for the QR code. */
    @POST
    @Path("/totp/setup")
    public Map<String, Object> totpSetup() throws Exception {
        return services.organiserService.totpSetup(me());
    }

    /** Body: {"code"} from the app; from now on it is asked at log-in. */
    @POST
    @Path("/totp/confirm")
    public Response totpConfirm(Map<String, Object> body) throws Exception {
        services.organiserService.totpConfirm(me(), text(body, "code"));
        return Response.noContent().build();
    }

    /** Body: {"password"}: turns the authenticator app off. */
    @POST
    @Path("/totp/disable")
    public Response totpDisable(Map<String, Object> body) throws Exception {
        services.organiserService.totpDisable(me(), text(body, "password"));
        return Response.noContent().build();
    }
}
