package com.competition.resource;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

@Path("/build-info")
@Produces(MediaType.APPLICATION_JSON)
public class BuildInfoResource {
    private static final Logger logger = LoggerFactory.getLogger(BuildInfoResource.class);

    // Stamped by Maven resource filtering at build time
    private static final String BUILD_TIMESTAMP = loadBuildTimestamp();

    public BuildInfoResource() {
        // Default constructor for Jersey
    }

    @GET
    public Response getBuildInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("buildTime", BUILD_TIMESTAMP);
        return Response.ok(info).build();
    }

    private static String loadBuildTimestamp() {
        try (InputStream in = BuildInfoResource.class.getResourceAsStream("/build-info.properties")) {
            if (in == null) {
                return null;
            }
            Properties props = new Properties();
            props.load(in);
            String value = props.getProperty("build.timestamp");
            // Unfiltered placeholder means the resource wasn't processed by Maven
            return value == null || value.startsWith("${") ? null : value;
        } catch (Exception e) {
            logger.warn("Could not read build-info.properties", e);
            return null;
        }
    }
}
