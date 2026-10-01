package com.competition.resource;

import com.competition.model.AppSettings;
import com.competition.service.BackupService;
import com.competition.service.ExternalBackupService;
import com.competition.service.SettingsService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Download all runtime data as a zip, and restore it from one. */
@Path("/backup")
public class BackupResource {
    private static final Logger logger = LoggerFactory.getLogger(BackupResource.class);

    @Inject
    private BackupService backupService;

    @Inject
    private SettingsService settingsService;

    @Inject
    private ExternalBackupService externalBackupService;

    public BackupResource() {
        // Default constructor for Jersey
    }

    @GET
    @Produces("application/zip")
    public Response download() {
        try {
            byte[] zip = backupService.createBackup();
            return Response.ok(zip, "application/zip")
                .header("Content-Disposition", "attachment; filename=\"" + BackupService.backupFileName() + "\"")
                .header("Cache-Control", "no-store")
                .build();
        } catch (Exception e) {
            logger.error("Failed to create backup", e);
            return Response.serverError()
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "Failed to create backup"))
                .build();
        }
    }

    /** The request body is the zip file itself, whatever content type the browser gives it. */
    @POST
    @Path("/restore")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public Response restore(InputStream zip) {
        try {
            return Response.ok(backupService.restore(zip)).build();
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid backup: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", e.getMessage()))
                .build();
        } catch (Exception e) {
            logger.error("Failed to restore backup", e);
            return Response.serverError()
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "Failed to restore backup"))
                .build();
        }
    }

    /** The external backup settings and what the last pushes did. */
    @GET
    @Path("/external")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> getExternal() {
        return externalState(settingsService.getExternalBackup());
    }

    @PUT
    @Path("/external")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateExternal(AppSettings.ExternalBackup settings) {
        try {
            return Response.ok(externalState(settingsService.updateExternalBackup(settings))).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            logger.error("Failed to save the external backup settings", e);
            return Response.serverError().type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "Failed to save the settings")).build();
        }
    }

    /** Checks the receiver at the posted settings' URL, which need not be saved yet. */
    @POST
    @Path("/external/test")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response testExternal(AppSettings.ExternalBackup settings) {
        try {
            // A token left empty in the form means the saved one
            String token = settings.getToken().isEmpty()
                ? settingsService.getExternalBackup().getToken() : settings.getToken();
            externalBackupService.testConnection(settings.getUrl(), token);
            return Response.ok(Map.of("ok", true)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.BAD_GATEWAY).type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", e.getMessage())).build();
        }
    }

    /** Pushes a backup right now, ignoring the quiet time. */
    @POST
    @Path("/external/run")
    @Produces(MediaType.APPLICATION_JSON)
    public Response runExternal() {
        try {
            externalBackupService.runNow();
            return Response.ok(externalState(settingsService.getExternalBackup())).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.BAD_GATEWAY).type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", String.valueOf(e.getMessage()))).build();
        }
    }

    private Map<String, Object> externalState(AppSettings.ExternalBackup settings) {
        Map<String, Object> state = new LinkedHashMap<>();
        // The token is write-only: it is stored but never sent back to the browser
        Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("enabled", settings.isEnabled());
        shown.put("url", settings.getUrl());
        shown.put("token_set", !settings.getToken().isEmpty());
        shown.put("debounceSeconds", settings.getDebounceSeconds());
        shown.put("maxDelaySeconds", settings.getMaxDelaySeconds());
        state.put("settings", shown);
        state.put("status", externalBackupService.status());
        return state;
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST).type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message)).build();
    }
}
