package com.competition.resource;

import com.competition.service.BackupService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Map;

/** Download all runtime data as a zip, and restore it from one. */
@Path("/backup")
public class BackupResource {
    private static final Logger logger = LoggerFactory.getLogger(BackupResource.class);

    @Inject
    private BackupService backupService;

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
}
