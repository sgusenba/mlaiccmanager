package com.competition.portal;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The assembled server on a free port: static pages, the API, filters and error answers. */
class PortalServerTest {
    @TempDir
    Path dir;

    private Server server;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        Path staticDir = Files.createDirectories(dir.resolve("static"));
        Files.writeString(staticDir.resolve("index.html"), "<!DOCTYPE html><title>Portal</title>");
        server = PortalMain.createServer(new PortalConfig("127.0.0.1", 0, dir.resolve("data"), staticDir, "http://localhost"));
        server.start();
        base = "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    @AfterEach
    void stop() throws Exception {
        server.stop();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthIsOkAndUncached() throws Exception {
        HttpResponse<String> response = get("/api/health");
        assertEquals(200, response.statusCode());
        assertEquals("{\"ok\":true}", response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null));
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertTrue(response.headers().firstValue("Server").isEmpty(), "no server version");
    }

    @Test
    void healthFailsWhenTheDataCannotBeRead() throws Exception {
        Files.createDirectories(dir.resolve("data"));
        Files.writeString(dir.resolve("data/portal.json"), "{broken");
        HttpResponse<String> response = get("/api/health");
        assertEquals(503, response.statusCode());
        assertEquals("{\"ok\":false}", response.body());
    }

    @Test
    void servesThePages() throws Exception {
        HttpResponse<String> response = get("/");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("<title>Portal</title>"));
        assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElse(null));
    }

    @Test
    void unknownApiPathsAnswerWithAJsonError() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/nothing"))
            .header("Accept-Language", "de-AT").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
        assertEquals("{\"error\":\"Unter dieser Adresse gibt es nichts.\"}", response.body());
    }

    @Test
    void refusesBodiesOverOneMegabyte() throws Exception {
        byte[] big = new byte[(int) com.competition.portal.config.RequestSizeFilter.MAX_BYTES + 1];
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/health"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(big)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(413, response.statusCode());
        assertTrue(response.body().startsWith("{\"error\":"));
    }
}
