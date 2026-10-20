package com.competition.portal;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Where the portal listens and keeps its files. Each setting comes from a
 * system property (-Dportal.port=...) or else an environment variable
 * (PORTAL_PORT=..., as set by the systemd unit's EnvironmentFile), else the
 * default, which suits running it locally from the registration-portal folder.
 *
 * @param bind      address to listen on; 127.0.0.1 behind the reverse proxy, never 0.0.0.0 in production
 * @param port      HTTP port (0 picks a free one, for tests)
 * @param dataDir   portal.json and meets/ live here
 * @param staticDir the pages (static/)
 * @param baseUrl   the public address, e.g. https://reg.example.org; writes must come from this origin
 */
public record PortalConfig(String bind, int port, Path dataDir, Path staticDir, String baseUrl) {

    public static PortalConfig fromEnvironment() {
        int port = Integer.parseInt(setting("port", "8080"));
        return new PortalConfig(
            setting("bind", "127.0.0.1"),
            port,
            Paths.get(setting("data.dir", "portal-data")).toAbsolutePath(),
            Paths.get(setting("static.dir", "static")).toAbsolutePath(),
            setting("base.url", "http://localhost:" + port));
    }

    /** -Dportal.data.dir, else PORTAL_DATA_DIR, else the default. */
    private static String setting(String name, String fallback) {
        String property = System.getProperty("portal." + name);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        String env = System.getenv("PORTAL_" + name.toUpperCase().replace('.', '_'));
        return env != null && !env.isBlank() ? env.trim() : fallback;
    }
}
