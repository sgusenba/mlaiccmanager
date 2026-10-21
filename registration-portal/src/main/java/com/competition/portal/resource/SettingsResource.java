package com.competition.portal.resource;

import com.competition.portal.i18n.Messages;
import com.competition.portal.model.Settings;
import com.competition.portal.service.PortalStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/**
 * Who is responsible for the data (the data controller under the GDPR),
 * shown on the privacy page to everyone. The organiser sets it through
 * GET/PUT /api/admin/settings, in AdminUserResource: JAX-RS picks one
 * resource class per path, and /admin/... belongs to that class.
 */
@Path("/privacy")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SettingsResource {
    private static final int MAX_TEXT = 300;

    @Inject
    private PortalStore store;

    /** For the privacy page: {"controller": {name, address, email}}. */
    @GET
    public Map<String, Object> privacy() {
        return Map.of("controller", store.get().settings.controller);
    }

    /** The organiser's PUT /api/admin/settings (in AdminUserResource, whose path covers /admin). */
    static Settings update(PortalStore store, Map<String, Object> body) throws Exception {
        Settings.Controller controller = new Settings.Controller();
        if (body != null && body.get("controller") instanceof Map<?, ?> c) {
            controller.name = text(c, "name");
            controller.address = text(c, "address");
            controller.email = text(c, "email");
        }
        return store.update(data -> {
            data.settings.controller = controller;
            return data.settings;
        });
    }

    private static String text(Map<?, ?> map, String field) {
        Object value = map.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException(Messages.get("request.mustBeText", field));
        }
        String trimmed = s.trim();
        if (trimmed.length() > MAX_TEXT) {
            throw new IllegalArgumentException(Messages.get("request.tooLong", field, MAX_TEXT));
        }
        return trimmed.isEmpty() ? null : trimmed;
    }
}
